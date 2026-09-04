package eu.inqudium.legatium.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Captures the events of one Logback logger for the duration of a test - a copy of the twins' helper
 * (ADR-0003: copies are cheaper than a test-jar). Detach in `finally`, the appender outlives the test
 * otherwise.
 */
internal class CapturedLogger(
    loggerName: String,
) {
    val logger: Logger = LoggerFactory.getLogger(loggerName) as Logger
    val appender: ListAppender<ILoggingEvent> = ListAppender<ILoggingEvent>().apply { start() }

    init {
        logger.addAppender(appender)
        logger.level = Level.INFO
    }

    val events: List<ILoggingEvent>
        get() = appender.list.toList()

    fun detach() {
        logger.detachAppender(appender)
        appender.stop()
    }
}
