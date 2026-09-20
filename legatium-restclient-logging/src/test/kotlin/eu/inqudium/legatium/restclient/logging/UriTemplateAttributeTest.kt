package eu.inqudium.legatium.restclient.logging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpRequest
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
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

    /** An interceptor in the position the module's takes, recording the attributes and the URI of the last request it saw. */
    private class RecordingInterceptor : ClientHttpRequestInterceptor {
        var attributes: Map<String, Any> = emptyMap()
            private set
        var uri: URI? = null
            private set

        override fun intercept(
            request: HttpRequest,
            body: ByteArray,
            execution: ClientHttpRequestExecution,
        ): ClientHttpResponse {
            attributes = request.attributes.toMap()
            uri = request.uri
            return execution.execute(request, body)
        }
    }

    private val recording = RecordingInterceptor()

    /** A real client over the mock factory, with the recording interceptor in the position the module's interceptor takes. */
    private val client =
        RestClient
            .builder()
            .baseUrl("https://api.example.com")
            .requestFactory(requestFactory)
            .requestInterceptor(recording)
            .build()

    @Test
    fun `should see the URI template RestClient records for the template form of uri`() {
        // What is tested: the mirrored attribute name - DefaultRestClient's constant is package-private,
        //   so the module derives it the same way and this test proves the derivation against the real
        //   client.
        // Success criteria: a call through `uri("/things/{id}", 7)` shows the expanded path on the
        //   request and the template under the mirrored attribute.
        // Why it matters: a renamed attribute upstream would silently drop adapter_url_template from
        //   every event.
        // Given/When: a call through the template form of uri
        client
            .get()
            .uri("/things/{id}", 7)
            .retrieve()
            .toBodilessEntity()

        // Then
        assertThat(recording.uri).isEqualTo(URI.create("https://api.example.com/things/7"))
        assertThat(recording.attributes).containsEntry(ClientRequestLoggingInterceptor.URI_TEMPLATE_ATTRIBUTE, "https://api.example.com/things/{id}")
    }

    @Test
    fun `should see no URI template for an expanded URI`() {
        // What is tested: the other side of the mirrored attribute - a call through `uri(URI)` records
        //   no template.
        // Success criteria: the request's attributes carry no entry under the mirrored name.
        // Why it matters: the emitter leaves adapter_url_template off exactly when the attribute is
        //   absent; a template invented for an expanded URI would mislabel the body meters.
        // Given/When: a call through an expanded URI
        client
            .get()
            .uri(URI.create("https://api.example.com/things/8"))
            .retrieve()
            .toBodilessEntity()

        // Then
        assertThat(recording.attributes).doesNotContainKey(ClientRequestLoggingInterceptor.URI_TEMPLATE_ATTRIBUTE)
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
