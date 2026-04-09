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

        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getUri()).isEqualTo(uri);
        assertThat(request.getHeaders().getFirst("Content-Type")).isEqualTo("application/json");
        assertThat(request.getBody()).isEqualTo(body);
    }

    @Test
    @DisplayName("ProxyRequest should handle null body")
    public void testProxyRequestNullBody() {
        URI uri = URI.create("https://api.example.com/test");

        ProxyRequest request = new ProxyRequest("GET", uri, Headers.empty(), null);

        assertThat(request.getBody()).isNotNull();
        assertThat(request.getBody()).isEmpty();
    }

    @Test
    @DisplayName("ProxyRequest withX methods should create modified copies")
    public void testProxyRequestWithMethods() {
        URI uri = URI.create("https://api.example.com/test");
        ProxyRequest original = new ProxyRequest("GET", uri, Headers.empty(), null);

        ProxyRequest withMethod = original.withMethod("POST");
        assertThat(withMethod.getMethod()).isEqualTo("POST");
        assertThat(withMethod.getUri()).isEqualTo(uri);

        URI newUri = URI.create("https://other.example.com");
        ProxyRequest withUri = original.withUri(newUri);
        assertThat(withUri.getUri()).isEqualTo(newUri);
        assertThat(withUri.getMethod()).isEqualTo("GET");

        ProxyRequest withHeader = original.withHeader("X-Custom", "value");
        assertThat(withHeader.getHeaders().getFirst("X-Custom")).isEqualTo("value");

        byte[] newBody = "new body".getBytes();
        ProxyRequest withBody = original.withBody(newBody);
        assertThat(withBody.getBody()).isEqualTo(newBody);
    }

    @Test
    @DisplayName("ProxyRequest should support multi-value headers")
    public void testProxyRequestMultiValueHeaders() {
        URI uri = URI.create("https://api.example.com/test");
        Headers headers = Headers.of(Map.of("Accept", List.of("text/html", "application/json")));

        ProxyRequest request = new ProxyRequest("GET", uri, headers, null);

        assertThat(request.getHeaders().getAll("Accept"))
                .containsExactly("text/html", "application/json");
    }

    @Test
    @DisplayName("Headers should be case-insensitive")
    public void testHeadersCaseInsensitive() {
        Headers headers = Headers.of("Content-Type", "application/json");

        assertThat(headers.getFirst("content-type")).isEqualTo("application/json");
        assertThat(headers.getFirst("CONTENT-TYPE")).isEqualTo("application/json");
        assertThat(headers.contains("content-type")).isTrue();
    }

    @Test
    @DisplayName("ProxyResponse should be created with valid data")
    public void testProxyResponseCreation() {
        Headers headers = Headers.of("Content-Type", "application/json");
        byte[] body = "{\"status\":\"ok\"}".getBytes();

        ProxyResponse response = new ProxyResponse(200, headers, body);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("application/json");
        assertThat(response.getBody()).isEqualTo(body);
    }

    @Test
    @DisplayName("ProxyResponse should handle null body")
    public void testProxyResponseNullBody() {
        ProxyResponse response = new ProxyResponse(204, Headers.empty(), null);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    @DisplayName("ProxyResponse withX methods should create modified copies")
    public void testProxyResponseWithMethods() {
        ProxyResponse original = new ProxyResponse(200, Headers.empty(), "body".getBytes());

        ProxyResponse withStatus = original.withStatusCode(404);
        assertThat(withStatus.getStatusCode()).isEqualTo(404);
        assertThat(withStatus.getBody()).isEqualTo("body".getBytes());

        ProxyResponse withHeader = original.withHeader("X-Custom", "value");
        assertThat(withHeader.getHeaders().getFirst("X-Custom")).isEqualTo("value");

        ProxyResponse withBody = original.withBody("new".getBytes());
        assertThat(withBody.getBody()).isEqualTo("new".getBytes());
        assertThat(withBody.getStatusCode()).isEqualTo(200);
    }
}
