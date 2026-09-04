package eu.inqudium.legatium.webclient.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import eu.inqudium.legatium.common.MdcKeys
import io.micrometer.tracing.Tracer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.web.reactive.function.client.WebClient

/**
 * ADR-0002 beside a REAL Micrometer Tracing bridge (Brave): the client observation Boot registers opens
 * the client span and injects the outgoing `traceparent` into the request BEFORE the filter chain runs,
 * so the event's `traceId` is the trace the call belongs to, its `spanId` the client span the peer will
 * see as its parent, the request id that trace id, and NO correlation header is added.
 */
@SpringBootTest(
    classes = [IntegrationApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["management.tracing.sampling.probability=1.0"],
)
class ClientRequestLoggingTracingIntegrationTest {
    @Autowired
    private lateinit var webClientBuilder: WebClient.Builder

    @Autowired
    private lateinit var tracer: Tracer

    private val logger = LoggerFactory.getLogger("http-adapter-exchange") as Logger
    private lateinit var appender: AwaitingAppender

    @BeforeEach
    fun setUp() {
        appender = AwaitingAppender().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.INFO
        peer.received.clear()
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `should join the event with the caller trace and add no correlation header on a traced call`() {
        // What is tested: the trace contract against a real bridge - an outer span is active on the
        //   subscribing thread, the client observation creates the child span and injects traceparent,
        //   the filter sees it.
        // Success criteria: the peer received a traceparent and NO X-Correlation-Id; the event's traceId
        //   is the outer span's trace id, its spanId a DIFFERENT span, the request id the trace id.
        // Why it matters: observational neutrality and the log-to-trace join, proven where the header
        //   actually comes from.
        // Given
        val outer = tracer.nextSpan().name("outer").start()
        val client = webClientBuilder.baseUrl(peer.baseUrl).build()

        // When
        tracer.withSpan(outer).use {
            client
                .get()
                .uri("/things/{id}", 1)
                .retrieve()
                .bodyToMono(String::class.java)
                .block()
        }
        outer.end()

        // Then
        val received = peer.received.single()
        assertThat(received.header("traceparent")).startsWith("00-${outer.context().traceId()}-")
        assertThat(received.header("X-Correlation-Id")).isNull()
        val event = appender.awaitEvents(1).single()
        assertThat(event.mdcPropertyMap)
            .containsEntry("traceId", outer.context().traceId())
            .containsEntry(MdcKeys.REQUEST_ID, outer.context().traceId())
        assertThat(event.mdcPropertyMap["spanId"]).matches("[0-9a-f]{16}").isNotEqualTo(outer.context().spanId())
    }

    @Test
    fun `should treat a call without an outer span as traced too because the client observation roots a trace`() {
        // What is tested: the identity decision when NO span is active on the subscribing thread -
        //   Boot's client observation still opens a span, so the request reaches the filter with a
        //   traceparent.
        // Success criteria: the peer received a traceparent and no X-Correlation-Id; the event's
        //   MDC carries traceId and spanId.
        // Why it matters: with a tracing bridge present there is no traceless call at all, so the
        //   correlation header must never appear next to a bridge - the trace id is the identity.
        // Given/When
        webClientBuilder
            .baseUrl(peer.baseUrl)
            .build()
            .get()
            .uri("/things/2")
            .retrieve()
            .bodyToMono(String::class.java)
            .block()

        // Then
        val received = peer.received.single()
        assertThat(received.header("traceparent")).isNotNull()
        assertThat(received.header("X-Correlation-Id")).isNull()
        assertThat(appender.awaitEvents(1).single().mdcPropertyMap).containsKeys("traceId", "spanId")
    }

    companion object {
        private lateinit var peer: PeerServer

        @JvmStatic
        @BeforeAll
        fun startPeer() {
            peer = PeerServer()
        }

        @JvmStatic
        @AfterAll
        fun stopPeer() {
            peer.close()
        }
    }
}
