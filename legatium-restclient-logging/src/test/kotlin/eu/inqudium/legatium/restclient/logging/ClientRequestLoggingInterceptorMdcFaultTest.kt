package eu.inqudium.legatium.restclient.logging

import ch.qos.logback.classic.Level
import eu.inqudium.legatium.common.ClientLoggingMetrics
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.MdcKeys
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.slf4j.spi.MDCAdapter
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.mock.http.client.MockClientHttpResponse
import java.io.IOException
import java.util.Deque
import java.util.concurrent.atomic.AtomicLong

/**
 * The fail-open guards of [ClientRequestLoggingInterceptor] around the host's MDC adapter, driven with
 * a FAILING adapter swapped in through [installMdcAdapter] and restored after every test: the call-wide
 * scope's open and close, the caller's snapshot (ADR-0011) and the snapshot's own rollback. The adapter
 * delegates everything else to the original, so MDC state stays real, and it fails only while ARMED -
 * from the point of the call the test targets to the point after it - so the emission at close runs
 * against a working adapter and the guard under test is the only one that fires. Each guard costs its
 * feature, counts `stage=wiring`, leaves a breadcrumb on the module logger and leaves the call - and
 * an exception propagating out of it - untouched.
 */
class ClientRequestLoggingInterceptorMdcFaultTest {
    private val original: MDCAdapter = MDC.getMDCAdapter()
    private val ticker = AtomicLong(0)
    private val meterRegistry = SimpleMeterRegistry()
    private val properties = ClientLoggingProperties(loggerName = "adapter-http-exchange-mdc-fault-test")
    private val interceptor = interceptorWith(properties, ticker, meterRegistry)
    private val log = CapturedLogger(properties.loggerName)
    private val moduleLog = CapturedLogger(ClientRequestLoggingInterceptor::class.java.name, Level.WARN)
    private val pinned = PinnedMdcAppender().apply { start() }
    private val adapter = ArmedFailingAdapter(original)

    /**
     * Delegates to [delegate]; while [armed], `put` of a key in [failPut] and `remove` of one in
     * [failRemove] throw, and `getCopyOfContextMap` throws when [failCopy] is set.
     */
    private class ArmedFailingAdapter(
        private val delegate: MDCAdapter,
    ) : MDCAdapter by delegate {
        @Volatile
        var armed = false
        var failPut: Set<String> = emptySet()
        var failRemove: Set<String> = emptySet()
        var failCopy = false

        override fun put(
            key: String,
            value: String?,
        ) {
            if (armed && key in failPut) error("adapter put refused $key")
            delegate.put(key, value)
        }

        override fun remove(key: String) {
            if (armed && key in failRemove) error("adapter remove refused $key")
            delegate.remove(key)
        }

        override fun getCopyOfContextMap(): MutableMap<String, String>? {
            if (armed && failCopy) error("adapter copy refused")
            return delegate.copyOfContextMap
        }

        override fun pushByKey(
            key: String,
            value: String,
        ) = delegate.pushByKey(key, value)

        override fun popByKey(key: String): String? = delegate.popByKey(key)

        override fun getCopyOfDequeByKey(key: String): Deque<String>? = delegate.getCopyOfDequeByKey(key)

        override fun clearDequeByKey(key: String) = delegate.clearDequeByKey(key)
    }

    @BeforeEach
    fun installFailingAdapter() {
        MDC.clear()
        installMdcAdapter(adapter)
        log.logger.addAppender(pinned)
    }

    @AfterEach
    fun restoreAdapter() {
        installMdcAdapter(original)
        MDC.clear()
        log.logger.detachAppender(pinned)
        pinned.stop()
        log.detach()
        moduleLog.detach()
    }

    private fun breadcrumbs(): List<Pair<Level, String>> = moduleLog.events.map { it.level to it.formattedMessage }

    @Test
    fun `should run the call without the call scope and count stage wiring when the adapter refuses the scope's put`() {
        // What is tested: openCallScope - the guard around MdcScope's install for the wire call.
        // Success criteria: the call runs and its body is returned, without the identity on the thread
        //   during the call; one success event at close; stage=wiring at 1 with the ERROR breadcrumb,
        //   stage=emission at 0; the thread is left clean (MdcScope rolled the partial install back).
        // Why it matters: the call scope is logging-owned work; a throwing MDC adapter must degrade the
        //   identity feature, never the call.
        // Given: every put refused while the interceptor wires the call, working again inside the call
        adapter.failPut = setOf(MdcKeys.REQUEST_ID, MdcKeys.REQUEST_METHOD, MdcKeys.ROUTE)
        adapter.armed = true
        val execution = ObservingExecution(answering(body = "ok")) { MDC.getCopyOfContextMap().orEmpty().also { adapter.armed = false } }

        // When
        val body = interceptor.intercept(request(), ByteArray(0), execution).consumeAndClose()

        // Then
        assertThat(body).isEqualTo("ok")
        assertThat(execution.seen).doesNotContainKeys(MdcKeys.REQUEST_ID, MdcKeys.REQUEST_METHOD, MdcKeys.ROUTE)
        assertThat(keyValues(log.events.single())).containsEntry("adapter_outcome", "success")
        assertThat(meterRegistry.count(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "wiring")).isEqualTo(1.0)
        assertThat(meterRegistry.count(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "emission")).isZero()
        assertThat(breadcrumbs()).anySatisfy { (level, message) ->
            assertThat(level).isEqualTo(Level.ERROR)
            assertThat(message).contains("MDC scope could not be opened")
        }
        assertThat(MDC.getCopyOfContextMap().orEmpty()).isEmpty()
    }

    @Test
    fun `should return the response and count stage wiring when the call scope's restoration throws`() {
        // What is tested: closeCallScope - the guard around MdcScope's close in the interceptor's
        //   finally, on a successful call.
        // Success criteria: the response is returned and its body readable; stage=wiring at 1 with the
        //   WARN breadcrumb; the documented cost - the calling thread keeps the key whose removal was
        //   refused - is visible; the event at close is still a success.
        // Why it matters: a throwing adapter in the finally must not fail a call that succeeded.
        // Given: the removal of the request id refused from inside the wire call on
        adapter.failRemove = setOf(MdcKeys.REQUEST_ID)
        val execution =
            ClientHttpRequestExecution { _, _ ->
                adapter.armed = true
                MockClientHttpResponse("ok".toByteArray(), HttpStatus.OK)
            }

        // When
        val response = interceptor.intercept(request(), ByteArray(0), execution)
        adapter.armed = false

        // Then
        assertThat(MDC.get(MdcKeys.REQUEST_ID)).isEqualTo("generated-42")
        assertThat(MDC.get(MdcKeys.REQUEST_METHOD)).isNull()
        assertThat(meterRegistry.count(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "wiring")).isEqualTo(1.0)
        assertThat(breadcrumbs()).anySatisfy { (level, message) ->
            assertThat(level).isEqualTo(Level.WARN)
            assertThat(message).contains("MDC restoration failed")
        }
        assertThat(response.consumeAndClose()).isEqualTo("ok")
        assertThat(keyValues(log.events.single())).containsEntry("adapter_outcome", "success")
        assertThat(meterRegistry.count(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "emission")).isZero()
    }

    @Test
    fun `should not mask the call's own exception when the call scope's restoration throws`() {
        // What is tested: closeCallScope while an exception is already propagating out of the call -
        //   the reason the teardown is guarded separately.
        // Success criteria: the engine's IOException reaches the caller unchanged; the failure event
        //   was emitted; stage=wiring at 1, stage=emission at 0.
        // Why it matters: an unguarded close in the finally would replace the client's exception with
        //   the adapter's, and the client's error mapping would see the wrong one.
        // Given: the removal of the request id refused from inside the wire call on, and a call that dies
        adapter.failRemove = setOf(MdcKeys.REQUEST_ID)
        val execution =
            ClientHttpRequestExecution { _, _ ->
                adapter.armed = true
                throw IOException("connection reset")
            }

        // When
        val thrown = catchThrowable { interceptor.intercept(request(), ByteArray(0), execution) }
        adapter.armed = false

        // Then
        assertThat(thrown).isInstanceOf(IOException::class.java).hasMessage("connection reset")
        assertThat(keyValues(log.events.single())).containsEntry("adapter_outcome", "failure")
        assertThat(meterRegistry.count(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "wiring")).isEqualTo(1.0)
        assertThat(meterRegistry.count(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "emission")).isZero()
    }

    @Test
    fun `should log without the caller's keys and count stage wiring when the caller's MDC cannot be captured`() {
        // What is tested: captureCallerMdcQuietly - the guard around the snapshot of ADR-0011 at wiring.
        // Success criteria: the call runs; a close on another thread logs the event with the module's
        //   own identity and WITHOUT the caller's key; stage=wiring at 1 with the WARN breadcrumb.
        // Why it matters: the snapshot is an extra; a failing extra must cost the caller's keys on the
        //   other thread, never the wiring.
        // Given: a caller key on this thread, and the adapter's copy refused while the call is wired
        MDC.put("endpoint_request_id", "inbound-7")
        adapter.failCopy = true
        adapter.armed = true
        val response = interceptor.intercept(request(), ByteArray(0), answering(body = "ok"))
        adapter.armed = false

        // When
        onAnotherThread { response.consumeAndClose() }

        // Then
        val event = pinned.events.single()
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "success")
        assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "generated-42").doesNotContainKey("endpoint_request_id")
        assertThat(meterRegistry.count(ClientLoggingMetrics.FAIL_OPEN_METER, "stage", "wiring")).isEqualTo(1.0)
        assertThat(breadcrumbs()).anySatisfy { (level, message) ->
            assertThat(level).isEqualTo(Level.WARN)
            assertThat(message).contains("could not be captured")
        }
    }

    @Test
    fun `should roll back a partial install of the caller's snapshot and propagate the failure`() {
        // What is tested: CallerMdcSnapshot.restore's rollback - the adapter refuses one of two keys
        //   mid-install on the other thread.
        // Success criteria: the install throws; the other thread is left exactly as it was - its own
        //   value for the key it carried, nothing for the key it did not - whichever key failed first.
        // Why it matters: half a caller identity on a pooled thread would join that thread's next
        //   lines to the wrong request.
        // Given: a two-key snapshot, one key's put refused on the other thread
        MDC.put("a_key", "1")
        MDC.put("b_key", "2")
        val snapshot = CallerMdcSnapshot.capture()
        adapter.failPut = setOf("b_key")

        // When
        val seen =
            onAnotherThread {
                MDC.put("a_key", "foreign")
                adapter.armed = true
                val thrown = catchThrowable { snapshot.restore() }
                adapter.armed = false
                Triple(thrown, MDC.get("a_key"), MDC.get("b_key"))
            }

        // Then
        assertThat(seen.first).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("b_key")
        assertThat(seen.second).isEqualTo("foreign")
        assertThat(seen.third).isNull()
    }

    @Test
    fun `should restore every key it can and rethrow the first failure with the rest suppressed on close`() {
        // What is tested: CallerMdcSnapshot's best-effort close - two keys whose removal is refused.
        // Success criteria: close throws the first failure with the second suppressed; a third key the
        //   adapter does remove is gone.
        // Why it matters: one refused key must not leave the remaining caller keys on the pooled thread.
        // Given: a three-key snapshot installed on the other thread, two removals refused at close
        MDC.put("a_key", "1")
        MDC.put("b_key", "2")
        MDC.put("c_key", "3")
        val snapshot = CallerMdcSnapshot.capture()
        adapter.failRemove = setOf("a_key", "b_key")

        // When
        val seen =
            onAnotherThread {
                val scope = snapshot.restore()
                adapter.armed = true
                val thrown = catchThrowable { scope.close() }
                adapter.armed = false
                Triple(thrown, MDC.get("c_key"), MDC.getCopyOfContextMap().orEmpty().keys)
            }

        // Then
        assertThat(seen.first).isInstanceOf(IllegalStateException::class.java)
        assertThat(requireNotNull(seen.first).suppressed).hasSize(1)
        assertThat(seen.second).isNull()
        assertThat(seen.third).containsExactlyInAnyOrder("a_key", "b_key")
    }
}
