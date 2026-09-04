package eu.inqudium.legatium.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import eu.inqudium.legatium.restclient.logging.ClientRequestLoggingInterceptor;
import eu.inqudium.legatium.webclient.logging.ClientRequestLoggingFilter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Exercises the two shaded jars from the consumer's side of the Shade boundary (ADR-0003): the inlined
 * common classes, Boot's auto-configuration through the jars' own imports files, and one exchange line
 * per client against a real local peer. Everything asserted here is invisible to the reactor's own
 * tests, which run before packaging against the legatium-common module.
 */
@SpringBootTest(classes = SmokeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ShadedTwinsSmokeTest {
    private static final String SHARED_CLASS = "eu/inqudium/legatium/common/ClientLoggingMetrics.class";
    private static final String EXCHANGE_LOGGER = "adapter-http-exchange";

    private static HttpServer peer;

    @Autowired private ApplicationContext context;
    @Autowired private RestClient.Builder restClientBuilder;
    @Autowired private WebClient.Builder webClientBuilder;

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

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

    @Test
    void should_carry_the_shared_classes_in_both_twin_jars_and_nowhere_else() throws IOException {
        // What is tested: where the JVM finds the inlined common classes - the resource must resolve
        //   from exactly the two twin jars, and from no legatium-common artifact.
        // Success criteria: two locations, one per twin jar; none mentions legatium-common.
        // Why it matters: a broken artifactSet or a dependency-reduced POM that still names the
        //   unpublished module surfaces here, not at the first consumer's NoClassDefFoundError.
        // Given/When
        List<URL> locations = Collections.list(getClass().getClassLoader().getResources(SHARED_CLASS));
        List<String> jars = new ArrayList<>();
        for (URL location : locations) {
            jars.add(location.toString());
        }

        // Then
        assertThat(jars).hasSize(2);
        assertThat(jars).anySatisfy(jar -> assertThat(jar).contains("legatium-restclient-logging"));
        assertThat(jars).anySatisfy(jar -> assertThat(jar).contains("legatium-webclient-logging"));
        assertThat(jars).noneSatisfy(jar -> assertThat(jar).contains("legatium-common"));
    }

    @Test
    void should_auto_configure_both_twins_from_the_shaded_jars() {
        // What is tested: Boot's auto-configuration import of both twins - the imports files and the
        //   configuration classes must be present and loadable in the shaded jars.
        // Success criteria: the interceptor bean and the filter bean exist, one each.
        // Why it matters: a consumer adds the artifact and expects logging without configuration; a
        //   missing or filtered META-INF entry would ship a silent no-op.
        // Given/When/Then
        assertThat(context.getBeansOfType(ClientRequestLoggingInterceptor.class)).hasSize(1);
        assertThat(context.getBeansOfType(ClientRequestLoggingFilter.class)).hasSize(1);
    }

    @Test
    void should_log_one_exchange_line_per_client_against_a_real_peer() {
        // What is tested: the end-to-end path through the product jars - Boot's builders carry the
        //   customizers, the interceptor and the filter observe one call each against a local peer.
        // Success criteria: exactly two exchange events, one per client, both `-> 200` and both with
        //   adapter_outcome=success.
        // Why it matters: it is the one place the shaded runtime is executed as a consumer executes it.
        // Given
        RestClient restClient = restClientBuilder.build();
        WebClient webClient = webClientBuilder.build();

        // When
        String blocking = restClient.get().uri(peerUrl()).retrieve().body(String.class);
        String reactive = webClient.get().uri(peerUrl()).retrieve().bodyToMono(String.class).block();

        // Then
        assertThat(blocking).isEqualTo("served");
        assertThat(reactive).isEqualTo("served");
        List<ILoggingEvent> events = captured.list;
        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("-> 200");
            assertThat(outcomeOf(event)).isEqualTo("success");
        });
    }

    private static Object outcomeOf(ILoggingEvent event) {
        for (KeyValuePair pair : event.getKeyValuePairs()) {
            if ("adapter_outcome".equals(pair.key)) {
                return pair.value;
            }
        }
        return null;
    }
}
