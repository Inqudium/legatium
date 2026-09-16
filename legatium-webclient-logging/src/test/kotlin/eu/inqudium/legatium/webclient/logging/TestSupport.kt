package eu.inqudium.legatium.webclient.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.context.ContextRegistry
import io.micrometer.context.ThreadLocalAccessor
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Mono
import java.net.URI

/** The key-value pairs of an event as a map, for assertions on the `adapter_*` family. */
internal fun keyValues(event: ILoggingEvent): Map<String, Any?> = event.keyValuePairs?.associate { it.key to it.value } ?: emptyMap()

/** An immutable outgoing request, as WebClient builds it. */
internal fun request(
    method: HttpMethod = HttpMethod.GET,
    uri: String = "https://api.example.com/things",
    customize: ClientRequest.Builder.() -> Unit = {},
): ClientRequest = ClientRequest.create(method, URI.create(uri)).apply(customize).build()

/** An exchange function answering [status] with [body] and the given headers. */
internal fun answering(
    status: HttpStatus = HttpStatus.OK,
    body: String = "",
    headers: Map<String, String> = emptyMap(),
): ExchangeFunction =
    ExchangeFunction {
        Mono.just(
            ClientResponse
                .create(status)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .body(body)
                .build(),
        )
    }

/** A list appender attached to [loggerName] at INFO, detached - and the previous level restored - by [detach]. */
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

/**
 * Registers an MDC-backed `ThreadLocalAccessor` for [key] with the JVM-global [ContextRegistry] the way
 * a host (or limesium, for its `endpoint_*` keys) does, and removes it again on [close] - but only when
 * this guard added it: an accessor a host registered before is never deleted. Without the guard an
 * accessor from one test would leak into every later test in the JVM.
 */
internal class MdcAccessorGuard(
    private val key: String,
    private val registry: ContextRegistry = ContextRegistry.getInstance(),
) : AutoCloseable {
    private val added: Boolean =
        if (registry.threadLocalAccessors.none { it.key() == key }) {
            registry.registerThreadLocalAccessor(
                object : ThreadLocalAccessor<String> {
                    override fun key(): Any = key

                    override fun getValue(): String? = MDC.get(key)

                    override fun setValue(value: String) = MDC.put(key, value)

                    override fun setValue() = MDC.remove(key)
                },
            )
            true
        } else {
            false
        }

    override fun close() {
        if (added) {
            registry.removeThreadLocalAccessor(key)
        }
    }
}
