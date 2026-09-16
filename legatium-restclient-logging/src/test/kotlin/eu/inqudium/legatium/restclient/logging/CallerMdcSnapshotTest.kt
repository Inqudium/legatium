package eu.inqudium.legatium.restclient.logging

import eu.inqudium.legatium.common.MdcKeys
import eu.inqudium.legatium.common.TraceMdcKeys
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC

/**
 * The caller's MDC snapshot (ADR-0011): what it captures, on which thread it applies, and how it leaves
 * the emitting thread afterwards.
 */
class CallerMdcSnapshotTest {
    @BeforeEach
    fun clearMdc() = MDC.clear()

    @AfterEach
    fun tearDown() = MDC.clear()

    @Test
    fun `should capture the caller's entries without the module's own and the trace keys`() {
        // What is tested: capture - the filter on the snapshot.
        // Success criteria: an ambient key is captured; adapter_* keys of an enclosing call scope and a
        //   bridge's traceId/spanId are not.
        // Why it matters: those keys belong to the emission's MdcScope - a nested client call must
        //   not carry the outer call's identity in its snapshot, and a stale bridge id must never reach an
        //   event through the back door the snapshot would open.
        // Given
        MDC.put("endpoint_request_id", "inbound-7")
        MDC.put(MdcKeys.REQUEST_ID, "outer-call")
        MDC.put(MdcKeys.REQUEST_METHOD, "GET")
        MDC.put(MdcKeys.ROUTE, "https://outer")
        MDC.put(TraceMdcKeys.TRACE_ID, "stale")
        MDC.put(TraceMdcKeys.SPAN_ID, "stale")

        // When/Then
        assertThat(CallerMdcSnapshot.capture().entries).containsOnly(entry("endpoint_request_id", "inbound-7"))
    }

    @Test
    fun `should be NONE for an empty MDC`() {
        // What is tested: the cost floor - no MDC, no map.
        // Success criteria: capture on a thread without MDC yields the NONE instance.
        // Why it matters: the snapshot is taken on every call; a host without MDC must pay nothing.
        // Given/When/Then
        assertThat(CallerMdcSnapshot.capture()).isSameAs(CallerMdcSnapshot.NONE)
    }

    @Test
    fun `should install the entries on another thread and restore that thread's own values on close`() {
        // What is tested: restore off the capturing thread - the case the snapshot exists for.
        // Success criteria: inside the scope the other thread sees the caller's value for a key it
        //   carried differently and keeps a key only it has; after close it is exactly as before.
        // Why it matters: a pooled reader thread may carry another request's identity - the caller's
        //   wins for its keys - and must be left as found.
        // Given
        MDC.put("endpoint_request_id", "inbound-7")
        val snapshot = CallerMdcSnapshot.capture()

        // When/Then
        val seen =
            onAnotherThread {
                MDC.put("endpoint_request_id", "foreign")
                MDC.put("worker_only", "w")
                val inside = snapshot.restore().use { MDC.getCopyOfContextMap() }
                Triple(inside, MDC.get("endpoint_request_id"), MDC.get("worker_only"))
            }
        assertThat(seen.first).containsEntry("endpoint_request_id", "inbound-7").containsEntry("worker_only", "w")
        assertThat(seen.second).isEqualTo("foreign")
        assertThat(seen.third).isEqualTo("w")
    }

    @Test
    fun `should be a no-op on the capturing thread`() {
        // What is tested: the thread check - the snapshot never overrides the caller's own, live MDC.
        // Success criteria: a value the caller updated after the capture is what the scope shows.
        // Why it matters: on the caller's thread the MDC is the truth; a stale copy would override a
        //   legitimate update for the duration of the log statement.
        // Given
        MDC.put("endpoint_request_id", "v1")
        val snapshot = CallerMdcSnapshot.capture()
        MDC.put("endpoint_request_id", "v2")

        // When/Then
        snapshot.restore().use {
            assertThat(MDC.get("endpoint_request_id")).isEqualTo("v2")
        }
        assertThat(MDC.get("endpoint_request_id")).isEqualTo("v2")
    }
}
