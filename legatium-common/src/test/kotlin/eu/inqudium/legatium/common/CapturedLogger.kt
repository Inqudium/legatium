package eu.inqudium.legatium.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Captures the events of one Logback logger for the duration of a test - a copy of the twins' helper
 * (ADR-0003: copies are cheaper than a test-jar). Detach in `finally`, the appender and the raised
 * level outlive the test otherwise.
 */
internal class CapturedLogger(
    loggerName: String,
) {
    val logger: Logger = LoggerFactory.getLogger(loggerName) as Logger
    val appender: ListAppender<ILoggingEvent> = ListAppender<ILoggingEvent>().apply { start() }

    // The level the logger had before - null when it inherited one - restored by [detach], so a test
    // that raises or silences a logger (Level.OFF in the metrics tests) leaves the JVM-global logger
    // tree as it found it and the suite stays order-independent.
    private val previousLevel: Level? = logger.level

    init {
        logger.addAppender(appender)
        logger.level = Level.INFO
    }

    val events: List<ILoggingEvent>
        get() = appender.list.toList()

    fun detach() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = previousLevel
    }
}
