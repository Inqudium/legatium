package eu.inqudium.legatium.restclient.logging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.web.client.RestClient
import java.net.URI

/**
 * Pins the mirrored attribute name against the value `RestClient` ACTUALLY records - through a real
 * client with a mock request factory, and through the private constant itself.
 */
class UriTemplateAttributeTest {
    private val requestFactory =
        ClientHttpRequestFactory { uri, method ->
            MockClientHttpRequest(method, uri).apply { setResponse(MockClientHttpResponse(ByteArray(0), HttpStatus.OK)) }
        }

    @Test
    fun `should see the URI template RestClient records for the template form of uri`() {
        // What is tested: the mirrored attribute name - DefaultRestClient's constant is package-private,
        //   so the module derives it the same way and this test proves the derivation against the real
        //   client.
        // Success criteria: a call through `uri("/things/{id}", 7)` shows the expanded path on the
        //   request and the template under the mirrored attribute; an expanded URI shows no attribute.
        // Why it matters: a renamed attribute upstream would silently drop adapter_url_template from
        //   every event.
        // Given: a client with a recording interceptor
        var seen: Map<String, Any>? = null
        var seenUri: URI? = null
        val client =
            RestClient
                .builder()
                .baseUrl("https://api.example.com")
                .requestFactory(requestFactory)
                .requestInterceptor { request, body, execution ->
                    seen = request.attributes.toMap()
                    seenUri = request.uri
                    execution.execute(request, body)
                }.build()

        // When
        client
            .get()
            .uri("/things/{id}", 7)
            .retrieve()
            .toBodilessEntity()

        // Then
        assertThat(seenUri).isEqualTo(URI.create("https://api.example.com/things/7"))
        assertThat(seen).containsEntry(ClientRequestLoggingInterceptor.URI_TEMPLATE_ATTRIBUTE, "https://api.example.com/things/{id}")

        // When: an expanded URI
        client
            .get()
            .uri(URI.create("https://api.example.com/things/8"))
            .retrieve()
            .toBodilessEntity()

        // Then
        assertThat(seen).doesNotContainKey(ClientRequestLoggingInterceptor.URI_TEMPLATE_ATTRIBUTE)
    }

    @Test
    fun `should mirror the private constant of DefaultRestClient literally`() {
        // What is tested: the package-private DefaultRestClient.URI_TEMPLATE_ATTRIBUTE read
        //   reflectively, compared with the module's own derived constant.
        // Success criteria: both strings are identical.
        // Why it matters: the constant cannot be referenced, so the module repeats its derivation;
        //   a Spring upgrade that renames the attribute would otherwise silently drop
        //   adapter_url_template and fold every body meter into the UNKNOWN uri tag.
        // Given/When: the package-private client's constant, read reflectively
        val field =
            Class
                .forName("org.springframework.web.client.DefaultRestClient")
                .getDeclaredField("URI_TEMPLATE_ATTRIBUTE")
                .apply { isAccessible = true }

        // Then
        assertThat(field.get(null)).isEqualTo(ClientRequestLoggingInterceptor.URI_TEMPLATE_ATTRIBUTE)
    }
}
