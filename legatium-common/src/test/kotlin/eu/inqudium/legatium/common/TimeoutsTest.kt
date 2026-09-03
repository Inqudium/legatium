package eu.inqudium.legatium.common

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
    fun `should recognise a Netty timeout by its class name without a Netty dependency`() {
        // What is tested: the by-name match for io.netty.handler.timeout.TimeoutException, which the
        //   WebClient twin meets as Reactor Netty's ReadTimeoutException but neither twin depends on.
        // Success criteria: a throwable whose superclass chain carries that name is a timeout.
        // Why it matters: the WebClient twin's most common timeout would otherwise log as a plain failure.
        // Given: a stand-in class under the Netty name, defined in this test's own classloader
        val nettyLike = NettyNamedTimeout.create()

        // When/Then
        assertThat(Timeouts.isTimeout(RuntimeException("wrapped", nettyLike))).isTrue()
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
}
