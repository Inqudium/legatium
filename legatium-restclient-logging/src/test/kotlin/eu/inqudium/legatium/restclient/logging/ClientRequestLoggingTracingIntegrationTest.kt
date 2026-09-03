package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.MdcKeys
import io.micrometer.tracing.Tracer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.web.client.RestClient

/**
 * ADR-0002 beside a REAL Micrometer Tracing bridge (Brave): the client observation Boot registers opens
 * the client span and injects the outgoing `traceparent` BEFORE the interceptors run, so the event's
 * `traceId` is the trace the call belongs to, its `spanId` is the client span the peer will see as its
 * parent, the request id is that trace id, and NO correlation header is added. A Boot upgrade that
 * reorders observation and interception breaks this suite instead of silently dropping trace ids.
 */
@SpringBootTest(
    classes = [IntegrationApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["management.tracing.sampling.probability=1.0"],
)
class ClientRequestLoggingTracingIntegrationTest {
    @Autowired
    private lateinit var restClientBuilder: RestClient.Builder

    @Autowired
    private lateinit var tracer: Tracer

    private lateinit var log: CapturedLogger

    @BeforeEach
    fun setUp() {
        log = CapturedLogger("http-client-exchange")
        peer.received.clear()
    }

    @AfterEach
    fun tearDown() {
        log.detach()
    }

    @Test
    fun `should join the event with the caller trace and add no correlation header on a traced call`() {
        // What is tested: the trace contract against a real bridge - an outer span is active, the
        //   client observation creates the child span and injects traceparent, the interceptor sees it.
        // Success criteria: the peer received a traceparent and NO X-Correlation-Id; the event's traceId
        //   is the outer span's trace id, its spanId is a DIFFERENT span (the client span), and the
        //   request id is the trace id.
        // Why it matters: this is the observational neutrality and the log-to-trace join ADR-0002
        //   promises, proven where the header actually comes from.
        // Given: an active outer span
        val outer = tracer.nextSpan().name("outer").start()
        val client = restClientBuilder.baseUrl(peer.baseUrl).build()

        // When
        tracer.withSpan(outer).use {
            client
                .get()
                .uri("/things/{id}", 1)
                .retrieve()
                .body(String::class.java)
        }
        outer.end()

        // Then
        val received = peer.received.single()
        assertThat(received.header("traceparent")).startsWith("00-${outer.context().traceId()}-")
        assertThat(received.header("X-Correlation-Id")).isNull()
        val event = log.events.single()
        assertThat(event.mdcPropertyMap)
            .containsEntry("traceId", outer.context().traceId())
            .containsEntry(MdcKeys.REQUEST_ID, outer.context().traceId())
        assertThat(event.mdcPropertyMap["spanId"]).matches("[0-9a-f]{16}").isNotEqualTo(outer.context().spanId())
        assertThat(event.formattedMessage).contains(" traceId=${outer.context().traceId()} spanId=${event.mdcPropertyMap["spanId"]}]")
    }

    @Test
    fun `should treat a call without an outer span as traced too because the client observation roots a trace`() {
        // What is tested: the boundary an operator must know - with tracing configured, EVERY call is
        //   traced: the client observation roots a trace when none is active, so the module never
        //   generates a correlation id in such a host.
        // Success criteria: traceparent on the wire, no correlation header, trace keys on the event.
        // Why it matters: a dashboard counting `generated` ids would otherwise be read as a
        //   propagation regression.
        // Given/When
        restClientBuilder
            .baseUrl(peer.baseUrl)
            .build()
            .get()
            .uri("/things/2")
            .retrieve()
            .body(String::class.java)

        // Then
        val received = peer.received.single()
        assertThat(received.header("traceparent")).isNotNull()
        assertThat(received.header("X-Correlation-Id")).isNull()
        assertThat(log.events.single().mdcPropertyMap).containsKeys("traceId", "spanId")
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
