package eu.inqudium.legatium.restclient.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpResponse
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import java.net.URI
import java.nio.charset.StandardCharsets

/** The key-value pairs of an event as a map, for assertions on the `adapter_*` family. */
internal fun keyValues(event: ILoggingEvent): Map<String, Any?> = event.keyValuePairs?.associate { it.key to it.value } ?: emptyMap()

/** A mock outgoing request the interceptor can mutate (headers, attributes) like the real intercepting request. */
internal fun request(
    method: HttpMethod = HttpMethod.GET,
    uri: String = "https://api.example.com/things",
): MockClientHttpRequest = MockClientHttpRequest(method, URI.create(uri))

/** An execution answering [status] with [body]; the response is a fresh mock per call. */
internal fun answering(
    status: HttpStatus = HttpStatus.OK,
    body: String = "",
    onExecute: (MockClientHttpResponse) -> Unit = {},
): ClientHttpRequestExecution =
    ClientHttpRequestExecution { _, _ ->
        MockClientHttpResponse(body.toByteArray(StandardCharsets.UTF_8), status).also(onExecute)
    }

/** Reads the whole body, as a message converter would, then closes the response - the client's normal path. */
internal fun ClientHttpResponse.consumeAndClose(): String =
    use { response ->
        response.body.readAllBytes().toString(StandardCharsets.UTF_8)
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
