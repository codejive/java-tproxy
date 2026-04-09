package org.codejive.tproxy;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.FileInputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for HTTPS Man-in-the-Middle interception.
 *
 * <p>Tests that the proxy can decrypt HTTPS traffic, allowing interceptors to inspect and modify
 * requests and responses.
 */
@DisplayName("HTTPS Interception (MITM) Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HttpsInterceptionTest {

    private static final int PROXY_PORT = 8891;
    private static final int HTTPS_BACKEND_PORT = 9444;

    @TempDir Path tempDir;

    private HttpProxy proxy;
    private WireMockServer httpsServer;
    private HttpClient httpClient;

    @BeforeEach
    public void setUp() throws Exception {
        // Start HTTPS WireMock server on fixed port
        httpsServer =
                new WireMockServer(
                        options().httpsPort(HTTPS_BACKEND_PORT).bindAddress("localhost"));
        httpsServer.start();

        // Create proxy with HTTPS interception enabled
        proxy = new HttpProxy();
        proxy.caStorageDir(tempDir);
        proxy.enableHttpsInterception(); // Enable MITM mode
        proxy.start(PROXY_PORT);

        // Wait for CA certificate file to be generated
        Thread.sleep(500);

        // Create HTTP client configured to use the proxy and trust the proxy's CA certificate
        SSLContext sslContext = createTrustProxyCASSLContext(tempDir);

        httpClient =
                HttpClient.newBuilder()
                        .proxy(
                                ProxySelector.of(
                                        new java.net.InetSocketAddress("127.0.0.1", PROXY_PORT)))
                        .sslContext(sslContext)
                        .build();

        // Wait for servers to start
        Thread.sleep(300);
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
    @DisplayName("Should intercept and inspect HTTPS GET request")
    public void testHttpsInterception() throws Exception {
        // Add an interceptor that captures the decrypted request
        AtomicReference<String> capturedUri = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();

        proxy.addInterceptor(
                (request, chain) -> {
                    // We can see the decrypted HTTPS request!
                    capturedUri.set(request.uri().toString());
                    capturedBody.set(new String(request.body()));
                    return chain.proceed(request);
                });

        // Setup HTTPS backend
        httpsServer.stubFor(
                get(urlEqualTo("/api/data"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"message\":\"secret data\"}")));

        // Make HTTPS request through proxy
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("https://localhost:" + HTTPS_BACKEND_PORT + "/api/data"))
                        .GET()
                        .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Verify response
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("secret data");

        // Verify interceptor saw the decrypted request
        assertThat(capturedUri.get())
                .isEqualTo("https://localhost:" + HTTPS_BACKEND_PORT + "/api/data");
        assertThat(capturedBody.get()).isEmpty(); // GET requests have no body

        // Verify backend received the request
        httpsServer.verify(getRequestedFor(urlEqualTo("/api/data")));
    }

    @Test
    @Order(2)
    @DisplayName("Should intercept and modify HTTPS POST request")
    public void testHttpsInterceptionWithModification() throws Exception {
        // Add an interceptor that modifies the request body
        proxy.addInterceptor(
                (request, chain) -> {
                    if ("POST".equals(request.method())) {
                        // Modify the request body
                        String originalBody = new String(request.body());
                        String modifiedBody = originalBody.replace("client", "intercepted-client");
                        ProxyRequest modifiedRequest = request.withBody(modifiedBody.getBytes());
                        return chain.proceed(modifiedRequest);
                    }
                    return chain.proceed(request);
                });

        // Setup HTTPS backend expecting the modified body
        httpsServer.stubFor(
                post(urlEqualTo("/api/submit"))
                        .withRequestBody(equalToJson("{\"from\":\"intercepted-client\"}"))
                        .willReturn(aResponse().withStatus(201).withBody("{\"id\":\"12345\"}")));

        // Make HTTPS POST request with original body
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("https://localhost:" + HTTPS_BACKEND_PORT + "/api/submit"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"from\":\"client\"}"))
                        .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Verify response
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("12345");

        // Verify backend received the MODIFIED request
        httpsServer.verify(
                postRequestedFor(urlEqualTo("/api/submit"))
                        .withRequestBody(equalToJson("{\"from\":\"intercepted-client\"}")));
    }

    @Test
    @Order(3)
    @DisplayName("Should intercept and modify HTTPS response")
    public void testHttpsResponseModification() throws Exception {
        // Add an interceptor that modifies the response
        proxy.addInterceptor(
                (request, chain) -> {
                    ProxyResponse response = chain.proceed(request);
                    // Modify the response body
                    String originalBody = new String(response.body());
                    String modifiedBody = originalBody.replace("server", "proxy");
                    return response.withBody(modifiedBody.getBytes());
                });

        // Setup HTTPS backend
        httpsServer.stubFor(
                get(urlEqualTo("/api/message"))
                        .willReturn(aResponse().withStatus(200).withBody("{\"from\":\"server\"}")));

        // Make HTTPS GET request
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("https://localhost:" + HTTPS_BACKEND_PORT + "/api/message"))
                        .GET()
                        .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Verify response
        assertThat(response.statusCode()).isEqualTo(200);
        // Client should see the MODIFIED response
        assertThat(response.body()).isEqualTo("{\"from\":\"proxy\"}");

        // Verify backend sent the original response
        httpsServer.verify(getRequestedFor(urlEqualTo("/api/message")));
    }

    /**
     * Create an SSLContext that trusts the proxy's CA certificate.
     *
     * @return SSL context
     */
    private SSLContext createTrustProxyCASSLContext(Path caDir) throws Exception {
        // Load the proxy's CA certificate
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate caCert;

        try (FileInputStream fis = new FileInputStream(caDir.resolve("tproxy-ca.crt").toFile())) {
            caCert = cf.generateCertificate(fis);
        }

        // Create a KeyStore containing the trusted CA
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setCertificateEntry("tproxy-ca", caCert);

        // Create a TrustManager that trusts the CA
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        // Create SSL context
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);

        return sslContext;
    }
}
