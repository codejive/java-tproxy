package org.codejive.tproxy;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;

/**
 * Integration tests for HTTPS pass-through tunneling. Tests the CONNECT method handler without
 * decryption/inspection.
 */
@DisplayName("HTTPS Pass-through Tunneling Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HttpsPassThroughTest {

    private static final int PROXY_PORT = 8890;
    private static final int HTTPS_BACKEND_PORT = 9443;

    private HttpProxy proxy;
    private WireMockServer httpsServer;
    private HttpClient httpClient;

    @BeforeEach
    public void setUp() throws IOException {
        // Start HTTPS WireMock server on fixed port
        httpsServer =
                new WireMockServer(
                        options().httpsPort(HTTPS_BACKEND_PORT).bindAddress("localhost"));
        httpsServer.start();

        // Create and start proxy
        proxy = new HttpProxy();
        proxy.start(PROXY_PORT);

        // Create HTTP client configured to use the proxy and trust all certificates
        try {
            SSLContext sslContext = createTrustAllSSLContext();

            httpClient =
                    HttpClient.newBuilder()
                            .proxy(
                                    ProxySelector.of(
                                            new java.net.InetSocketAddress(
                                                    "127.0.0.1", PROXY_PORT)))
                            .sslContext(sslContext)
                            .build();
        } catch (Exception e) {
            throw new IOException("Failed to create HTTP client", e);
        }

        // Wait for servers to start
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterEach
    public void tearDown() {
        if (proxy != null) {
            proxy.stop();
        }
        if (httpsServer != null) {
            httpsServer.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Should establish CONNECT tunnel for HTTPS")
    public void testConnectTunnel() throws Exception {
        // Setup HTTPS backend
        httpsServer.stubFor(
                get(urlEqualTo("/secure"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"secure\":true}")));

        // Make HTTPS request through proxy using CONNECT tunnel
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("https://localhost:" + HTTPS_BACKEND_PORT + "/secure"))
                        .GET()
                        .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Verify response
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"secure\":true}");

        // Verify backend received the request
        httpsServer.verify(getRequestedFor(urlEqualTo("/secure")));
    }

    @Test
    @Order(2)
    @DisplayName("Should handle HTTPS POST through CONNECT tunnel")
    public void testConnectTunnelPost() throws Exception {
        // Setup HTTPS backend
        httpsServer.stubFor(
                post(urlEqualTo("/secure/data"))
                        .withRequestBody(equalToJson("{\"data\":\"secret\"}"))
                        .willReturn(
                                aResponse()
                                        .withStatus(201)
                                        .withBody("{\"id\":\"encrypted-123\"}")));

        // Make HTTPS POST request through proxy
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("https://localhost:" + HTTPS_BACKEND_PORT + "/secure/data"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"data\":\"secret\"}"))
                        .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Verify response
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("encrypted-123");

        // Verify backend received the request
        httpsServer.verify(
                postRequestedFor(urlEqualTo("/secure/data"))
                        .withRequestBody(equalToJson("{\"data\":\"secret\"}")));
    }

    @Test
    @Order(3)
    @DisplayName("Should handle multiple HTTPS requests through same tunnel")
    public void testMultipleRequestsThroughTunnel() throws Exception {
        // Setup HTTPS backend
        httpsServer.stubFor(get(urlEqualTo("/test1")).willReturn(aResponse().withStatus(200)));
        httpsServer.stubFor(get(urlEqualTo("/test2")).willReturn(aResponse().withStatus(200)));
        httpsServer.stubFor(get(urlEqualTo("/test3")).willReturn(aResponse().withStatus(200)));

        // Make multiple HTTPS requests
        for (int i = 1; i <= 3; i++) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            "https://localhost:"
                                                    + HTTPS_BACKEND_PORT
                                                    + "/test"
                                                    + i))
                            .GET()
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }

        // Verify all requests were received
        httpsServer.verify(getRequestedFor(urlEqualTo("/test1")));
        httpsServer.verify(getRequestedFor(urlEqualTo("/test2")));
        httpsServer.verify(getRequestedFor(urlEqualTo("/test3")));
    }

    @Test
    @Order(4)
    @DisplayName("Should preserve HTTPS headers through tunnel")
    public void testHeaderPreservation() throws Exception {
        // Setup HTTPS backend
        httpsServer.stubFor(
                get(urlEqualTo("/headers"))
                        .withHeader("X-Custom-Header", equalTo("test-value"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("X-Response-Header", "response-value")));

        // Make HTTPS request with custom headers
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("https://localhost:" + HTTPS_BACKEND_PORT + "/headers"))
                        .header("X-Custom-Header", "test-value")
                        .GET()
                        .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Verify response
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Response-Header"))
                .isPresent()
                .hasValue("response-value");

        // Verify backend received the custom header
        httpsServer.verify(
                getRequestedFor(urlEqualTo("/headers"))
                        .withHeader("X-Custom-Header", equalTo("test-value")));
    }

    /**
     * Create an SSLContext that trusts all certificates (for testing only).
     *
     * @return SSLContext that trusts all certificates
     */
    private SSLContext createTrustAllSSLContext()
            throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAllCerts =
                new TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
                };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        return sslContext;
    }
}
