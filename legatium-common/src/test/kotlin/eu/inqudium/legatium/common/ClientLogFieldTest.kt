package eu.inqudium.legatium.common

import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
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
            // What is tested: the type half of the lockstep - the three non-keyword fields against the JVM
            //   type the enum declares (Long duration, Int status, Boolean slow flag).
            // Success criteria: duration maps as long, the status code as short, the slow flag as boolean.
            // Why it matters: a keyword duration cannot be ranged or percentiled, and a status mapped as a
            //   number that is summed reads as garbage - the index type is what makes the dashboards work.
            // Given / When / Then: the shape each field declares and the type the index expects
            //   must describe the same value - long duration, short status (three digits, a label never
            //   summed), boolean flags
            assertThat(properties[ClientLogField.DURATION_MS.wireName]).containsEntry("type", "long")
            assertThat(properties[ClientLogField.RESPONSE_STATUS_CODE.wireName]).containsEntry("type", "short")
            assertThat(properties[ClientLogField.SLOW.wireName]).containsEntry("type", "boolean")
        }

        @Test
        fun `should be a component template, claiming no indices of its own`() {
            // What is tested: the top-level shape of the shipped JSON - a component template, not an index
            //   template.
            // Success criteria: the root carries only `template` and `_meta`; in particular no
            //   `index_patterns`.
            // Why it matters: an index_patterns entry would compete with the host's own template on priority
            //   and claim data streams the host never meant to hand to this module.
            // Given / When: the top-level keys
            val root: Map<String, Any> = JsonPath.read(template, "$")

            // Then: no index_patterns - it composes into the host's template rather than competing with
            //   it on priority; which data streams carry these fields is the host pipeline's decision.
            assertThat(root).containsOnlyKeys("template", "_meta")
        }
    }

    @Nested
    inner class `Declared shape` {
        @Test
        fun `should accept exactly the JVM type each field declares`() {
            // What is tested: the shape each field declares for the index template - what the lockstep
            //   test maps and what the emitters pass.
            // Success criteria: a value of the declared type is accepted, a value of another type (an Int
            //   where the mapping says long) or null is not.
            // Why it matters: the declared shape is what the component template maps; the emitters are
            //   the only writers, so the declaration is a pin, not a runtime gate.
            // Given/When/Then
            assertThat(ClientLogField.OUTCOME.accepts("success")).isTrue()
            assertThat(ClientLogField.DURATION_MS.accepts(42L)).isTrue()
            assertThat(ClientLogField.RESPONSE_STATUS_CODE.accepts(200)).isTrue()
            assertThat(ClientLogField.SLOW.accepts(true)).isTrue()
            assertThat(ClientLogField.DURATION_MS.accepts(42)).isFalse()
            assertThat(ClientLogField.OUTCOME.accepts(null)).isFalse()
        }
    }
}
