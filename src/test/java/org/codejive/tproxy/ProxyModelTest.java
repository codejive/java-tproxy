package org.codejive.tproxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for core proxy models. */
@DisplayName("Proxy Core Model Tests")
public class ProxyModelTest {

    @Test
    @DisplayName("ProxyRequest should be created with valid data")
    public void testProxyRequestCreation() {
        URI uri = URI.create("https://api.example.com/test");
        Headers headers = Headers.of("Content-Type", "application/json");
        byte[] body = "test body".getBytes();

        ProxyRequest request = new ProxyRequest("GET", uri, headers, body);

        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.uri()).isEqualTo(uri);
        assertThat(request.headers().first("Content-Type")).isEqualTo("application/json");
        assertThat(request.body()).isEqualTo(body);
    }

    @Test
    @DisplayName("ProxyRequest should handle null body")
    public void testProxyRequestNullBody() {
        URI uri = URI.create("https://api.example.com/test");

        ProxyRequest request = new ProxyRequest("GET", uri, Headers.empty(), null);

        assertThat(request.body()).isNotNull();
        assertThat(request.body()).isEmpty();
    }

    @Test
    @DisplayName("ProxyRequest withX methods should create modified copies")
    public void testProxyRequestWithMethods() {
        URI uri = URI.create("https://api.example.com/test");
        ProxyRequest original = new ProxyRequest("GET", uri, Headers.empty(), null);

        ProxyRequest withMethod = original.withMethod("POST");
        assertThat(withMethod.method()).isEqualTo("POST");
        assertThat(withMethod.uri()).isEqualTo(uri);

        URI newUri = URI.create("https://other.example.com");
        ProxyRequest withUri = original.withUri(newUri);
        assertThat(withUri.uri()).isEqualTo(newUri);
        assertThat(withUri.method()).isEqualTo("GET");

        ProxyRequest withHeader = original.withHeader("X-Custom", "value");
        assertThat(withHeader.headers().first("X-Custom")).isEqualTo("value");

        byte[] newBody = "new body".getBytes();
        ProxyRequest withBody = original.withBody(newBody);
        assertThat(withBody.body()).isEqualTo(newBody);
    }

    @Test
    @DisplayName("ProxyRequest should support multi-value headers")
    public void testProxyRequestMultiValueHeaders() {
        URI uri = URI.create("https://api.example.com/test");
        Headers headers = Headers.of(Map.of("Accept", List.of("text/html", "application/json")));

        ProxyRequest request = new ProxyRequest("GET", uri, headers, null);

        assertThat(request.headers().all("Accept"))
                .containsExactly("text/html", "application/json");
    }

    @Test
    @DisplayName("Headers should be case-insensitive")
    public void testHeadersCaseInsensitive() {
        Headers headers = Headers.of("Content-Type", "application/json");

        assertThat(headers.first("content-type")).isEqualTo("application/json");
        assertThat(headers.first("CONTENT-TYPE")).isEqualTo("application/json");
        assertThat(headers.contains("content-type")).isTrue();
    }

    @Test
    @DisplayName("ProxyResponse should be created with valid data")
    public void testProxyResponseCreation() {
        Headers headers = Headers.of("Content-Type", "application/json");
        byte[] body = "{\"status\":\"ok\"}".getBytes();

        ProxyResponse response = new ProxyResponse(200, headers, body);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().first("Content-Type")).isEqualTo("application/json");
        assertThat(response.body()).isEqualTo(body);
    }

    @Test
    @DisplayName("ProxyResponse should handle null body")
    public void testProxyResponseNullBody() {
        ProxyResponse response = new ProxyResponse(204, Headers.empty(), null);

        assertThat(response.body()).isNotNull();
        assertThat(response.body()).isEmpty();
    }

    @Test
    @DisplayName("ProxyResponse withX methods should create modified copies")
    public void testProxyResponseWithMethods() {
        ProxyResponse original = new ProxyResponse(200, Headers.empty(), "body".getBytes());

        ProxyResponse withStatus = original.withStatusCode(404);
        assertThat(withStatus.statusCode()).isEqualTo(404);
        assertThat(withStatus.body()).isEqualTo("body".getBytes());

        ProxyResponse withHeader = original.withHeader("X-Custom", "value");
        assertThat(withHeader.headers().first("X-Custom")).isEqualTo("value");

        ProxyResponse withBody = original.withBody("new".getBytes());
        assertThat(withBody.body()).isEqualTo("new".getBytes());
        assertThat(withBody.statusCode()).isEqualTo(200);
    }
}
