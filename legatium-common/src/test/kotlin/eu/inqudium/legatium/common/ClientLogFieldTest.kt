package eu.inqudium.legatium.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import java.nio.charset.StandardCharsets

/**
 * Contract of the [ClientLogField] family: the wire names (a contract with the log index), the per-field
 * type guarantee, the drop-the-field-not-the-event semantics of the [addKeyValue] overload, and the
 * lockstep with the repository-shared component template (the one index contract both stacks share) -
 * tested once, in the module both twins inline (ADR-0003).
 */
class ClientLogFieldTest {
    // The ONE template for both stacks lives in the repository-shared /docs and reaches this module's
    // test classpath through the declared test resource in the POM. Enum and template are the two
    // halves of the index contract every twin ships; testing them once here IS the lockstep.
    private val template: String by lazy {
        val resource = ClassPathResource("elk/legatium-restclient-logging-fields.component-template.json")
        assertThat(resource.exists())
            .describedAs("the component template must be on the test classpath (declared test resource from the shared /docs)")
            .isTrue()
        resource.inputStream.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
    }

    private val properties: Map<String, Map<String, Any>> by lazy {
        JsonPath.read(template, "$.template.mappings.properties")
    }

    @Nested
    inner class `Wire names` {
        @Test
        fun `should be the literal strings the component template maps`() {
            // What is tested: every wire name, spelled out as a literal - independently of the enum, so a
            //   rename cannot pass by asserting a value against itself.
            // Success criteria: all thirteen names match exactly.
            // Why it matters: once the template is composed into a pipeline, changing a name is a breaking
            //   change for every dashboard and alert keying on it - the compiler cannot see that.
            // Given/When/Then: every wire name against its literal
            assertThat(ClientLogField.OUTCOME.wireName).isEqualTo("adapter_outcome")
            assertThat(ClientLogField.DURATION_MS.wireName).isEqualTo("adapter_duration_ms")
            assertThat(ClientLogField.REQUEST_METHOD.wireName).isEqualTo("adapter_request_method")
            assertThat(ClientLogField.RESPONSE_STATUS_CODE.wireName).isEqualTo("adapter_response_status_code")
            assertThat(ClientLogField.URL_HOST.wireName).isEqualTo("adapter_url_host")
            assertThat(ClientLogField.URL_TEMPLATE.wireName).isEqualTo("adapter_url_template")
            assertThat(ClientLogField.URL_PATH.wireName).isEqualTo("adapter_url_path")
            assertThat(ClientLogField.URL_QUERY.wireName).isEqualTo("adapter_url_query")
            assertThat(ClientLogField.SLOW.wireName).isEqualTo("adapter_slow")
            assertThat(ClientLogField.REQUEST_HEADERS.wireName).isEqualTo("adapter_request_headers")
            assertThat(ClientLogField.RESPONSE_HEADERS.wireName).isEqualTo("adapter_response_headers")
            assertThat(ClientLogField.REQUEST_BODY.wireName).isEqualTo("adapter_request_body")
            assertThat(ClientLogField.RESPONSE_BODY.wireName).isEqualTo("adapter_response_body")
        }

        @Test
        fun `should prefix every wire name with adapter and keep them unique`() {
            // What is tested: the naming contract of the whole family in one place.
            // Success criteria: every wire name starts with 'adapter_', is lower snake_case, and no two
            //   fields collide.
            // Why it matters: the names are index-side contract - a stray prefix or a duplicate silently
            //   splits one logical field into two that no dashboard knows about.
            // Given/When: the wire names of every field of the family
            val wireNames = ClientLogField.entries.map { it.wireName }

            // Then: prefixed, snake_case, unique
            assertThat(wireNames).allSatisfy { name ->
                assertThat(name).startsWith("adapter_")
                assertThat(name).matches("[a-z0-9_]+")
            }
            assertThat(wireNames).doesNotHaveDuplicates()
        }
    }

    @Nested
    inner class `The component template` {
        @Test
        fun `should map exactly the fields this module emits`() {
            // What is tested: that the template and the enum describe the same field set.
            // Success criteria: set equality - it fails in BOTH directions, a field added to the enum
            //   without a mapping AND a mapping left behind for a removed field.
            // Why it matters: an unmapped field is not an error at index time - Elasticsearch maps it
            //   dynamically, and for a body or a header that means the value becomes SEARCHABLE, the one
            //   outcome the mapping guide's sensitivity rule forbids.
            // Given: the fields the module can emit
            val emitted = ClientLogField.entries.map { it.wireName }

            // When / Then: the template maps those and no others
            assertThat(properties.keys).containsExactlyInAnyOrderElementsOf(emitted)
        }

        @Test
        fun `should keep every payload field out of the index`() {
            // What is tested: the mapping half of the sensitivity rule - headers and bodies must not be
            //   searchable, no matter how the rest of the template changes.
            // Success criteria: index false AND doc_values false asserted explicitly, not via the field
            //   set - a field silently re-typed to a searchable keyword would still pass the set check.
            // Why it matters: selection and masking in code are the real protection; the mapping is the
            //   second line, so a value that slips through cannot at least be searched for deliberately.
            // Given: the four fields carrying caller-controlled payload
            val sensitive =
                listOf(
                    ClientLogField.REQUEST_HEADERS,
                    ClientLogField.RESPONSE_HEADERS,
                    ClientLogField.REQUEST_BODY,
                    ClientLogField.RESPONSE_BODY,
                )

            // When / Then: none of them is indexed or given doc values
            sensitive.forEach { field ->
                assertThat(properties[field.wireName])
                    .describedAs("%s must not be searchable", field.wireName)
                    .containsEntry("index", false)
                    .containsEntry("doc_values", false)
            }
        }

        @Test
        fun `should keep the high-cardinality URL fields out of doc values but leave them searchable`() {
            // What is tested: the repetition-factor split of the path pair - the decision an unsuspecting
            //   edit is most likely to undo ("why is url_path not aggregatable? let me fix it").
            // Success criteria: path and query have doc_values off but stay indexed; the template half
            //   keeps its doc values as the aggregation counterpart.
            // Why it matters: the resolved path appears in about one line each - doc values on it grow an
            //   ordinal dictionary to the document count and buy only singleton buckets, while
            //   adapter_url_template is the field that answers "which endpoint is slow".
            // Given / When / Then: path and query are filterable but not groupable
            listOf(ClientLogField.URL_PATH, ClientLogField.URL_QUERY).forEach { field ->
                assertThat(properties[field.wireName])
                    .describedAs("%s: repetition factor ~1, see the mapping guide", field.wireName)
                    .containsEntry("doc_values", false)
                    .doesNotContainEntry("index", false)
            }

            // And: the template half and the host are the aggregation counterparts, so they keep
            //   their doc values
            assertThat(properties[ClientLogField.URL_TEMPLATE.wireName]).isEqualTo(mapOf("type" to "keyword"))
            assertThat(properties[ClientLogField.URL_HOST.wireName]).isEqualTo(mapOf("type" to "keyword"))
        }

        @Test
        fun `should map the numeric and boolean shapes the code guarantees`() {
            // Given / When / Then: the shape format() enforces in code and the type the index expects
            //   must describe the same value - long duration, short status (three digits, a label never
            //   summed), boolean flags
            assertThat(properties[ClientLogField.DURATION_MS.wireName]).containsEntry("type", "long")
            assertThat(properties[ClientLogField.RESPONSE_STATUS_CODE.wireName]).containsEntry("type", "short")
            assertThat(properties[ClientLogField.SLOW.wireName]).containsEntry("type", "boolean")
        }

        @Test
        fun `should be a component template, claiming no indices of its own`() {
            // Given / When: the top-level keys
            val root: Map<String, Any> = JsonPath.read(template, "$")

            // Then: no index_patterns - it composes into the host's template rather than competing with
            //   it on priority; which data streams carry these fields is the host pipeline's decision.
            assertThat(root).containsOnlyKeys("template", "_meta")
        }
    }

    @Nested
    inner class `Type guarantee` {
        @Test
        fun `should pass a correctly typed value through unchanged`() {
            // Given/When: values of the shape each field declares
            // Then: format returns the identical value - checked, never converted
            assertThat(ClientLogField.OUTCOME.format("success")).isEqualTo("success")
            assertThat(ClientLogField.DURATION_MS.format(42L)).isEqualTo(42L)
            assertThat(ClientLogField.RESPONSE_STATUS_CODE.format(200)).isEqualTo(200)
            assertThat(ClientLogField.SLOW.format(true)).isEqualTo(true)
        }

        @Test
        fun `should reject a value of the wrong type naming the field`() {
            // Given/When: an Int where the mapping says long
            val thrown = catchThrowable { ClientLogField.DURATION_MS.format(42) }

            // Then: the rejection names the field and both types
            assertThat(thrown)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("adapter_duration_ms")
                .hasMessageContaining("Long")
                .hasMessageContaining("Int")
        }
    }

    @Nested
    inner class `Drop the field not the event` {
        private val logger = LoggerFactory.getLogger("client-log-field-test") as Logger
        private lateinit var appender: ListAppender<ILoggingEvent>

        @BeforeEach
        fun setUp() {
            appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            logger.level = Level.INFO
        }

        @AfterEach
        fun tearDown() {
            logger.detachAppender(appender)
            appender.stop()
        }

        @Test
        fun `should drop a badly typed field but keep the event and its other fields`() {
            // What is tested: the fail-open contract of the addKeyValue(field, value) overload.
            // Success criteria: the event is logged, the well-typed field survives, the ill-typed field is
            //   absent - the statement never throws.
            // Why it matters: the exchange line is the observability of the request path; a type slip in
            //   one field must not take the whole statement (and the surrounding request) down with it.
            // Given: the field logger observed as well, so the promised warning is verifiable
            val fieldLogger = LoggerFactory.getLogger(ClientLogField::class.java) as Logger
            val fieldAppender = ListAppender<ILoggingEvent>().apply { start() }
            fieldLogger.addAppender(fieldAppender)
            try {
                // When: one well-typed and one ill-typed field on the same event
                logger
                    .atInfo()
                    .setMessage("exchange")
                    .addKeyValue(ClientLogField.OUTCOME, "success")
                    .addKeyValue(ClientLogField.DURATION_MS, "not-a-long")
                    .log()

                // Then: the event survived with only the well-typed field, and ONE warning names the dropped one
                val keyValues =
                    appender.list
                        .single()
                        .keyValuePairs
                        .associate { it.key to it.value }
                assertThat(keyValues)
                    .containsEntry("adapter_outcome", "success")
                    .doesNotContainKey("adapter_duration_ms")
                val warning = fieldAppender.list.single()
                assertThat(warning.level).isEqualTo(Level.WARN)
                assertThat(warning.formattedMessage).contains("adapter_duration_ms")
            } finally {
                fieldLogger.detachAppender(fieldAppender)
                fieldAppender.stop()
            }
        }
    }
}
