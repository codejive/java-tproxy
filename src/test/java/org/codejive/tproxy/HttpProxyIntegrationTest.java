package org.codejive.tproxy;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;

/** Integration tests for the HTTP proxy using WireMock as backend server. */
@DisplayName("HTTP Proxy Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HttpProxyIntegrationTest {

    private static final int PROXY_PORT = 8889;
    private static final int BACKEND_PORT = 9091;

    private HttpProxy proxy;
    private WireMockServer mockServer;
    private HttpClient httpClient;

    @BeforeEach
    public void setUp() throws IOException {
        // Start WireMock backend server
        mockServer =
                new WireMockServer(
                        WireMockConfiguration.options()
                                .port(BACKEND_PORT)
                                .bindAddress("127.0.0.1"));
        mockServer.start();

        // Create and start proxy
        proxy = new HttpProxy();
        proxy.start(PROXY_PORT);

        // Create HTTP client for manual testing (not using proxy)
        httpClient = HttpClient.newBuilder().build();

        // Wait a bit for servers to fully start
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterEach
    public void tearDown() {
        if (proxy != null) {
            proxy.stop();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Should forward GET request through proxy")
    public void testForwardGetRequest() throws Exception {
        // Setup mock backend
        mockServer.stubFor(
                get(urlEqualTo("/api/test"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withHeader("X-Custom", "test-value")
                                        .withBody("{\"status\":\"ok\"}")));

        // Make request through proxy
        ProxyRequest request =
                new ProxyRequest(
                        "GET",
                        URI.create("http://127.0.0.1:" + BACKEND_PORT + "/api/test"),
                        Headers.of("User-Agent", "TestClient/1.0"),
                        null);

        ProxyResponse response = proxy.execute(request);

        // Verify response
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().first("Content-Type")).isEqualTo("application/json");
        assertThat(response.headers().first("X-Custom")).isEqualTo("test-value");
        assertThat(new String(response.body())).isEqualTo("{\"status\":\"ok\"}");

        // Verify backend received the request
        mockServer.verify(getRequestedFor(urlEqualTo("/api/test")));
    }

    @Test
    @Order(2)
    @DisplayName("Should forward POST request with body")
    public void testForwardPostRequest() throws Exception {
        // Setup mock backend
        mockServer.stubFor(
                post(urlEqualTo("/api/data"))
                        .withRequestBody(equalToJson("{\"name\":\"test\"}"))
                        .willReturn(
                                aResponse()
                                        .withStatus(201)
                                        .withHeader("Location", "/api/data/123")
                                        .withBody("{\"id\":123}")));

        // Make request through proxy
        String requestBody = "{\"name\":\"test\"}";
        ProxyRequest request =
                new ProxyRequest(
                        "POST",
                        URI.create("http://127.0.0.1:" + BACKEND_PORT + "/api/data"),
                        Headers.of("Content-Type", "application/json"),
                        requestBody.getBytes());

        ProxyResponse response = proxy.execute(request);

        // Verify response
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().first("Location")).isEqualTo("/api/data/123");
        assertThat(new String(response.body())).isEqualTo("{\"id\":123}");

        // Verify backend received the request
        mockServer.verify(
                postRequestedFor(urlEqualTo("/api/data"))
                        .withRequestBody(equalToJson("{\"name\":\"test\"}")));
    }

    @Test
    @Order(3)
    @DisplayName("Should preserve query parameters")
    public void testQueryParameterPreservation() throws Exception {
        // Setup mock backend
        mockServer.stubFor(
                get(urlPathEqualTo("/search"))
                        .withQueryParam("q", equalTo("test query"))
                        .withQueryParam("page", equalTo("2"))
                        .willReturn(aResponse().withStatus(200).withBody("results")));

        // Make request through proxy
        ProxyRequest request =
                new ProxyRequest(
                        "GET",
                        URI.create(
                                "http://127.0.0.1:" + BACKEND_PORT + "/search?q=test+query&page=2"),
                        Headers.empty(),
                        null);

        ProxyResponse response = proxy.execute(request);

        // Verify response
        assertThat(response.statusCode()).isEqualTo(200);

        // Verify backend received correct query params
        mockServer.verify(
                getRequestedFor(urlPathEqualTo("/search"))
                        .withQueryParam("q", equalTo("test query"))
                        .withQueryParam("page", equalTo("2")));
    }

    @Test
    @Order(4)
    @DisplayName("Should work with interceptor capturing requests")
    public void testInterceptorCapture() throws Exception {
        // Setup interceptor to capture requests
        List<ProxyRequest> capturedRequests = new ArrayList<>();
        proxy.addInterceptor(
                (request, chain) -> {
                    capturedRequests.add(request);
                    return chain.proceed(request);
                });

        // Setup mock backend
        mockServer.stubFor(get(urlEqualTo("/api/item")).willReturn(aResponse().withStatus(200)));

        // Make request through proxy
        ProxyRequest request =
                new ProxyRequest(
                        "GET",
                        URI.create("http://127.0.0.1:" + BACKEND_PORT + "/api/item"),
                        Headers.of("X-Request-ID", "12345"),
                        null);

        proxy.execute(request);

        // Verify interceptor captured the request
        assertThat(capturedRequests).hasSize(1);
        ProxyRequest captured = capturedRequests.get(0);
        assertThat(captured.method()).isEqualTo("GET");
        assertThat(captured.uri().getPath()).isEqualTo("/api/item");
        assertThat(captured.headers().first("X-Request-ID")).isEqualTo("12345");
    }

    @Test
    @Order(5)
    @DisplayName("Should work with interceptor modifying requests")
    public void testInterceptorModifyRequest() throws Exception {
        // Setup interceptor to add a custom header
        proxy.addInterceptor(
                (request, chain) -> {
                    ProxyRequest modifiedRequest =
                            request.withHeader("X-Proxy-Added", "through-proxy");
                    return chain.proceed(modifiedRequest);
                });

        // Setup mock backend
        mockServer.stubFor(get(urlEqualTo("/api/test")).willReturn(aResponse().withStatus(200)));

        // Make request through proxy
        ProxyRequest request =
                new ProxyRequest(
                        "GET",
                        URI.create("http://127.0.0.1:" + BACKEND_PORT + "/api/test"),
                        Headers.empty(),
                        null);

        proxy.execute(request);

        // Verify backend received the modified header
        mockServer.verify(
                getRequestedFor(urlEqualTo("/api/test"))
                        .withHeader("X-Proxy-Added", equalTo("through-proxy")));
    }

    @Test
    @Order(6)
    @DisplayName("Should work with interceptor modifying responses")
    public void testInterceptorModifyResponse() throws Exception {
        // Setup interceptor to modify response
        proxy.addInterceptor(
                (request, chain) -> {
                    ProxyResponse originalResponse = chain.proceed(request);
                    return originalResponse.withHeader("X-Proxy-Modified", "true");
                });

        // Setup mock backend
        mockServer.stubFor(
                get(urlEqualTo("/api/test"))
                        .willReturn(aResponse().withStatus(200).withBody("original")));

        // Make request through proxy
        ProxyRequest request =
                new ProxyRequest(
                        "GET",
                        URI.create("http://127.0.0.1:" + BACKEND_PORT + "/api/test"),
                        Headers.empty(),
                        null);

        ProxyResponse response = proxy.execute(request);

        // Verify response was modified
        assertThat(response.headers().first("X-Proxy-Modified")).isEqualTo("true");
        assertThat(new String(response.body())).isEqualTo("original");
    }

    @Test
    @Order(7)
    @DisplayName("Should handle multiple HTTP methods")
    public void testMultipleHttpMethods() throws Exception {
        // Setup mock backend
        mockServer.stubFor(put(urlEqualTo("/resource")).willReturn(aResponse().withStatus(200)));
        mockServer.stubFor(delete(urlEqualTo("/resource")).willReturn(aResponse().withStatus(204)));
        mockServer.stubFor(patch(urlEqualTo("/resource")).willReturn(aResponse().withStatus(200)));

        // Test PUT
        ProxyRequest putRequest =
                new ProxyRequest(
                        "PUT",
                        URI.create("http://127.0.0.1:" + BACKEND_PORT + "/resource"),
                        Headers.empty(),
                        "updated".getBytes());
        ProxyResponse putResponse = proxy.execute(putRequest);
        assertThat(putResponse.statusCode()).isEqualTo(200);

        // Test DELETE
        ProxyRequest deleteRequest =
                new ProxyRequest(
                        "DELETE",
                        URI.create("http://127.0.0.1:" + BACKEND_PORT + "/resource"),
                        Headers.empty(),
                        null);
        ProxyResponse deleteResponse = proxy.execute(deleteRequest);
        assertThat(deleteResponse.statusCode()).isEqualTo(204);

        // Test PATCH
        ProxyRequest patchRequest =
                new ProxyRequest(
                        "PATCH",
                        URI.create("http://127.0.0.1:" + BACKEND_PORT + "/resource"),
                        Headers.empty(),
                        "patched".getBytes());
        ProxyResponse patchResponse = proxy.execute(patchRequest);
        assertThat(patchResponse.statusCode()).isEqualTo(200);
    }

    @Test
    @Order(8)
    @DisplayName("Should handle error responses from backend")
    public void testErrorResponses() throws Exception {
        // Setup mock backend to return errors
        mockServer.stubFor(get(urlEqualTo("/not-found")).willReturn(aResponse().withStatus(404)));
        mockServer.stubFor(
                get(urlEqualTo("/server-error")).willReturn(aResponse().withStatus(500)));

        // Test 404
        ProxyRequest notFoundRequest =
                new ProxyRequest(
                        "GET",
                        URI.create("http://127.0.0.1:" + BACKEND_PORT + "/not-found"),
                        Headers.empty(),
                        null);
        ProxyResponse notFoundResponse = proxy.execute(notFoundRequest);
        assertThat(notFoundResponse.statusCode()).isEqualTo(404);

        // Test 500
        ProxyRequest errorRequest =
                new ProxyRequest(
                        "GET",
                        URI.create("http://127.0.0.1:" + BACKEND_PORT + "/server-error"),
                        Headers.empty(),
                        null);
        ProxyResponse errorResponse = proxy.execute(errorRequest);
        assertThat(errorResponse.statusCode()).isEqualTo(500);
    }
}
