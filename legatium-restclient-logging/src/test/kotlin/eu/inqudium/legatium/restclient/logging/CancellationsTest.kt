package eu.inqudium.legatium.restclient.logging

import org.apache.hc.client5.http.impl.classic.RequestFailedException
import org.apache.hc.core5.http.ConnectionRequestTimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.channels.ClosedByInterruptException

/**
 * The cause-graph fallback of the blocking twin's cancellation classification (ADR-0015): which failures
 * report a caller that stopped waiting even when the interrupt flag is gone.
 */
class CancellationsTest {
    @Test
    fun `should recognise an interrupt through the wrapping cause chain`() {
        // What is tested: the two types that mean an interrupt and nothing else, wrapped the way the
        //   request factories wrap them (Spring's JDK and Jetty factories: IOException over the
        //   InterruptedException; a Reactor block: a RuntimeException over it).
        // Success criteria: both classify as a cancellation, as does an interrupt carried only as a
        //   suppressed exception.
        // Why it matters: the flag can already be consumed when the exception reaches the interceptor;
        //   the chain is then the only evidence that the caller left.
        // Given
        val interrupted = IOException("Request was interrupted: null", InterruptedException())
        val reactor = RuntimeException(IllegalStateException("wrapped", InterruptedException()))
        val channel = IOException("io", ClosedByInterruptException())
        val suppressed = IOException("io").apply { addSuppressed(InterruptedException()) }

        // When/Then
        assertThat(Cancellations.isCancellation(interrupted)).isTrue()
        assertThat(Cancellations.isCancellation(reactor)).isTrue()
        assertThat(Cancellations.isCancellation(channel)).isTrue()
        assertThat(Cancellations.isCancellation(suppressed)).isTrue()
    }

    @Test
    fun `should recognise the JDK's socket closed by interrupt and Apache's aborted request by message`() {
        // What is tested: the two message-matched fallbacks - the JDK's SocketException of a socket it
        //   closed on an interrupt, Apache's RequestFailedException of an aborted lease or execution.
        // Success criteria: both classify as a cancellation, also when wrapped.
        // Why it matters: these are the exceptions an interrupted blocking call actually throws on the
        //   HttpURLConnection and Apache engines.
        // Given
        val socket = SocketException("Closed by interrupt")
        val aborted = IOException("wrapped", RequestFailedException("Request aborted", InterruptedException()))
        val abortedWithoutCause = RequestFailedException("Request aborted")

        // When/Then
        assertThat(Cancellations.isCancellation(socket)).isTrue()
        assertThat(Cancellations.isCancellation(aborted)).isTrue()
        assertThat(Cancellations.isCancellation(abortedWithoutCause)).isTrue()
    }

    @Test
    fun `should not read timeouts, plain interrupted I-O or other failures as a cancellation`() {
        // What is tested: the controls - the InterruptedIOException family that are timeouts, a plain
        //   InterruptedIOException, the same exception classes with other messages, a refusal.
        // Success criteria: none classifies as a cancellation; null is none either.
        // Why it matters: "an InterruptedIOException means cancelled" would turn every read timeout and
        //   every exhausted pool into a departure of the caller; Apache's failed lease
        //   ("Request execution failed") is a failure of the call, not of the caller's patience.
        // Given
        val failures =
            listOf(
                SocketTimeoutException("Read timed out"),
                ConnectionRequestTimeoutException("Timeout deadline: 200 MILLISECONDS"),
                InterruptedIOException("interrupted"),
                SocketException("Connection reset"),
                ConnectException("Connection refused"),
                RequestFailedException("Request execution failed", IOException("boom")),
            )

        // When/Then
        failures.forEach { assertThat(Cancellations.isCancellation(it)).describedAs("%s", it).isFalse() }
        assertThat(Cancellations.isCancellation(null)).isFalse()
    }
}
