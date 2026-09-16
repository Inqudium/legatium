package eu.inqudium.legatium.restclient.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.read.ListAppender
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.NanoTimeSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpResponse
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** The key-value pairs of an event as a map, for assertions on the `adapter_*` family. */
internal fun keyValues(event: ILoggingEvent): Map<String, Any?> = event.keyValuePairs?.associate { it.key to it.value } ?: emptyMap()

/**
 * An interceptor with its two functional collaborators pinned - [ticker] as the clock,
 * [correlationId] as the id every traceless call sends - against [registry]: a fresh one unless the
 * test asserts on a shared registry.
 */
internal fun interceptorWith(
    properties: ClientLoggingProperties,
    ticker: AtomicLong,
    registry: MeterRegistry = SimpleMeterRegistry(),
    correlationId: String = "generated-42",
): ClientRequestLoggingInterceptor = ClientRequestLoggingInterceptor(properties, NanoTimeSource { ticker.get() }, CorrelationIdGenerator { correlationId }, registry)

/** Runs [block] on a fresh thread and returns its result - the case of a close that does not happen on the caller's thread. */
internal fun <T> onAnotherThread(block: () -> T): T {
    val executor = Executors.newSingleThreadExecutor()
    try {
        return executor.submit(block).get()
    } finally {
        executor.shutdownNow()
    }
}

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

/**
 * Reads the whole body to its EOF (`readAllBytes` - the path of the String converter and of every
 * converter without a known length), then closes the response - the client's normal path. NOT the path
 * of `ByteArrayHttpMessageConverter`, which reads exactly `Content-Length` bytes and never sees the EOF;
 * the metrics test drives that converter for real.
 */
internal fun ClientHttpResponse.consumeAndClose(): String =
    use { response ->
        response.body.readAllBytes().toString(StandardCharsets.UTF_8)
    }

/**
 * A list appender that pins each event's MDC to the EMITTING thread: logback captures the MDC lazily on
 * the first `getMDCPropertyMap()` access, and a test reading an event that another thread logged would
 * otherwise capture its own (empty) MDC. `prepareForDeferredProcessing()` is logback's own answer for
 * cross-thread inspection (AsyncAppender does the same).
 */
internal class PinnedMdcAppender : AppenderBase<ILoggingEvent>() {
    val events = CopyOnWriteArrayList<ILoggingEvent>()

    override fun append(event: ILoggingEvent) {
        event.prepareForDeferredProcessing()
        events.add(event)
    }
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
 * The fixture every Spring integration suite of this module shares: one [PeerServer] per suite class,
 * started before its first test and stopped after its last; the exchange logger captured per test - a
 * fresh instance per test method under JUnit's default lifecycle, detached afterwards - and the peer's
 * record cleared before each test. A suite adds its own `@SpringBootTest` configuration on top.
 */
abstract class PeerIntegrationSuite {
    /**
     * The `adapter-http-exchange` logger of this test, captured at INFO. Attached in `@BeforeEach`, not
     * in an initializer: the suite's Spring context starts AFTER the instance is built, and Boot's logging
     * initialisation on that start leaves the logger without an appender attached earlier - the first
     * test of every suite would see no events.
     */
    internal lateinit var log: CapturedLogger

    @BeforeEach
    fun attachLogAndClearPeerRecord() {
        log = CapturedLogger("adapter-http-exchange")
        peer.received.clear()
    }

    @AfterEach
    fun detachLog() {
        log.detach()
    }

    companion object {
        /** The peer of the running suite class; suites run sequentially, so one static slot serves them all. */
        internal lateinit var peer: PeerServer

        @JvmStatic
        @BeforeAll
        fun startPeer() {
            peer = PeerServer()
        }

        @JvmStatic
        @AfterAll
        fun stopPeer() {
            peer.close()
        }
    }
}
