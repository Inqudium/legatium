package eu.inqudium.legatium.webclient.logging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.net.URI

/**
 * Pins the mirrored attribute name against the value `WebClient` ACTUALLY records - through a real
 * client with a stub exchange function, and through the private constant itself.
 */
class UriTemplateAttributeTest {
    @Test
    fun `should see the URI template WebClient records for the template form of uri`() {
        // What is tested: the mirrored attribute name - DefaultWebClient's constant is private, so the
        //   module derives it the same way and this test proves the derivation against the real client.
        // Success criteria: a call through `uri("/things/{id}", 7)` shows the expanded URL on the
        //   request and the template under the mirrored attribute; an expanded URI shows no attribute.
        // Why it matters: a renamed attribute upstream would silently drop client_url_template.
        // Given: a client whose exchange function records what it is handed
        var seen: Map<String, Any>? = null
        var seenUrl: URI? = null
        val client =
            WebClient
                .builder()
                .baseUrl("https://api.example.com")
                .exchangeFunction(
                    ExchangeFunction { request ->
                        seen = request.attributes().toMap()
                        seenUrl = request.url()
                        Mono.just(ClientResponse.create(HttpStatus.OK).build())
                    },
                ).build()

        // When
        client
            .get()
            .uri("/things/{id}", 7)
            .retrieve()
            .toBodilessEntity()
            .block()

        // Then
        assertThat(seenUrl).isEqualTo(URI.create("https://api.example.com/things/7"))
        assertThat(seen).containsEntry(ClientRequestLoggingFilter.URI_TEMPLATE_ATTRIBUTE, "https://api.example.com/things/{id}")

        // When: an expanded URI
        client
            .get()
            .uri(URI.create("https://api.example.com/things/8"))
            .retrieve()
            .toBodilessEntity()
            .block()

        // Then
        assertThat(seen).doesNotContainKey(ClientRequestLoggingFilter.URI_TEMPLATE_ATTRIBUTE)
    }

    @Test
    fun `should mirror the private constant of DefaultWebClient literally`() {
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
