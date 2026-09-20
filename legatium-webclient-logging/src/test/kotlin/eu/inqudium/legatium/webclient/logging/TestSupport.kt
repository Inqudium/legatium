package eu.inqudium.legatium.webclient.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.read.ListAppender
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.NanoTimeSource
import io.micrometer.context.ContextRegistry
import io.micrometer.context.ThreadLocalAccessor
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.client.reactive.MockClientHttpRequest
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.ExchangeStrategies
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The bound every cross-thread wait in the tests gets before it gives up - a latch, a worker join, an
 * awaited log event. Generous against a loaded runner; a wait that reaches it is a failed test, never a
 * slow one.
 */
internal val AWAIT: Duration = Duration.ofSeconds(5)

/** The count of the counter [name] carries under [tags] - the one registry read every meter assertion makes. */
internal fun MeterRegistry.count(
    name: String,
    vararg tags: String,
): Double = get(name).tags(*tags).counter().count()

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

/** An exchange function that records the request it is handed and answers through [delegate]. */
internal class RecordingExchange(
    private val delegate: ExchangeFunction = answering(),
) : ExchangeFunction {
    /** The request the connector received - the last one, for a client that calls more than once; null before the first call. */
    var sent: ClientRequest? = null
        private set

    override fun exchange(request: ClientRequest): Mono<ClientResponse> {
        sent = request
        return delegate.exchange(request)
    }
}

/** A filter on [properties] with a pinned clock ([ticker]) and a pinned id ([correlationId]); the registry is fresh unless a test shares one. */
internal fun filterWith(
    properties: ClientLoggingProperties,
    ticker: AtomicLong,
    registry: MeterRegistry = SimpleMeterRegistry(),
    correlationId: String = "generated-42",
): ClientRequestLoggingFilter = ClientRequestLoggingFilter(properties, NanoTimeSource { ticker.get() }, CorrelationIdGenerator { correlationId }, registry)

/**
 * A response whose body refuses a SECOND subscription the way Spring's `AbstractClientHttpResponse` -
 * the base of the JDK, Jetty and HttpComponents connector responses - does: the first subscriber gets
 * [body], every later one an `onSubscribe` followed by the `IllegalStateException` Spring's own release
 * path swallows. Reactor Netty, by contrast, completes a second subscriber empty; `Flux.just` bodies
 * replay - neither shape proves anything about a second subscription.
 */
internal fun singleSubscriber(
    body: String,
    status: HttpStatus = HttpStatus.OK,
): ClientResponse {
    val subscribed = AtomicBoolean(false)
    val once =
        Flux.defer {
            if (subscribed.compareAndSet(false, true)) {
                Flux.just(buffer(body))
            } else {
                Flux.error(IllegalStateException("The client response body can only be consumed once"))
            }
        }
    return ClientResponse.create(status).body(once).build()
}

/** A publisher that ignores cancellation - the Reactive-Streams-permitted late onNext, made deterministic. */
internal class ManualPublisher : Publisher<DataBuffer> {
    private lateinit var subscriber: Subscriber<in DataBuffer>

    override fun subscribe(s: Subscriber<in DataBuffer>) {
        subscriber = s
        s.onSubscribe(
            object : Subscription {
                override fun request(n: Long) = Unit

                override fun cancel() = Unit
            },
        )
    }

    fun emit(buffer: DataBuffer) = subscriber.onNext(buffer)
}

/** Runs the call the way `retrieve().bodyToMono(String)` does: exchange, then consume the body. */
internal fun ClientRequestLoggingFilter.call(
    request: ClientRequest,
    next: ExchangeFunction,
): String? = filter(request, next).flatMap { it.bodyToMono(String::class.java) }.block()

/**
 * An exchange function that WRITES the request body to a mock connector request - the one place every
 * encoder passes, so the request-side tee runs for real - and then continues with [andThen]: the answer,
 * or the failure of a connection that took the upload. The connector request is recorded.
 */
internal class WritingExchange(
    private val andThen: (ClientRequest) -> Mono<ClientResponse> = { Mono.just(ClientResponse.create(HttpStatus.OK).build()) },
) : ExchangeFunction {
    /** The connector request the body was written to - the last one; null before the first call. */
    var received: MockClientHttpRequest? = null
        private set

    override fun exchange(request: ClientRequest): Mono<ClientResponse> {
        val connectorRequest = MockClientHttpRequest(request.method(), request.url())
        received = connectorRequest
        return request.writeTo(connectorRequest, ExchangeStrategies.withDefaults()).then(Mono.defer { andThen(request) })
    }
}

/** A [WritingExchange] answering [response] once the body is written. */
internal fun writingThenAnswering(response: ClientResponse = ClientResponse.create(HttpStatus.OK).build()): ExchangeFunction = WritingExchange { Mono.just(response) }

/**
 * An exchange function that observes the calling thread at the moment of the exchange - the log so
 * far - through [observe], then answers through [delegate]. What was seen is read once the call returned.
 */
internal class ObservingExchange<T : Any>(
    private val delegate: ExchangeFunction = answering(),
    private val observe: () -> T,
) : ExchangeFunction {
    /** What [observe] returned on the exchange; null before it. */
    var seen: T? = null
        private set

    override fun exchange(request: ClientRequest): Mono<ClientResponse> {
        seen = observe()
        return delegate.exchange(request)
    }
}

/** A heap buffer holding [text] in UTF-8, as a connector would hand it to the tee. */
internal fun buffer(text: String): DataBuffer = DefaultDataBufferFactory.sharedInstance.wrap(text.toByteArray())

/**
 * An [appender] attached to [loggerName] at INFO for one test, detached - and the previous level
 * restored - by [detach]. The level the logger had before is null when it inherited one; restoring it
 * means a test that raises or silences a logger (Level.OFF in the metrics tests) leaves the JVM-global
 * logger tree as it found it and the suite stays order-independent.
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

/** An [AwaitingAppender] on [loggerName] for the tests whose events arrive from connector threads - by default the production logger. */
internal class AwaitingLogger(
    loggerName: String = "adapter-http-exchange",
) : AttachedLogger<AwaitingAppender>(loggerName, AwaitingAppender().apply { start() }) {
    /** See [AwaitingAppender.awaitEvents]. */
    fun awaitEvents(count: Int): List<ILoggingEvent> = appender.awaitEvents(count)
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
