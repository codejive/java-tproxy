package org.codejive.tproxy;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    private final HttpClient httpClient;
    private ProxyServer proxyServer;
    private boolean running = false;
    private int port;

    public HttpProxy() {
        this.httpClient =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1) // Start with HTTP/1.1
                        .connectTimeout(Duration.ofSeconds(30))
                        .followRedirects(HttpClient.Redirect.NEVER) // Proxy handles redirects
                        .build();
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
     * Execute a request through the proxy. The request will pass through all configured
     * interceptors.
     *
     * @param request the request to execute
     * @return the response
     * @throws IOException if an I/O error occurs
     */
    public ProxyResponse execute(ProxyRequest request) throws IOException {
        logger.info("Executing request: {} {}", request.getMethod(), request.getUri());

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
        logger.debug("Executing actual HTTP request: {} {}", request.getMethod(), request.getUri());

        try {
            // Filter headers for forwarding
            Headers filteredHeaders = HeaderFilter.filterForForwarding(request.getHeaders());

            // Build HttpRequest
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder()
                            .uri(request.getUri())
                            .method(
                                    request.getMethod(),
                                    request.getBody().length > 0
                                            ? BodyPublishers.ofByteArray(request.getBody())
                                            : BodyPublishers.noBody());

            // Add headers (HttpClient will set Host automatically, so skip it)
            filteredHeaders.forEach(
                    entry -> {
                        String name = entry.getKey();
                        // Skip Host header - HttpClient sets it automatically from URI
                        if (!"Host".equalsIgnoreCase(name)) {
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
            logger.error("Request interrupted: {} {}", request.getMethod(), request.getUri(), e);
            return new ProxyResponse(
                    503,
                    Headers.of("Content-Type", "text/plain"),
                    "Service Unavailable: Request interrupted".getBytes());
        } catch (IOException e) {
            logger.error(
                    "Error executing request: {} {}", request.getMethod(), request.getUri(), e);
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

        // Create and start the proxy server
        proxyServer = new ProxyServer(this, port);
        proxyServer.start();

        this.running = true;
        logger.info("Proxy server started on port {}", port);
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
    public boolean isRunning() {
        return running;
    }

    /**
     * Get the port the proxy is running on.
     *
     * @return the port number
     */
    public int getPort() {
        return port;
    }
}
