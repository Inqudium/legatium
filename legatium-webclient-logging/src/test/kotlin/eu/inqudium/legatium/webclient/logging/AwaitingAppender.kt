package eu.inqudium.legatium.webclient.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Captures events from connector threads and lets integration tests await their arrival event-driven: one
 * permit per event, a bounded [tryAcquire][Semaphore.tryAcquire] instead of any sleep. On timeout the
 * events captured so far are returned, so the caller's size assertion fails WITH the actual content.
 */
internal class AwaitingAppender : AppenderBase<ILoggingEvent>() {
    val events = CopyOnWriteArrayList<ILoggingEvent>()
    private val arrivals = Semaphore(0)

    override fun append(event: ILoggingEvent) {
        // Logback captures the MDC LAZILY on the first getMDCPropertyMap() access, from the CALLING
        // thread - handing the event to the awaiting test thread without this call races that first
        // access, and a test-thread win captures the test thread's (empty) MDC instead of the emitting
        // thread's. prepareForDeferredProcessing() is logback's own answer for cross-thread event
        // inspection (AsyncAppender does the same) and pins the snapshot to the emitting thread here.
        event.prepareForDeferredProcessing()
        events.add(event)
        arrivals.release()
    }

    fun awaitEvents(count: Int): List<ILoggingEvent> {
        arrivals.tryAcquire(count, 5, TimeUnit.SECONDS)
        return events.toList()
    }
}
