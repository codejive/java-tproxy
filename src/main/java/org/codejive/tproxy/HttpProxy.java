package org.codejive.tproxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core HTTP/HTTPS proxy implementation. This proxy is purely concerned with proxying requests and
 * has no knowledge of testing, recording, or replay functionality.
 *
 * <p>All additional functionality is implemented via interceptors.
 */
public class HttpProxy {
    private static final Logger logger = LoggerFactory.getLogger(HttpProxy.class);

    private final List<Interceptor> interceptors = new ArrayList<>();
    private HttpClient httpClient;
    private ProxyServer proxyServer;
    private CertificateAuthority certificateAuthority;
    private boolean running = false;
    private boolean httpsInterceptionEnabled = false;
    private Path caStorageDir;
    private int port;

    public HttpProxy() {
        this.httpClient = buildHttpClient(null);
    }

    /**
     * Add an interceptor to the proxy. Interceptors are invoked in the order they are added;
     * earlier interceptors are "outermost" and see both the request and the final response
     * (including short-circuited ones).
     *
     * @param interceptor the interceptor to add
     * @return this proxy instance for chaining
     */
    public HttpProxy addInterceptor(Interceptor interceptor) {
        this.interceptors.add(interceptor);
        logger.debug("Added interceptor: {}", interceptor.getClass().getSimpleName());
        return this;
    }

    /**
     * Remove all interceptors.
     *
     * @return this proxy instance for chaining
     */
    public HttpProxy clearInterceptors() {
        interceptors.clear();
        logger.debug("Cleared all interceptors");
        return this;
    }

    /**
     * Enable HTTPS interception (Man-in-the-Middle mode).
     *
     * <p>When enabled, the proxy will decrypt HTTPS traffic by generating certificates on-the-fly,
     * allowing interceptors to inspect and modify HTTPS requests and responses.
     *
     * <p>Must be called before {@link #start(int)}.
     *
     * @return this proxy instance for chaining
     * @throws IllegalStateException if the proxy is already running
     */
    public HttpProxy enableHttpsInterception() {
        if (running) {
            throw new IllegalStateException(
                    "Cannot enable HTTPS interception while proxy is running");
        }
        this.httpsInterceptionEnabled = true;
        logger.info("HTTPS interception enabled");
        return this;
    }

    /**
     * Set the directory in which the CA keystore and certificate files are stored.
     *
     * <p>Must be called before {@link #start(int)}. If not set, defaults to the current directory.
     *
     * @param storageDir the directory for CA storage
     * @return this proxy instance for chaining
     * @throws IllegalStateException if the proxy is already running
     */
    public HttpProxy caStorageDir(Path storageDir) {
        if (running) {
            throw new IllegalStateException(
                    "Cannot change CA storage directory while proxy is running");
        }
        this.caStorageDir = storageDir;
        return this;
    }

    /**
     * Disable HTTPS interception (pass-through mode).
     *
     * <p>When disabled (default), HTTPS traffic passes through encrypted without inspection.
     *
     * <p>Must be called before {@link #start(int)}.
     *
     * @return this proxy instance for chaining
     * @throws IllegalStateException if the proxy is already running
     */
    public HttpProxy disableHttpsInterception() {
        if (running) {
            throw new IllegalStateException(
                    "Cannot disable HTTPS interception while proxy is running");
        }
        this.httpsInterceptionEnabled = false;
        logger.info("HTTPS interception disabled");
        return this;
    }

    /**
     * Execute a request through the proxy. The request will pass through all configured
     * interceptors.
     *
     * @param request the request to execute
     * @return the response
     * @throws IOException if an I/O error occurs
     */
    public ProxyResponse execute(ProxyRequest request) throws IOException {
        logger.info("Executing request: {} {}", request.method(), request.uri());

        try {
            InterceptorChain chain = new InterceptorChain(interceptors, this::executeActualRequest);

            return chain.proceed(request);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Error executing request through interceptors", e);
        }
    }

    /**
     * Execute the actual HTTP request (without interceptors). This is the terminal operation in the
     * interceptor chain.
     *
     * @param request the request to execute
     * @return the response
     */
    private ProxyResponse executeActualRequest(ProxyRequest request) {
        logger.debug("Executing actual HTTP request: {} {}", request.method(), request.uri());

        try {
            // Filter headers for forwarding
            Headers filteredHeaders = HeaderFilter.filterForForwarding(request.headers());

            // Build HttpRequest
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder()
                            .uri(request.uri())
                            .method(
                                    request.method(),
                                    request.body().length > 0
                                            ? BodyPublishers.ofByteArray(request.body())
                                            : BodyPublishers.noBody());

            // Add headers (HttpClient sets Host and Content-Length automatically)
            filteredHeaders.forEach(
                    entry -> {
                        String name = entry.getKey();
                        if (!"Host".equalsIgnoreCase(name)
                                && !"Content-Length".equalsIgnoreCase(name)) {
                            entry.getValue().forEach(value -> builder.header(name, value));
                        }
                    });

            HttpRequest httpRequest = builder.build();

            // Execute request
            HttpResponse<byte[]> httpResponse =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());

            // Convert response
            Headers responseHeaders = convertResponseHeaders(httpResponse);
            return new ProxyResponse(
                    httpResponse.statusCode(), responseHeaders, httpResponse.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Request interrupted: {} {}", request.method(), request.uri(), e);
            return new ProxyResponse(
                    503,
                    Headers.of("Content-Type", "text/plain"),
                    "Service Unavailable: Request interrupted".getBytes());
        } catch (IOException e) {
            logger.error("Error executing request: {} {}", request.method(), request.uri(), e);
            return new ProxyResponse(
                    502,
                    Headers.of("Content-Type", "text/plain"),
                    ("Bad Gateway: " + e.getMessage()).getBytes());
        }
    }

    /**
     * Convert HttpResponse headers to our Headers model.
     *
     * @param httpResponse the HTTP response
     * @return converted headers
     */
    private Headers convertResponseHeaders(HttpResponse<byte[]> httpResponse) {
        return Headers.of(httpResponse.headers().map());
    }

    /**
     * Start the proxy server on the specified port.
     *
     * @param port the port to listen on
     * @throws IOException if the server cannot be started
     */
    public void start(int port) throws IOException {
        if (running) {
            throw new IllegalStateException("Proxy is already running");
        }
        this.port = port;

        // Create Certificate Authority if HTTPS interception is enabled
        if (httpsInterceptionEnabled && certificateAuthority == null) {
            logger.info("Creating Certificate Authority for HTTPS interception");
            certificateAuthority =
                    caStorageDir != null
                            ? new CertificateAuthority(caStorageDir)
                            : new CertificateAuthority();
            // Rebuild HTTP client with trust-all SSL for upstream HTTPS connections
            this.httpClient = buildHttpClient(createTrustAllSslContext());
        }

        // Create and start the proxy server
        proxyServer = new ProxyServer(this, port, httpsInterceptionEnabled, certificateAuthority);
        proxyServer.start();

        this.running = true;
        logger.info(
                "Proxy server started on port {} (HTTPS interception: {})",
                port,
                httpsInterceptionEnabled ? "enabled" : "disabled");
    }

    /** Stop the proxy server. */
    public void stop() {
        if (running) {
            if (proxyServer != null) {
                proxyServer.stop();
                proxyServer = null;
            }
            running = false;
            logger.info("Proxy server stopped");
        }
    }

    /**
     * Check if the proxy server is running.
     *
     * @return true if running, false otherwise
     */
    public boolean running() {
        return running;
    }

    /**
     * Get the port the proxy is running on.
     *
     * @return the port number
     */
    public int port() {
        return port;
    }

    /**
     * Configure an HttpClient.Builder to use this proxy.
     *
     * @param builder the HttpClient.Builder to configure
     * @return the same builder instance for chaining
     * @throws IllegalStateException if the proxy is not running
     */
    public HttpClient.Builder configureClient(HttpClient.Builder builder) {
        if (!running) {
            throw new IllegalStateException("Proxy must be running before configuring clients");
        }
        return builder.proxy(ProxySelector.of(new InetSocketAddress("localhost", port)));
    }

    private static HttpClient buildHttpClient(SSLContext sslContext) {
        HttpClient.Builder builder =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(30))
                        .followRedirects(HttpClient.Redirect.NEVER);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        return builder.build();
    }

    private static SSLContext createTrustAllSslContext() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(
                    null,
                    new TrustManager[] {
                        new X509TrustManager() {
                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }

                            @Override
                            public void checkClientTrusted(
                                    X509Certificate[] certs, String authType) {}

                            @Override
                            public void checkServerTrusted(
                                    X509Certificate[] certs, String authType) {}
                        }
                    },
                    null);
            return sslContext;
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to create trust-all SSL context", e);
        }
    }
}
