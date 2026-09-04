package eu.inqudium.legatium.webclient.logging

import eu.inqudium.legatium.common.BodyLogMode
import eu.inqudium.legatium.common.ClientLoggingMetrics
import eu.inqudium.legatium.common.ClientLoggingProperties
import eu.inqudium.legatium.common.CorrelationIdGenerator
import eu.inqudium.legatium.common.HeaderLogProperties
import eu.inqudium.legatium.common.HeaderValueMasker
import eu.inqudium.legatium.common.NanoTimeSource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.reactive.MockClientHttpRequest
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.ExchangeStrategies
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * Header selection/masking and the body tees of [ClientRequestLoggingFilter]: what reaches the
 * `adapter_request_headers` / `adapter_response_headers` / `adapter_request_body` / `adapter_response_body`
 * fields, the request-side inserter wrap, and what the response tee observes about consumption.
 */
class ClientRequestLoggingFilterBodyAndHeaderTest {
    private val ticker = AtomicLong(0)
    private val base = ClientLoggingProperties(loggerName = "http-adapter-exchange-reactive-body-header-test")
    private lateinit var log: CapturedLogger

    @BeforeEach
    fun setUp() {
        log = CapturedLogger(base.loggerName)
    }

    @AfterEach
    fun tearDown() {
        log.detach()
    }

    private fun filterWith(properties: ClientLoggingProperties) = ClientRequestLoggingFilter(properties, NanoTimeSource { ticker.get() }, CorrelationIdGenerator { "generated-42" }, SimpleMeterRegistry())

    /** An exchange function that WRITES the request body to a mock connector request, then answers. */
    private fun writingThenAnswering(
        response: ClientResponse = ClientResponse.create(HttpStatus.OK).build(),
        onWritten: (MockClientHttpRequest) -> Unit = {},
    ): ExchangeFunction =
        ExchangeFunction { request ->
            val connectorRequest = MockClientHttpRequest(request.method(), request.url())
            request
                .writeTo(connectorRequest, ExchangeStrategies.withDefaults())
                .then(Mono.fromCallable { onWritten(connectorRequest) })
                .then(Mono.just(response))
        }

    private fun ClientRequestLoggingFilter.call(
        request: ClientRequest,
        next: ExchangeFunction,
    ): String? = filter(request, next).flatMap { it.bodyToMono(String::class.java) }.block()

    @Nested
    inner class `Header selection and masking` {
        @Test
        fun `should log selected request headers multi-value, mask the configured ones stably and include the sent correlation header`() {
            // What is tested: HeaderLogProperties.select over the OUTGOING request's HttpHeaders -
            //   explicit includes, a two-valued header joined, a masked name rendered by the
            //   default fingerprint, and the correlation header the filter itself added.
            // Success criteria: the field joins both Accept values with ", ", renders Authorization
            //   as the DEFAULT masker's output without the plaintext, and shows X-Correlation-Id as
            //   "generated-42".
            // Why it matters: the field must describe the request as it went over the wire,
            //   including the header this module put there, and a masked value must be the stable
            //   fingerprint a reader can correlate.
            // Given
            val filter =
                filterWith(
                    base.copy(
                        requestHeaders =
                            HeaderLogProperties(includes = listOf("Accept", "Authorization", "X-Correlation-Id"), masked = listOf("authorization")),
                    ),
                )
            val request =
                request {
                    header("Accept", "application/json", "text/plain")
                    header("Authorization", "Bearer secret-token")
                }

            // When
            filter.call(request, answering())

            // Then
            val rendered = keyValues(log.events.single())["adapter_request_headers"].toString()
            assertThat(rendered).contains("Accept:\"application/json, text/plain\"")
            assertThat(rendered).contains("Authorization:\"${HeaderValueMasker.DEFAULT.mask("Bearer secret-token")}\"")
            assertThat(rendered).doesNotContain("secret-token")
            assertThat(rendered).contains("X-Correlation-Id:\"generated-42\"")
        }

        @Test
        fun `should render masked values through a host-provided masker`() {
            // What is tested: the masker is an injected collaborator - the filter built with a host
            //   bean masks request AND response headers with it.
            // Success criteria: both selected, masked headers carry the host masker's output, never the
            //   plaintext and never the built-in fingerprint.
            // Why it matters: a compliance regime forbidding unkeyed hashes must be satisfiable without
            //   forking the module.
            // Given
            val keyed = HeaderValueMasker { "hmac:${it.length}" }
            val keyedSubject =
                ClientRequestLoggingFilter(
                    base.copy(
                        requestHeaders = HeaderLogProperties(includes = listOf("Authorization"), masked = listOf("Authorization")),
                        responseHeaders = HeaderLogProperties(includes = listOf("Set-Cookie"), masked = listOf("Set-Cookie")),
                    ),
                    NanoTimeSource { ticker.get() },
                    CorrelationIdGenerator { "generated-42" },
                    SimpleMeterRegistry(),
                    keyed,
                )

            // When
            keyedSubject.call(request { header("Authorization", "Bearer secret-token") }, answering(headers = mapOf("Set-Cookie" to "session=1")))

            // Then
            val fields = keyValues(log.events.single())
            assertThat(fields["adapter_request_headers"].toString()).isEqualTo("[Authorization:\"hmac:19\"]")
            assertThat(fields["adapter_response_headers"].toString()).isEqualTo("[Set-Cookie:\"hmac:9\"]")
        }

        @Test
        fun `should key the built-in fingerprint from the properties when constructed without a masker`() {
            // What is tested: the masker default of the public four-argument constructor - the manual
            //   wiring path the guides recommend - derives from properties.maskingKey through
            //   HeaderValueMasker.forKey, exactly as the auto-configuration's default bean does.
            // Success criteria: with masking-key set and no masker passed, the masked Authorization value
            //   is the keyed HMAC fingerprint and NOT the unkeyed default fingerprint of the same value.
            // Why it matters: a host that configured a secret and wired the filter by hand silently
            //   logged unkeyed, guessable fingerprints - the configured guess-resistance depended on how
            //   the filter was constructed instead of on the property.
            // Given: keyed properties, the four-argument constructor
            val manuallyWired =
                filterWith(
                    base.copy(
                        maskingKey = "s3cret",
                        requestHeaders = HeaderLogProperties(includes = listOf("Authorization"), masked = listOf("Authorization")),
                    ),
                )

            // When
            manuallyWired.call(request { header("Authorization", "Bearer secret-token") }, answering())

            // Then: keyed, not the unkeyed default
            val rendered = keyValues(log.events.single())["adapter_request_headers"].toString()
            assertThat(rendered).isEqualTo("[Authorization:\"${HeaderValueMasker.keyed("s3cret").mask("Bearer secret-token")}\"]")
            assertThat(rendered).doesNotContain(HeaderValueMasker.DEFAULT.mask("Bearer secret-token"))
        }

        @Test
        fun `should log the selected response headers as the peer sent them`() {
            // What is tested: the response-side selection at emission - a wildcard include, an
            //   exclude that wins over it, and an unmasked name rendered in plaintext.
            // Success criteria: Content-Type appears with its literal value, Set-Cookie appears
            //   nowhere in the field.
            // Why it matters: a wildcard include is the debugging configuration; the exclude and
            //   the plaintext allowlist are what keep a session cookie out of the line while the
            //   content type stays readable.
            // Given: a wildcard include with one exclusion and one name allowed in plaintext
            val filter =
                filterWith(
                    base.copy(
                        responseHeaders = HeaderLogProperties(includes = listOf("*"), excludes = listOf("Set-Cookie"), unmasked = listOf("Content-Type")),
                    ),
                )

            // When
            filter.call(request(), answering(body = "ok", headers = mapOf("Content-Type" to "text/plain", "Set-Cookie" to "session=1")))

            // Then
            val rendered = keyValues(log.events.single())["adapter_response_headers"].toString()
            assertThat(rendered).contains("Content-Type:\"text/plain\"")
            assertThat(rendered).doesNotContain("Set-Cookie")
        }
    }

    @Nested
    inner class `Mask by default` {
        @Test
        fun `should mask every selected header by default so a wildcard include never leaks plaintext`() {
            // What is tested: ADR-0005 at the filter - `includes: ["*"]` with nothing said about masking.
            // Success criteria: every logged request header is a fingerprint; the secret appears nowhere.
            // Why it matters: with masking as a second, empty list the same configuration logged
            //   everything in plaintext - the unsafe combination was the convenient one.
            // Given
            val filter = filterWith(base.copy(requestHeaders = HeaderLogProperties(includes = listOf("*"))))

            // When
            filter.call(request { header("Authorization", "Bearer secret-token") }, answering())

            // Then
            val rendered = keyValues(log.events.single())["adapter_request_headers"].toString()
            assertThat(rendered).contains("Authorization:\"${HeaderValueMasker.DEFAULT.mask("Bearer secret-token")}\"")
            assertThat(rendered).doesNotContain("secret-token")
        }
    }

    @Nested
    inner class `Request body tee` {
        @Test
        fun `should log the request body as it is written to the connector and deliver it unchanged`() {
            // What is tested: the inserter wrap - the body is observed at the connector's writeWith,
            //   and the connector receives the identical bytes.
            // Success criteria: the mock connector request holds the body; the event logs it.
            // Why it matters: the tee must be a passive copy at the one place every encoder passes.
            // Given
            val filter = filterWith(base.copy(logRequestBody = BodyLogMode.ALWAYS))
            var written: String? = null
            val request =
                request(method = HttpMethod.POST) {
                    header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    body(BodyInserters.fromValue("""{"name":"thing"}"""))
                }

            // When
            filter.call(request, writingThenAnswering { written = it.bodyAsString.block() })

            // Then
            assertThat(written).isEqualTo("""{"name":"thing"}""")
            assertThat(keyValues(log.events.single())).containsEntry("adapter_request_body", """{"name":"thing"}""")
        }

        @Test
        fun `should truncate the logged request body at the capture limit and say so`() {
            // What is tested: a request-side capture with maxBodyBytes below the body length, fed
            //   through the inserter wrap's tee.
            // Success criteria: the field holds the first 4 bytes plus the truncation note with the
            //   exact 10-byte total.
            // Why it matters: the cap bounds log volume and heap per call; the note tells the
            //   reader that the body shown is a prefix and how much really went out.
            // Given
            val filter = filterWith(base.copy(logRequestBody = BodyLogMode.ALWAYS, maxBodyBytes = 4))
            val request = request(method = HttpMethod.POST) { body(BodyInserters.fromValue("0123456789")) }

            // When
            filter.call(request, writingThenAnswering())

            // Then
            assertThat(keyValues(log.events.single())).containsEntry("adapter_request_body", "0123... [truncated, 10 bytes total]")
        }

        @Test
        fun `should omit the request body key for a bodiless request`() {
            // What is tested: a GET without an inserter body under logRequestBody=ALWAYS - the tee
            //   sees setComplete only, the capture stays at zero bytes and loggedValue returns
            //   null.
            // Success criteria: the event carries no adapter_request_body key at all.
            // Why it matters: an empty-string field on every bodiless call would be noise and would
            //   make "no body" indistinguishable from "an empty body".
            // Given/When
            filterWith(base.copy(logRequestBody = BodyLogMode.ALWAYS)).call(request(), writingThenAnswering())

            // Then
            assertThat(keyValues(log.events.single())).doesNotContainKey("adapter_request_body")
        }
    }

    @Nested
    inner class `Response body tee` {
        @Test
        fun `should log the response body the application consumed and deliver identical content`() {
            // What is tested: the ObservedBody tee in the default consumption path -
            //   bodyToMono(String) reads the body through the mutated response.
            // Success criteria: the caller receives "hello" unchanged and the event logs the same
            //   text.
            // Why it matters: the tee is a passive copy; a body that arrived altered or a log that
            //   showed something else than what the application read would both be bugs of the same
            //   mechanism.
            // Given
            val filter = filterWith(base.copy(logResponseBody = BodyLogMode.ALWAYS))

            // When
            val body = filter.call(request(), answering(body = "hello"))

            // Then
            assertThat(body).isEqualTo("hello")
            assertThat(keyValues(log.events.single())).containsEntry("adapter_response_body", "hello")
        }

        @Test
        fun `should forward the original buffer untouched and copy only the bounded prefix`() {
            // What is tested: the tee's memory contract - counting never clones the buffer; at most the
            //   capture's remaining capacity is copied via a non-advancing read, and the ORIGINAL buffer
            //   flows downstream with its read position untouched.
            // Success criteria: downstream receives the identical buffer instance, fully readable; the
            //   capture holds exactly the 8-byte prefix and counted all 16 bytes.
            // Why it matters: an operator sizing heap by the cap must be able to rely on the bound.
            // Given
            val capture = BoundedBodyCapture(8)
            val buffer = DefaultDataBufferFactory.sharedInstance.wrap("0123456789abcdef".toByteArray())

            // When
            val passed = tee(capture, buffer)

            // Then
            assertThat(passed).isSameAs(buffer)
            assertThat(passed.readableByteCount()).isEqualTo(16)
            assertThat(capture.totalBytes).isEqualTo(16L)
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("01234567... [truncated, 16 bytes total]")
        }

        @Test
        fun `should copy nothing at all in count-only mode while still counting every byte`() {
            // What is tested: tee() against a capture with limit 0 - remainingCapacity is 0, so the
            //   copy branch is skipped and the whole buffer goes to count().
            // Success criteria: totalBytes is 7 and the logged value is the bare truncation note
            //   with that total.
            // Why it matters: measure-only mode installs exactly this capture on every call; it
            //   must cost no allocation per buffer while keeping the size sample exact.
            // Given: limit 0 - the measure-only mode
            val capture = BoundedBodyCapture(0)
            val buffer = DefaultDataBufferFactory.sharedInstance.wrap("payload".toByteArray())

            // When
            tee(capture, buffer)

            // Then
            assertThat(capture.totalBytes).isEqualTo(7L)
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("... [truncated, 7 bytes total]")
        }

        @Test
        fun `should truncate the logged response body at the capture limit and keep the exact total`() {
            // What is tested: a response-side capture with maxBodyBytes below the body length, fed
            //   through the ObservedBody tee.
            // Success criteria: the field holds the first 4 bytes plus the truncation note with the
            //   exact 10-byte total.
            // Why it matters: the cap protects heap and log volume on the response side too, and
            //   the total must stay exact even though only a prefix was copied.
            // Given
            val filter = filterWith(base.copy(logResponseBody = BodyLogMode.ALWAYS, maxBodyBytes = 4))

            // When
            filter.call(request(), answering(body = "0123456789"))

            // Then
            assertThat(keyValues(log.events.single())).containsEntry("adapter_response_body", "0123... [truncated, 10 bytes total]")
        }

        @Test
        fun `should decode the response body with the charset the peer declared`() {
            // What is tested: declaredCharsetOrUtf8 on the RESPONSE headers at emission - the
            //   Content-Type charset parameter selects the decoder for the captured bytes.
            // Success criteria: the ISO-8859-1 bytes of "café" log as "café", not as a mojibake
            //   sequence.
            // Why it matters: a peer that does not speak UTF-8 must still yield a readable body
            //   field; the decoder must follow the header, not a default.
            // Given
            val filter = filterWith(base.copy(logResponseBody = BodyLogMode.ALWAYS))
            val latin =
                ClientResponse
                    .create(HttpStatus.OK)
                    .header("Content-Type", "text/plain; charset=ISO-8859-1")
                    .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap("café".toByteArray(StandardCharsets.ISO_8859_1))))
                    .build()

            // When
            filter.filter(request(), ExchangeFunction { Mono.just(latin) }).flatMap { it.releaseBody() }.block()

            // Then
            assertThat(keyValues(log.events.single())).containsEntry("adapter_response_body", "café")
        }

        @Test
        fun `should omit the response body key when no bytes flowed`() {
            // What is tested: an empty response body under logResponseBody=ALWAYS - the tee sees no
            //   buffer, loggedValue returns null and the *IfPresent helper drops the field.
            // Success criteria: the event carries no adapter_response_body key.
            // Why it matters: an empty field on every bodiless answer would be noise and would hide
            //   the difference between "nothing sent" and "empty body sent".
            // Given/When: a bodiless answer, released
            filterWith(base.copy(logResponseBody = BodyLogMode.ALWAYS)).call(request(), answering())

            // Then
            assertThat(keyValues(log.events.single())).doesNotContainKey("adapter_response_body")
        }

        @Test
        fun `should record the read state as partial for a cancelled body and complete for a consumed one`() {
            // What is tested: the observation points of the read state on the reactive tee - the
            //   subscription marks PARTIAL, the completion signal marks COMPLETE, a cancellation leaves
            //   PARTIAL.
            // Success criteria: read off the captures of two exchanges.
            // Why it matters: the state is the one signal that tells a discarded body from an absent one.
            // Given: a measuring filter on its own registry
            val registry = SimpleMeterRegistry()
            val observing = ClientRequestLoggingFilter(base.copy(measureResponseBodySize = true), { ticker.get() }, { "g" }, registry)

            // When: one body consumed, one body cancelled after the first chunk
            observing.call(request(), answering(body = "all"))
            val endless = ClientResponse.create(HttpStatus.OK).body(Flux.concat(Mono.just(buffer("x")), Flux.never())).build()
            observing
                .filter(request(), ExchangeFunction { Mono.just(endless) })
                .flatMapMany { it.bodyToFlux(DataBuffer::class.java).take(1) }
                .blockLast()

            // Then: the counters carry one complete and one partial
            assertThat(
                registry
                    .get(ClientLoggingMetrics.RESPONSE_BODY_READ_METER)
                    .tag("state", "complete")
                    .counter()
                    .count(),
            ).isEqualTo(1.0)
            assertThat(
                registry
                    .get(ClientLoggingMetrics.RESPONSE_BODY_READ_METER)
                    .tag("state", "partial")
                    .counter()
                    .count(),
            ).isEqualTo(1.0)
        }
    }

    @Nested
    inner class `Outcome-gated bodies` {
        private val onFailure = base.copy(logRequestBody = BodyLogMode.ON_FAILURE, logResponseBody = BodyLogMode.ON_FAILURE)

        private fun posting(text: String) = request(method = HttpMethod.POST) { body(BodyInserters.fromValue(text)) }

        private fun answer(
            status: HttpStatus,
            body: String,
        ): ClientResponse = ClientResponse.create(status).body(body).build()

        @Test
        fun `should withhold both bodies from a successful exchange in on-failure mode`() {
            // What is tested: the volume switch - on-failure tees the request body (the outcome is unknown
            //   while it is written) and discards both captures at emission when the outcome is success.
            // Success criteria: the application receives the response body; the line carries neither body.
            // Why it matters: this is the mode that keeps body logging affordable outside a debug session.
            // Given/When
            val body = filterWith(onFailure).call(posting("sent"), writingThenAnswering(answer(HttpStatus.OK, "received")))

            // Then
            assertThat(body).isEqualTo("received")
            assertThat(keyValues(log.events.single()))
                .containsEntry("adapter_outcome", "success")
                .doesNotContainKeys("adapter_request_body", "adapter_response_body")
        }

        @Test
        fun `should log both bodies of a 5xx answer in on-failure mode`() {
            // What is tested: the on-failure gate for a 5xx - classified as outcome failure without
            //   an error signal, so both captures are written.
            // Success criteria: outcome failure, the request body "sent" and the response body
            //   "upstream down" on the line.
            // Why it matters: a 5xx is the case an operator wants the bodies for, and the request
            //   body was teed before the outcome was known - the on-failure capture must not have
            //   thrown it away.
            // Given/When: a failure outcome without an error signal
            filterWith(onFailure).call(posting("sent"), writingThenAnswering(answer(HttpStatus.BAD_GATEWAY, "upstream down")))

            // Then
            assertThat(keyValues(log.events.single()))
                .containsEntry("adapter_outcome", "failure")
                .containsEntry("adapter_request_body", "sent")
                .containsEntry("adapter_response_body", "upstream down")
        }

        @Test
        fun `should log the teed request body of a call that failed after sending it`() {
            // What is tested: on-failure with an error signal AFTER the request body was written -
            //   no response exists, the exchange completes through doFinally on the response Mono.
            // Success criteria: the caller gets the IOException, the line carries outcome failure
            //   and the request body, and no response body key.
            // Why it matters: for a connection that dropped after the upload the request body is
            //   the only payload evidence there is; it must survive to the failure line.
            // Given: a connector that takes the body and then fails without a response
            val refused =
                ExchangeFunction { request ->
                    request
                        .writeTo(MockClientHttpRequest(request.method(), request.url()), ExchangeStrategies.withDefaults())
                        .then(Mono.error<ClientResponse>(IOException("Connection refused")))
                }

            // When
            val thrown = catchThrowable { filterWith(onFailure).filter(posting("sent"), refused).block() }

            // Then: the request body that was captured before the outcome was known is on the line
            assertThat(Exceptions.unwrap(thrown)).isInstanceOf(IOException::class.java)
            assertThat(keyValues(log.events.single()))
                .containsEntry("adapter_outcome", "failure")
                .containsEntry("adapter_request_body", "sent")
                .doesNotContainKey("adapter_response_body")
        }

        @Test
        fun `should log both bodies of a 4xx answer although its outcome stays success`() {
            // What is tested: the gate is wider than the outcome vocabulary by one status class - a 4xx keeps
            //   its success outcome (the peer answered; the request was wrong) but is exactly the case a body explains.
            // Success criteria: outcome success, and BOTH bodies on the line.
            // Why it matters: a validation error\'s response body is the most wanted body of all; hiding it
            //   behind the outcome vocabulary would make on-failure useless for client errors.
            // Given/When: a 404 with a body
            filterWith(onFailure).call(posting("sent"), writingThenAnswering(answer(HttpStatus.NOT_FOUND, "no such thing")))

            // Then
            assertThat(keyValues(log.events.single()))
                .containsEntry("adapter_outcome", "success")
                .containsEntry("adapter_request_body", "sent")
                .containsEntry("adapter_response_body", "no such thing")
        }

        @Test
        fun `should still measure the size of a body it withholds`() {
            // What is tested: on-failure plus measureRequestBodySize on a successful call - the
            //   capture is installed for logging, the emitter records its size and then discards
            //   the text.
            // Success criteria: the request body size summary totals 4 bytes while the event has no
            //   adapter_request_body key.
            // Why it matters: metrics run before the level and outcome gates; a size sample must
            //   not depend on whether the body ends up on the line.
            // Given: on-failure plus measuring, on an own registry
            val registry = SimpleMeterRegistry()
            val measuring = ClientRequestLoggingFilter(onFailure.copy(measureRequestBodySize = true), { ticker.get() }, { "g" }, registry)

            // When: a successful call with a 4-byte request body
            measuring.call(posting("four"), writingThenAnswering())

            // Then: the sample is recorded, the field is not
            assertThat(registry.get(ClientLoggingMetrics.REQUEST_BODY_SIZE_METER).summary().totalAmount()).isEqualTo(4.0)
            assertThat(keyValues(log.events.single())).doesNotContainKey("adapter_request_body")
        }
    }

    private fun buffer(text: String): DataBuffer = DefaultDataBufferFactory.sharedInstance.wrap(text.toByteArray())
}
