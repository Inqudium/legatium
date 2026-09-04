package eu.inqudium.legatium.common

import io.netty.channel.ConnectTimeoutException
import io.netty.handler.timeout.ReadTimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.http.HttpConnectTimeoutException
import java.util.concurrent.TimeoutException

/**
 * The timeout classification both twins share: which failures of an outbound call are reported as
 * `timeout` instead of a plain `failure`.
 */
class TimeoutsTest {
    @Test
    fun `should recognise the JDK timeout types through a wrapping cause chain`() {
        // What is tested: the cause walk - clients never throw the raw timeout, they wrap it
        //   (ResourceAccessException over SocketTimeoutException, a client exception over a
        //   TimeoutException from a future).
        // Success criteria: a timeout anywhere in the chain classifies the failure as a timeout.
        // Why it matters: a timeout logged as a generic failure hides the one client-side disposition
        //   an operator reads differently from every other one.
        // Given: three wrapped JDK timeouts
        val socket = IllegalStateException("wrapped", IOException("io", SocketTimeoutException("read timed out")))
        val future = RuntimeException("wrapped", TimeoutException("future"))
        val connect = RuntimeException("wrapped", HttpConnectTimeoutException("connect"))

        // When/Then
        assertThat(Timeouts.isTimeout(socket)).isTrue()
        assertThat(Timeouts.isTimeout(future)).isTrue()
        assertThat(Timeouts.isTimeout(connect)).isTrue()
    }

    @Test
    fun `should recognise Netty's read and connect timeouts by their class names`() {
        // What is tested: the by-name matches for io.netty.handler.timeout.TimeoutException (Reactor
        //   Netty's ReadTimeoutException is one) and io.netty.channel.ConnectTimeoutException (a
        //   java.net.ConnectException that no JDK timeout type covers) - against the REAL classes, Netty
        //   being a test-scoped dependency here while neither twin depends on it.
        // Success criteria: both, wrapped the way the WebClient twin meets them, classify as a timeout.
        // Why it matters: the WebClient twin's most common timeouts would otherwise log as plain failures.
        // Given
        val read = RuntimeException("wrapped", ReadTimeoutException.INSTANCE)
        val connect = RuntimeException("wrapped", ConnectTimeoutException("connection timed out after 200 ms"))

        // When/Then
        assertThat(Timeouts.isTimeout(read)).isTrue()
        assertThat(Timeouts.isTimeout(connect)).isTrue()
    }

    @Test
    fun `should not classify ordinary failures or a missing throwable as a timeout`() {
        // Given/When/Then: I/O errors, state errors and null are plain failures
        assertThat(Timeouts.isTimeout(IOException("connection reset"))).isFalse()
        assertThat(Timeouts.isTimeout(IllegalStateException("boom", IOException("reset")))).isFalse()
        assertThat(Timeouts.isTimeout(null)).isFalse()
    }

    @Test
    fun `should terminate on a cyclic cause chain`() {
        // What is tested: the visited-set guard of the cause walk - Throwable permits a cycle.
        // Success criteria: the call returns (false) instead of looping.
        // Why it matters: an unbounded walk on a pooled thread is a hang, not a log line.
        // Given: a two-element cause cycle
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        // When/Then
        assertThat(Timeouts.isTimeout(a)).isFalse()
    }

    @Test
    fun `should recognise a timeout carried as a suppressed exception of a composite error`() {
        // What is tested: the walk over SUPPRESSED exceptions - Reactor's composite errors (zip/merge/when)
        //   carry their components as suppressed, not as the cause.
        // Success criteria: a composite whose only timeout is suppressed classifies as a timeout; a
        //   suppressed cycle terminates.
        // Why it matters: a timeout hidden in a composite would log as a plain failure on the WebClient twin.
        // Given: a composite with a timeout among its suppressed components, and a self-referencing one
        val composite =
            RuntimeException("Multiple exceptions").apply {
                addSuppressed(IOException("reset"))
                addSuppressed(TimeoutException("late"))
            }
        val cyclic = RuntimeException("cycle").also { it.addSuppressed(it) }

        // When/Then
        assertThat(Timeouts.isTimeout(composite)).isTrue()
        assertThat(Timeouts.isTimeout(cyclic)).isFalse()
    }
}
