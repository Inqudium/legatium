package eu.inqudium.legatium.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.sun.net.httpserver.HttpServer;
import eu.inqudium.legatium.restclient.logging.ClientRequestLoggingInterceptor;
import eu.inqudium.legatium.webclient.logging.ClientRequestLoggingFilter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Exercises the two shaded jars from the consumer's side of the Shade boundary (ADR-0003): the
 * inlined common classes, Boot's auto-configuration through the jars' own imports files, and one
 * exchange line per client against a real local peer. Everything asserted here is invisible to the
 * reactor's own tests, which run before packaging against the legatium-common module.
 */
@SpringBootTest(
        classes = SmokeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // Bounded the way a consumer bounds them: a stalled peer fails the job with a cause instead
        // of holding it until the runner's timeout.
        properties = {
            "spring.http.clients.connect-timeout=5s",
            "spring.http.clients.read-timeout=5s"
        })
class ShadedTwinsSmokeTest {
    private static final String SHARED_CLASS =
            "eu/inqudium/legatium/common/ClientLoggingMetrics.class";
    private static final String EXCHANGE_LOGGER = "adapter-http-exchange";
    private static final String BLOCKING = "blocking";
    private static final String REACTIVE = "reactive";

    /** The bound on every cross-thread wait here: the reactive result and the awaited lines. */
    private static final Duration AWAIT = Duration.ofSeconds(5);

    /**
     * How long a surplus line gets to show up after the two expected ones - it would follow them
     * within microseconds.
     */
    private static final Duration SETTLE = Duration.ofMillis(200);

    private static HttpServer peer;

    @Autowired private ApplicationContext context;
    @Autowired private RestClient.Builder restClientBuilder;
    @Autowired private WebClient.Builder webClientBuilder;

    private final AwaitingAppender captured = new AwaitingAppender();

    @BeforeAll
    static void startPeer() throws IOException {
        peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        peer.createContext(
                "/things",
                exchange -> {
                    byte[] body = "served".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        peer.start();
    }

    @AfterAll
    static void stopPeer() {
        peer.stop(0);
    }

    @BeforeEach
    void captureExchangeLogger() {
        captured.start();
        exchangeLogger().addAppender(captured);
    }

    @AfterEach
    void releaseExchangeLogger() {
        exchangeLogger().detachAppender(captured);
        captured.stop();
    }

    private static Logger exchangeLogger() {
        return (Logger) LoggerFactory.getLogger(EXCHANGE_LOGGER);
    }

    private static String peerUrl() {
        return "http://127.0.0.1:" + peer.getAddress().getPort() + "/things";
    }

    private static Object fieldOf(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst()
                .orElse(null);
    }

    @Test
    void should_carry_the_shared_classes_in_both_twin_jars_and_nowhere_else() throws IOException {
        // What is tested: where the JVM finds the inlined common classes - the resource must
        //   resolve from exactly the two twin jars, and from no legatium-common artifact.
        // Success criteria: two locations, one per twin jar; none mentions legatium-common.
        // Why it matters: a broken artifactSet or a dependency-reduced POM that still names the
        //   unpublished module surfaces here, not at the first consumer's NoClassDefFoundError.
        // Given/When
        List<String> jars =
                Collections.list(getClass().getClassLoader().getResources(SHARED_CLASS)).stream()
                        .map(URL::toString)
                        .toList();

        // Then
        assertThat(jars).hasSize(2);
        assertThat(jars).anySatisfy(jar -> assertThat(jar).contains("legatium-restclient-logging"));
        assertThat(jars).anySatisfy(jar -> assertThat(jar).contains("legatium-webclient-logging"));
        assertThat(jars).noneSatisfy(jar -> assertThat(jar).contains("legatium-common"));
    }

    @Test
    void should_auto_configure_both_twins_from_the_shaded_jars() {
        // What is tested: Boot's auto-configuration import of both twins - the imports files and
        //   the configuration classes must be present and loadable in the shaded jars.
        // Success criteria: the interceptor bean and the filter bean exist, one each.
        // Why it matters: a consumer adds the artifact and expects logging without configuration; a
        //   missing or filtered META-INF entry would ship a silent no-op.
        // Given/When/Then
        assertThat(context.getBeansOfType(ClientRequestLoggingInterceptor.class)).hasSize(1);
        assertThat(context.getBeansOfType(ClientRequestLoggingFilter.class)).hasSize(1);
    }

    @Test
    void should_log_one_exchange_line_per_client_against_a_real_peer() throws InterruptedException {
        // What is tested: the end-to-end path through the product jars - Boot's builders carry the
        //   customizers, the interceptor and the filter observe one call each against a local peer.
        // Success criteria: exactly one exchange event per client, told apart by the adapter name
        //   each call carries, both `-> 200` with adapter_outcome=success - and no third line
        //   follows.
        // Why it matters: it is the one place the shaded runtime is executed as a consumer
        //   executes it. The WebClient line is awaited, not read: the reactive twin emits AFTER it
        //   handed the body's completion on, so block() can return while the Reactor Netty thread
        //   is still emitting. The names are what tell "both twins once" from "one twin twice":
        //   without them, two lines from one twin - an exactly-once regression inside a shaded jar
        //   - would pass for one per twin.
        // Given
        RestClient restClient = restClientBuilder.build();
        WebClient webClient = webClientBuilder.build();

        // When
        String blocking =
                restClient
                        .get()
                        .uri(peerUrl())
                        .attribute(ClientRequestLoggingInterceptor.ADAPTER_NAME_ATTRIBUTE, BLOCKING)
                        .retrieve()
                        .body(String.class);
        String reactive =
                webClient
                        .get()
                        .uri(peerUrl())
                        .attribute(ClientRequestLoggingFilter.ADAPTER_NAME_ATTRIBUTE, REACTIVE)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(AWAIT);

        // Then
        assertThat(blocking).isEqualTo("served");
        assertThat(reactive).isEqualTo("served");
        List<ILoggingEvent> events = captured.awaitEvents(2);
        assertThat(events.stream().map(event -> fieldOf(event, "adapter_name")).toList())
                .containsExactlyInAnyOrder(BLOCKING, REACTIVE);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("-> 200");
            assertThat(fieldOf(event, "adapter_outcome")).isEqualTo("success");
        });
        // And: two calls, two lines - neither twin emitted a second one
        assertThat(captured.noEventWithin(SETTLE))
                .as("a third exchange event after the two expected ones")
                .isTrue();
    }

    /**
     * Collects the exchange logger's events and lets the test WAIT for a count: the reactive twin's
     * line is written on the connector's thread after the caller's block() already returned, so a
     * plain list read right after the call races the emission. Events are pinned with
     * prepareForDeferredProcessing() for the same cross-thread reason.
     */
    private static final class AwaitingAppender extends AppenderBase<ILoggingEvent> {
        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
        private final Semaphore arrivals = new Semaphore(0);

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            events.add(event);
            arrivals.release();
        }

        List<ILoggingEvent> awaitEvents(int count) throws InterruptedException {
            assertThat(arrivals.tryAcquire(count, AWAIT.toMillis(), TimeUnit.MILLISECONDS))
                    .as("%d exchange events within %s, got %d", count, AWAIT, events.size())
                    .isTrue();
            return List.copyOf(events);
        }

        /** True when nothing arrived within {@code settle}: the bounded check for a surplus. */
        boolean noEventWithin(Duration settle) throws InterruptedException {
            return !arrivals.tryAcquire(settle.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
