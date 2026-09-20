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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestInstancePostProcessor
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpRequest
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpResponse
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The read timeout that IS the subject of the Spring suites' timeout scenarios: well below the peer's `/slow` delay. */
internal val SHORT: Duration = Duration.ofMillis(200)

/**
 * The tracing bridge - on the test classpath for the tracing suite - excluded, so the calls of a suite
 * are TRACELESS: an active bridge injects a traceparent into EVERY call, sampled or not, and the
 * correlation contract must be what goes on the wire.
 */
internal const val TRACELESS_CALLS =
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration," +
        "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration"

/** The count of the counter [name] carries under [tags] - the one registry read every meter assertion makes. */
internal fun MeterRegistry.count(
    name: String,
    vararg tags: String,
): Double = get(name).tags(*tags).counter().count()

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

/**
 * An execution that observes the calling thread at the moment of the wire call - its MDC, the log so
 * far - through [observe], then answers through [delegate]. What was seen is read once the call returned.
 */
internal class ObservingExecution<T : Any>(
    private val delegate: ClientHttpRequestExecution = answering(),
    private val observe: () -> T,
) : ClientHttpRequestExecution {
    /** What [observe] returned on the wire call; null before it. */
    var seen: T? = null
        private set

    /** Whether the wire call happened at all. */
    val called: Boolean
        get() = seen != null

    override fun execute(
        request: HttpRequest,
        body: ByteArray,
    ): ClientHttpResponse {
        seen = observe()
        return delegate.execute(request, body)
    }
}

/**
 * Runs [block] on a fresh thread and returns its result - the case of a close that does not happen on
 * the caller's thread. Bounded: a close that blocks fails the test instead of hanging the suite.
 */
internal fun <T> onAnotherThread(block: () -> T): T {
    val executor = Executors.newSingleThreadExecutor()
    try {
        return executor.submit(block).get(30, TimeUnit.SECONDS)
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
    /** The level the logger is raised to while captured - INFO for the exchange lines, DEBUG for the wiring report. */
    level: Level = Level.INFO,
) {
    val logger: Logger = LoggerFactory.getLogger(loggerName) as Logger
    val appender: ListAppender<ILoggingEvent> = ListAppender<ILoggingEvent>().apply { start() }

    // The level the logger had before - null when it inherited one - restored by `detach`, so a test
    // that raises or silences a logger (Level.OFF in the metrics tests) leaves the JVM-global logger
    // tree as it found it and the suite stays order-independent.
    private val previousLevel: Level? = logger.level

    init {
        logger.addAppender(appender)
        logger.level = level
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
 * started before its first test and stopped after its last ([PeerExtension]); the exchange logger
 * captured per test - a fresh instance per test method under JUnit's default lifecycle, detached
 * afterwards - and the peer's record cleared before each test. A suite adds its own `@SpringBootTest`
 * configuration on top.
 */
@ExtendWith(PeerExtension::class)
abstract class PeerIntegrationSuite {
    /**
     * The peer of this suite class, injected into every test instance by [PeerExtension] - an instance
     * field, so two suite classes running at once could not reassign each other's peer through a
     * static slot, under the default per-method lifecycle, so Boot's prototype `RestClient.Builder` is
     * still a fresh one per test.
     */
    internal lateinit var peer: PeerServer

    /**
     * The `adapter-http-exchange` logger of this test, captured at INFO. Attached in `@BeforeEach`, not
     * in an initializer: the suite's Spring context starts AFTER the instance is built, and Boot's logging
     * initialisation on that start leaves the logger without an appender attached earlier - the first
     * test of every suite would see no events.
     */
    internal lateinit var log: CapturedLogger

    private val closeables = mutableListOf<AutoCloseable>()

    /** Registers an engine resource to be released after the test. */
    protected fun <T : AutoCloseable> closing(resource: T): T = resource.also { closeables += it }

    @BeforeEach
    fun attachLogAndClearPeerRecord() {
        log = CapturedLogger("adapter-http-exchange")
        peer.received.clear()
    }

    @AfterEach
    fun detachLogAndReleaseEngineResources() {
        log.detach()
        closeables.reversed().forEach { runCatching { it.close() } }
        closeables.clear()
    }
}

/**
 * Starts one [PeerServer] per suite class and hands it to every test instance of the suite: created in
 * `beforeAll` and kept in the CLASS-level store, where JUnit closes it - the store closes its
 * `AutoCloseable` values - once the last test of the class ran; injected into
 * [PeerIntegrationSuite.peer] as each instance is built. Neither a static slot (shared by every suite
 * class, correct only while classes run one after another) nor a per-class test instance (which would
 * share Boot's prototype `RestClient.Builder` across the tests of a suite).
 */
internal class PeerExtension :
    BeforeAllCallback,
    TestInstancePostProcessor {
    override fun beforeAll(context: ExtensionContext) {
        context.getStore(NAMESPACE).computeIfAbsent(PeerServer::class.java, { PeerServer() }, PeerServer::class.java)
    }

    override fun postProcessTestInstance(
        testInstance: Any,
        context: ExtensionContext,
    ) {
        (testInstance as PeerIntegrationSuite).peer =
            checkNotNull(context.getStore(NAMESPACE).get(PeerServer::class.java, PeerServer::class.java)) {
                "no peer for ${context.requiredTestClass.name} - beforeAll did not run"
            }
    }

    private companion object {
        val NAMESPACE: ExtensionContext.Namespace = ExtensionContext.Namespace.create(PeerExtension::class.java)
    }
}
