package eu.inqudium.legatium.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.slf4j.spi.MDCAdapter
import java.util.Deque

/**
 * Partial-install rollback and best-effort restoration of [MdcScope] against a FAILING MDC
 * adapter. SLF4J exposes no public adapter setter, so the
 * package-private `MDC.setMDCAdapter` is invoked reflectively and the original adapter is restored
 * after every test; the failing adapter delegates everything else to the original, so MDC state stays
 * real.
 */
class MdcScopeTest {
    private lateinit var original: MDCAdapter

    /** Delegates to [delegate]; throws on `put` of the keys in [failPut] and on `remove` of those in [failRemove]. */
    private class FailingAdapter(
        private val delegate: MDCAdapter,
        private val failPut: Set<String> = emptySet(),
        private val failRemove: Set<String> = emptySet(),
    ) : MDCAdapter by delegate {
        override fun put(
            key: String,
            value: String?,
        ) {
            if (key in failPut) throw IllegalStateException("adapter put failed for $key")
            delegate.put(key, value)
        }

        override fun remove(key: String) {
            if (key in failRemove) throw IllegalStateException("adapter remove failed for $key")
            delegate.remove(key)
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
    fun setUp() {
        original = MDC.getMDCAdapter()
        MDC.clear()
    }

    @AfterEach
    fun tearDown() {
        installMdcAdapter(original)
        MDC.clear()
    }

    @Test
    fun `should roll back the keys already installed when a later put fails and keep the install exception`() {
        // What is tested: the partial-install rollback - the adapter fails on the THIRD key.
        // Success criteria: the install exception propagates as-is, and the two keys installed before it
        //   are gone from the MDC (pooled-thread hygiene).
        // Why it matters: half an identity on a pooled thread contaminates the next request's logs.
        // Given: an adapter failing on adapter_route
        installMdcAdapter(FailingAdapter(original, failPut = setOf(MdcKeys.ROUTE)))

        // When: the scope is opened
        val thrown = catchThrowable { MdcScope("corr-1", "GET", "https://api.example.com/things") }

        // Then: the ORIGINAL exception, and nothing left behind
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java).hasMessageContaining(MdcKeys.ROUTE)
        assertThat(MDC.get(MdcKeys.REQUEST_ID)).isNull()
        assertThat(MDC.get(MdcKeys.REQUEST_METHOD)).isNull()
    }

    @Test
    fun `should restore every remaining key when one restoration fails and attach later failures as suppressed`() {
        // What is tested: best-effort restoration on close - the adapter fails on TWO keys' removes.
        // Success criteria: close throws the first failure with the second attached as suppressed, and the
        //   other keys were still restored.
        // Why it matters: a restoration loop that stops at the first failure leaves module-owned MDC on
        //   the thread for every later key - exactly the contamination the scope exists to prevent.
        // Given: a scope opened against a healthy adapter, then an adapter failing on the removes of
        //   adapter_request_id AND adapter_method
        val scope = MdcScope("corr-1", "GET", "https://api.example.com/things")
        installMdcAdapter(FailingAdapter(original, failRemove = setOf(MdcKeys.REQUEST_ID, MdcKeys.REQUEST_METHOD)))

        // When: the scope closes
        val thrown = catchThrowable { scope.close() }

        // Then: the FIRST failure surfaces, the second rides along as suppressed, the healthy key is gone,
        //   the refused ones remain (the adapter refused to remove them)
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("remove failed for")
        assertThat(thrown.suppressed).hasSize(1)
        assertThat(setOf(thrown.message, thrown.suppressed.single().message))
            .containsExactlyInAnyOrder("adapter remove failed for ${MdcKeys.REQUEST_ID}", "adapter remove failed for ${MdcKeys.REQUEST_METHOD}")
        assertThat(MDC.get(MdcKeys.ROUTE)).isNull()
        assertThat(MDC.get(MdcKeys.REQUEST_ID)).isEqualTo("corr-1")
        assertThat(MDC.get(MdcKeys.REQUEST_METHOD)).isEqualTo("GET")
    }

    @Test
    fun `should restore a previous value of a module-owned key instead of removing it`() {
        // What is tested: the additive-overlay promise for a NESTED scope - an outer scope (or an ambient
        //   owner of the same keys) has values in place; the inner scope overlays and must put them back.
        // Success criteria: inside the scope the inner values are visible; after close the outer values
        //   are back, key for key - including an owned trace key that the inner scope removed.
        // Why it matters: an outbound call made while another outbound call's scope is active (a retry
        //   inside a client, a nested adapter) must not erase the outer identity.
        // Given: ambient values under the module's keys, and a bridge span id
        MDC.put(MdcKeys.REQUEST_ID, "outer-id")
        MDC.put(MdcKeys.REQUEST_METHOD, "POST")
        MDC.put(MdcKeys.ROUTE, "https://outer/route")
        MDC.put(TraceMdcKeys.SPAN_ID, "bridge-span")

        // When: an inner scope with a parsed trace id but no span id, owning the trace keys
        MdcScope("inner-id", "GET", "https://inner/route", traceId = "4bf92f3577b34da6a3ce929d0e0e4736", ownsTraceKeys = true).use {
            assertThat(MDC.get(MdcKeys.REQUEST_ID)).isEqualTo("inner-id")
            assertThat(MDC.get(MdcKeys.REQUEST_METHOD)).isEqualTo("GET")
            assertThat(MDC.get(TraceMdcKeys.TRACE_ID)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736")
            assertThat(MDC.get(TraceMdcKeys.SPAN_ID)).isNull()
        }

        // Then: every previous value is back
        assertThat(MDC.get(MdcKeys.REQUEST_ID)).isEqualTo("outer-id")
        assertThat(MDC.get(MdcKeys.REQUEST_METHOD)).isEqualTo("POST")
        assertThat(MDC.get(MdcKeys.ROUTE)).isEqualTo("https://outer/route")
        assertThat(MDC.get(TraceMdcKeys.TRACE_ID)).isNull()
        assertThat(MDC.get(TraceMdcKeys.SPAN_ID)).isEqualTo("bridge-span")
    }

    @Test
    fun `should attach a failing rollback to the install exception instead of replacing it`() {
        // What is tested: the nested failure in the init block - the put fails AND the rollback's remove
        //   fails on a key installed before it.
        // Success criteria: the install exception propagates with the rollback failure attached as
        //   suppressed; the key the rollback could remove is gone.
        // Why it matters: a rollback exception replacing the original would hide the actual cause of the
        //   broken adapter behind its own follow-up failure.
        // Given: an adapter whose put of adapter_route fails AND whose remove of adapter_request_id fails
        installMdcAdapter(FailingAdapter(original, failPut = setOf(MdcKeys.ROUTE), failRemove = setOf(MdcKeys.REQUEST_ID)))

        // When: the scope is opened (install fails, rollback partially fails)
        val thrown = catchThrowable { MdcScope("corr-1", "GET", "https://api.example.com/things") }

        // Then: the install exception wins, the rollback failure rides along as suppressed
        assertThat(thrown).hasMessageContaining("put failed for ${MdcKeys.ROUTE}")
        assertThat(thrown.suppressed).anySatisfy { assertThat(it).hasMessageContaining("remove failed for ${MdcKeys.REQUEST_ID}") }
        assertThat(MDC.get(MdcKeys.REQUEST_METHOD)).isNull()
    }
}
