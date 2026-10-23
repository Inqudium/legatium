package eu.inqudium.legatium.restclient.logging

import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.RequestBuilder
import org.apache.http.entity.ByteArrayEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.util.EntityUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.AbstractBufferingClientHttpRequest
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.ClientHttpResponse
import java.io.IOException
import java.io.InputStream
import java.net.URI

/**
 * A `ClientHttpRequestFactory` for Apache HttpClient 4, TEST-ONLY: Spring Framework 6 dropped its own
 * (`HttpComponentsClientHttpRequestFactory` targets HttpClient 5 since), so a host still on HttpClient 4
 * bridges it with an adapter of its own - this is the shape such an adapter takes, modelled on the one
 * Spring 5 shipped: the body buffered, executed in one `execute`, the response's entity consumed and the
 * response closed at `close`. The request-factory contract runs through it; it is not part of the
 * module.
 *
 * Timeouts are the client's (`RequestConfig` on the builder), not the adapter's.
 */
internal class HttpClient4RequestFactory(
    private val httpClient: CloseableHttpClient,
) : ClientHttpRequestFactory,
    AutoCloseable {
    override fun createRequest(
        uri: URI,
        httpMethod: HttpMethod,
    ): ClientHttpRequest = HttpClient4Request(httpClient, uri, httpMethod)

    override fun close() = httpClient.close()
}

private class HttpClient4Request(
    private val httpClient: CloseableHttpClient,
    private val uri: URI,
    private val method: HttpMethod,
) : AbstractBufferingClientHttpRequest() {
    override fun getMethod(): HttpMethod = method

    override fun getURI(): URI = uri

    override fun executeInternal(
        headers: HttpHeaders,
        bufferedOutput: ByteArray,
    ): ClientHttpResponse {
        val builder = RequestBuilder.create(method.name()).setUri(uri)
        // The client computes the framing headers from the entity itself; passing them through would
        // make it reject the request (ProtocolException: Content-Length header already present).
        headers.headerNames().filterNot { FRAMING_HEADERS.any { framing -> framing.equals(it, ignoreCase = true) } }.forEach { name ->
            headers[name]?.forEach { value -> builder.addHeader(name, value) }
        }
        if (bufferedOutput.isNotEmpty()) {
            builder.entity = ByteArrayEntity(bufferedOutput)
        }
        return HttpClient4Response(httpClient.execute(builder.build()))
    }

    private companion object {
        val FRAMING_HEADERS = listOf(HttpHeaders.CONTENT_LENGTH, HttpHeaders.TRANSFER_ENCODING)
    }
}

private class HttpClient4Response(
    private val response: CloseableHttpResponse,
) : ClientHttpResponse {
    private val headers =
        HttpHeaders().also { headers -> response.allHeaders.forEach { headers.add(it.name, it.value) } }

    override fun getStatusCode(): HttpStatusCode = HttpStatusCode.valueOf(response.statusLine.statusCode)

    override fun getStatusText(): String = response.statusLine.reasonPhrase

    override fun getHeaders(): HttpHeaders = headers

    override fun getBody(): InputStream = response.entity?.content ?: InputStream.nullInputStream()

    // As Spring 5's adapter: consume what is left (the connection goes back to the pool reusable),
    // then close; a failure there is the pool's business, not the caller's.
    override fun close() {
        try {
            try {
                EntityUtils.consume(response.entity)
            } finally {
                response.close()
            }
        } catch (_: IOException) {
            // Ignored, as in Spring 5's HttpComponentsClientHttpResponse.
        }
    }
}
