package eu.inqudium.legatium.restclient.logging

import ch.qos.logback.classic.Level
import eu.inqudium.legatium.common.BodyLogMode
import eu.inqudium.legatium.common.CapturedLogger
import eu.inqudium.legatium.common.ClientLoggingMetrics
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.count
import eu.inqudium.legatium.common.keyValues
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.hc.client5.http.impl.classic.RequestFailedException
import org.apache.hc.core5.http.ConnectionRequestTimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.mock.http.client.MockClientHttpResponse
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * The blocking twin's `cancelled` disposition (ADR-0015): a call that failed because the CALLER stopped
 * waiting - its thread interrupted, or an interrupt in the failure's cause chain - logs WARN
 * `cancelled` without a cause; the interrupt flag is read, never cleared. Deterministic: the flag is set
 * by the execution on the test thread itself; the real engines are pinned in [RequestFactoryContract].
 */
class ClientRequestLoggingInterceptorCancellationTest {
    private val registry = SimpleMeterRegistry()
    private val properties = ClientLoggingProperties(loggerName = "adapter-http-exchange-cancellation-test")
    private val interceptor = interceptorWith(properties, AtomicLong(), registry)
    private val log = CapturedLogger(properties.loggerName)

    @AfterEach
    fun tearDown() {
        // A test that interrupted the thread clears the flag it set; JUnit's next test must not inherit it.
        Thread.interrupted()
        log.detach()
    }

    /** An execution that interrupts the calling thread, as the caller's cancellation would, then throws [failure]. */
    private fun interruptedThrowing(failure: Exception) =
        ClientHttpRequestExecution { _, _ ->
            Thread.currentThread().interrupt()
            throw failure
        }

    @Test
    fun `should log WARN cancelled without a cause when the call fails on an interrupted thread`() {
        // What is tested: the primary signal - the interrupt flag at the moment the failure reaches the
        //   interceptor - with an exception that carries no interrupt evidence of its own.
        // Success criteria: the exception propagates unchanged; one WARN event, outcome cancelled, no
        //   status, no throwable; the events counter moved under cancelled; the flag is STILL set.
        // Why it matters: a caller that left says nothing about the peer and must not fill ERROR; the
        //   interrupt belongs to the caller, so the interceptor must leave it where it found it.
        // Given
        val boom = IOException("Socket closed")

        // When
        val thrown = catchThrowable { interceptor.intercept(request(), ByteArray(0), interruptedThrowing(boom)) }
        val stillInterrupted = Thread.currentThread().isInterrupted

        // Then
        assertThat(thrown).isSameAs(boom)
        assertThat(stillInterrupted).describedAs("interrupt flag after the emission").isTrue()
        val event = log.events.single()
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(event.formattedMessage).contains("-> - [")
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "cancelled").doesNotContainKey("adapter_response_status_code")
        assertThat(event.throwableProxy).isNull()
        assertThat(registry.count(ClientLoggingMetrics.EVENTS_METER, "outcome", "cancelled")).isEqualTo(1.0)
        assertThat(registry.count(ClientLoggingMetrics.EVENTS_METER, "outcome", "failure")).isZero()
    }

    @Test
    fun `should let the cancellation win over a timeout reported on an interrupted thread`() {
        // What is tested: the order against the timeout branch - a SocketTimeoutException and Apache's
        //   pool lease timeout, both InterruptedIOExceptions, thrown while the flag is set.
        // Success criteria: both log WARN cancelled, not timeout.
        // Why it matters: the caller left; what the transport reported in the same moment is secondary
        //   (ADR-0015), and an alert on timeout must not count departures as slow peers.
        // Given
        val timeouts = listOf(SocketTimeoutException("Read timed out"), ConnectionRequestTimeoutException("lease"))

        // When
        timeouts.forEach { timeout ->
            catchThrowable { interceptor.intercept(request(), ByteArray(0), interruptedThrowing(timeout)) }
            Thread.interrupted()
        }

        // Then
        assertThat(log.events.map { keyValues(it)["adapter_outcome"] }).containsExactly("cancelled", "cancelled")
        assertThat(log.events.map { it.level }).containsOnly(Level.WARN)
    }

    @Test
    fun `should log cancelled from the cause chain when the flag was already consumed`() {
        // What is tested: the fallback - no interrupt flag, but an interrupt in the failure's cause
        //   chain (the JDK factory's wrapper, the JDK's socket closed by interrupt, Apache's aborted lease).
        // Success criteria: each logs WARN cancelled; the flag stays CLEAR - the interceptor never sets it.
        // Why it matters: an inner interceptor or an engine may consume the flag before rethrowing.
        // Given
        val failures =
            listOf(
                IOException("Request was interrupted: null", InterruptedException()),
                SocketException("Closed by interrupt"),
                RequestFailedException("Request aborted", InterruptedException()),
            )

        // When
        failures.forEach { failure -> catchThrowable { interceptor.intercept(request(), ByteArray(0)) { _, _ -> throw failure } } }

        // Then
        assertThat(Thread.currentThread().isInterrupted).describedAs("interrupt flag after the emissions").isFalse()
        assertThat(log.events.map { keyValues(it)["adapter_outcome"] }).containsExactly("cancelled", "cancelled", "cancelled")
        assertThat(log.events.map { it.level }).containsOnly(Level.WARN)
        assertThat(log.events.map { it.throwableProxy }).containsOnlyNulls()
    }

    @Test
    fun `should keep timeouts, plain interrupted I-O and other failures as they are without an interrupt`() {
        // What is tested: the controls on a thread that is NOT interrupted.
        // Success criteria: read timeout and pool lease timeout stay WARN timeout; a plain
        //   InterruptedIOException, a refusal and a reset stay ERROR failure with the cause.
        // Why it matters: the cancellation must not swallow the dispositions an operator acts on.
        // Given
        val failures =
            listOf(
                SocketTimeoutException("Read timed out"),
                ConnectionRequestTimeoutException("lease"),
                InterruptedIOException("interrupted"),
                ConnectException("Connection refused"),
                SocketException("Connection reset"),
            )

        // When
        failures.forEach { failure -> catchThrowable { interceptor.intercept(request(), ByteArray(0)) { _, _ -> throw failure } } }

        // Then
        assertThat(log.events.map { keyValues(it)["adapter_outcome"] }).containsExactly("timeout", "timeout", "failure", "failure", "failure")
        assertThat(log.events.map { it.level }).containsExactly(Level.WARN, Level.WARN, Level.ERROR, Level.ERROR, Level.ERROR)
        assertThat(log.events.map { it.throwableProxy }).doesNotContainNull()
    }

    @Test
    fun `should log cancelled with the received status when the body read fails on an interrupted thread`() {
        // What is tested: the response path - the status line arrived, the caller's thread was then
        //   interrupted during the body read, and the response is closed on ANOTHER thread that is not.
        // Success criteria: WARN cancelled WITH status 200 - the flag is read where the failure surfaced,
        //   not where the close happens.
        // Why it matters: the reactive twin logs a body cancelled mid-stream as cancelled with the
        //   received status; the blocking twin must say the same, wherever the host closes.
        // Given
        val interruptedRead =
            object : InputStream() {
                override fun read(): Int {
                    Thread.currentThread().interrupt()
                    throw SocketException("Socket closed")
                }
            }
        val response = interceptor.intercept(request(), ByteArray(0)) { _, _ -> MockClientHttpResponse(interruptedRead, HttpStatus.OK) }

        // When
        catchThrowable { response.body.read() }
        Thread.interrupted()
        Thread.ofPlatform().start { response.close() }.join()

        // Then
        val event = log.events.single()
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "cancelled").containsEntry("adapter_response_status_code", 200)
    }

    @Test
    fun `should classify an answered call by its status even when the thread is interrupted at close`() {
        // What is tested: an interrupt WITHOUT a failure - the exchange completed, the caller's thread
        //   was interrupted afterwards, before the close.
        // Success criteria: INFO success; the flag still set after the close.
        // Why it matters: the answer arrived before anybody stopped waiting for it; calling it
        //   cancelled would misreport a healthy call, and the interrupt is still the caller's.
        // Given
        val response = interceptor.intercept(request(), ByteArray(0), answering(body = "ok"))
        response.body.readAllBytes()

        // When
        Thread.currentThread().interrupt()
        response.close()
        val stillInterrupted = Thread.currentThread().isInterrupted

        // Then
        assertThat(stillInterrupted).isTrue()
        val event = log.events.single()
        assertThat(event.level).isEqualTo(Level.INFO)
        assertThat(keyValues(event)).containsEntry("adapter_outcome", "success")
    }

    @Test
    fun `should log the bodies of a cancelled exchange in on-failure mode`() {
        // What is tested: the body gate - cancelled is not success, so on-failure writes both bodies,
        //   as the reactive twin's gate does.
        // Success criteria: the cancelled event carries the request body and the part of the response
        //   body that was read before the interrupt.
        // Why it matters: the gate is one rule on both twins (ADR-0006); a cancelled call is a call
        //   whose bodies explain what the caller gave up on.
        // Given
        val onFailure =
            ClientLoggingProperties(
                loggerName = properties.loggerName,
                logRequestBody = BodyLogMode.ON_FAILURE,
                logResponseBody = BodyLogMode.ON_FAILURE,
            )
        val gated = interceptorWith(onFailure, AtomicLong())
        val partial =
            object : InputStream() {
                private var sent = false

                override fun read(): Int {
                    if (!sent) {
                        sent = true
                        return 'p'.code
                    }
                    Thread.currentThread().interrupt()
                    throw SocketException("Closed by interrupt")
                }
            }
        val response = gated.intercept(request(), "hello".toByteArray(StandardCharsets.UTF_8)) { _, _ -> MockClientHttpResponse(partial, HttpStatus.OK) }

        // When
        catchThrowable { response.body.readAllBytes() }
        response.close()

        // Then
        val event = log.events.single()
        assertThat(keyValues(event))
            .containsEntry("adapter_outcome", "cancelled")
            .containsEntry("adapter_request_body", "hello")
            .containsEntry("adapter_response_body", "p")
    }
}
