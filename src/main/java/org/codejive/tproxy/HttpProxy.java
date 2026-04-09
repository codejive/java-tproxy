package org.codejive.tproxy;

import java.io.IOException;
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
    private boolean running = false;
    private int port;

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

        // TODO: Implement actual HTTP client call
        // For now, return a mock response
        return new ProxyResponse(
                200,
                Headers.of("Content-Type", "text/plain"),
                "Mock response from proxy".getBytes());
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
        this.running = true;
        logger.info("Proxy server started on port {}", port);

        // TODO: Implement actual server startup
    }

    /** Stop the proxy server. */
    public void stop() {
        if (running) {
            running = false;
            logger.info("Proxy server stopped");

            // TODO: Implement actual server shutdown
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
