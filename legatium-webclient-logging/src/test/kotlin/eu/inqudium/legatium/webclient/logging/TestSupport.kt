package eu.inqudium.legatium.webclient.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
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

/** A list appender attached to [loggerName] at INFO, detached by [detach]. */
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
