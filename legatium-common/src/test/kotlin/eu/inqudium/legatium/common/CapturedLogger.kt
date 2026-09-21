package eu.inqudium.legatium.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * An [appender] attached to [loggerName] for one test, detached - and the previous level restored - by
 * [detach]. The level the logger had before is null when it inherited one; restoring it means a test
 * that raises or silences a logger (Level.OFF in the metrics tests) leaves the JVM-global logger tree as
 * it found it and the suite stays order-independent. Detach in `finally` or `@AfterEach`: the appender
 * and the raised level outlive the test otherwise. Shared by the common module and both twins
 * (ADR-0003, the test-jar); the reactive twin adds an awaiting variant on top for events that arrive
 * from connector threads.
 */
internal abstract class AttachedLogger<A : Appender<ILoggingEvent>>(
    loggerName: String,
    val appender: A,
    /** The level the logger is raised to while captured - INFO for the exchange lines, DEBUG for the wiring report. */
    level: Level = Level.INFO,
) {
    val logger: Logger = LoggerFactory.getLogger(loggerName) as Logger
    private val previousLevel: Level? = logger.level

    init {
        logger.addAppender(appender)
        logger.level = level
    }

    fun detach() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = previousLevel
    }
}

/** A list appender on [loggerName] for the synchronous tests: the events are read once the call returned. */
internal class CapturedLogger(
    loggerName: String,
    level: Level = Level.INFO,
) : AttachedLogger<ListAppender<ILoggingEvent>>(loggerName, ListAppender<ILoggingEvent>().apply { start() }, level) {
    val events: List<ILoggingEvent>
        get() = appender.list.toList()
}

/** The key-value pairs of an event as a map, for assertions on the `adapter_*` family. */
internal fun keyValues(event: ILoggingEvent): Map<String, Any?> = event.keyValuePairs?.associate { it.key to it.value } ?: emptyMap()
