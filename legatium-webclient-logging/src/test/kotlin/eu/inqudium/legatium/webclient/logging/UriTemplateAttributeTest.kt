package eu.inqudium.legatium.webclient.logging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.net.URI

/**
 * Pins the mirrored attribute name against the value `WebClient` ACTUALLY records - through a real
 * client with a stub exchange function, and through the private constant itself.
 */
class UriTemplateAttributeTest {
    /** A real client whose exchange function records the request it is handed. */
    private val exchange = RecordingExchange()
    private val client =
        WebClient
            .builder()
            .baseUrl("https://api.example.com")
            .exchangeFunction(exchange)
            .build()

    @Test
    fun `should see the URI template WebClient records for the template form of uri`() {
        // What is tested: the mirrored attribute name - DefaultWebClient's constant is private, so the
        //   module derives it the same way and this test proves the derivation against the real client.
        // Success criteria: a call through `uri("/things/{id}", 7)` shows the expanded URL on the
        //   request and the template under the mirrored attribute.
        // Why it matters: a renamed attribute upstream would silently drop adapter_url_template.
        // Given/When
        client
            .get()
            .uri("/things/{id}", 7)
            .retrieve()
            .toBodilessEntity()
            .block()

        // Then
        val sent = requireNotNull(exchange.sent)
        assertThat(sent.url()).isEqualTo(URI.create("https://api.example.com/things/7"))
        assertThat(sent.attributes()).containsEntry(ClientRequestLoggingFilter.URI_TEMPLATE_ATTRIBUTE, "https://api.example.com/things/{id}")
    }

    @Test
    fun `should see no URI template for the expanded form of uri`() {
        // What is tested: the absence side of the mirrored attribute - a call through `uri(URI)`
        //   records no template.
        // Success criteria: the request WebClient hands the exchange function carries no attribute
        //   under the mirrored name.
        // Why it matters: adapter_url_template must stay absent for an expanded URI, never repeat the
        //   expanded path as a "template".
        // Given/When
        client
            .get()
            .uri(URI.create("https://api.example.com/things/8"))
            .retrieve()
            .toBodilessEntity()
            .block()

        // Then
        assertThat(requireNotNull(exchange.sent).attributes()).doesNotContainKey(ClientRequestLoggingFilter.URI_TEMPLATE_ATTRIBUTE)
    }

    @Test
    fun `should mirror the private constant of DefaultWebClient literally`() {
        // What is tested: the mirrored URI_TEMPLATE_ATTRIBUTE against the private field of Spring's
        //   DefaultWebClient, read via reflection.
        // Success criteria: both strings are identical.
        // Why it matters: the constant is private upstream and can only be mirrored; a change in
        //   Spring's derivation would drop adapter_url_template from every event without a compile
        //   error.
        // Given/When
        val field =
            Class
                .forName("org.springframework.web.reactive.function.client.DefaultWebClient")
                .getDeclaredField("URI_TEMPLATE_ATTRIBUTE")
                .apply { isAccessible = true }

        // Then
        assertThat(field.get(null)).isEqualTo(ClientRequestLoggingFilter.URI_TEMPLATE_ATTRIBUTE)
    }
}
