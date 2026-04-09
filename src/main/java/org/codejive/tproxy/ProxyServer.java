package org.codejive.tproxy;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.Executors;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embedded Jetty server that handles incoming HTTP requests and delegates to HttpProxy for
 * processing through the interceptor chain.
 */
class ProxyServer {
    private static final Logger logger = LoggerFactory.getLogger(ProxyServer.class);

    private final HttpProxy httpProxy;
    private final int port;
    private Server server;

    /**
     * Create a new proxy server.
     *
     * @param httpProxy the HTTP proxy to delegate requests to
     * @param port the port to listen on
     */
    ProxyServer(HttpProxy httpProxy, int port) {
        this.httpProxy = httpProxy;
        this.port = port;
    }

    /**
     * Start the proxy server.
     *
     * @throws IOException if the server cannot be started
     */
    void start() throws IOException {
        try {
            // Create thread pool with virtual threads for Java 21
            QueuedThreadPool threadPool = new QueuedThreadPool();
            threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
            threadPool.setName("proxy-server");

            server = new Server(threadPool);

            // Create HTTP connector
            ServerConnector connector = new ServerConnector(server);
            connector.setPort(port);
            connector.setHost("127.0.0.1"); // Bind to localhost for testing
            server.addConnector(connector);

            // Create CONNECT handler for HTTPS tunneling
            ConnectHandler connectHandler = new ConnectHandler();
            connectHandler.setConnectTimeout(30000); // 30 seconds

            // Create servlet context for regular HTTP requests
            ServletContextHandler servletContext =
                    new ServletContextHandler(ServletContextHandler.SESSIONS);
            servletContext.setContextPath("/");
            ServletHolder servletHolder = new ServletHolder(new ProxyServlet());
            servletContext.addServlet(servletHolder, "/*");

            // Connect handler wraps servlet context
            connectHandler.setHandler(servletContext);

            // Set handler
            server.setHandler(connectHandler);

            // Start the server
            server.start();
            logger.info("Proxy server started on port {}", port);

        } catch (Exception e) {
            throw new IOException("Failed to start proxy server on port " + port, e);
        }
    }

    /** Stop the proxy server. */
    void stop() {
        if (server != null) {
            try {
                server.stop();
                logger.info("Proxy server stopped");
            } catch (Exception e) {
                logger.error("Error stopping proxy server", e);
            }
        }
    }

    /**
     * Check if the server is running.
     *
     * @return true if running, false otherwise
     */
    boolean isRunning() {
        return server != null && server.isRunning();
    }

    /** Servlet that handles all HTTP requests and delegates to the HttpProxy. */
    private class ProxyServlet extends HttpServlet {

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            String method = req.getMethod();
            logger.debug("Received {} request: {}", method, req.getRequestURL());

            // CONNECT is handled by ConnectHandler, so we should not see it here
            if ("CONNECT".equalsIgnoreCase(method)) {
                resp.sendError(
                        HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                        "CONNECT should be handled by ConnectHandler");
                return;
            }

            try {
                // Build the target URI
                URI uri = buildTargetUri(req);

                // Convert servlet request to ProxyRequest
                ProxyRequest proxyRequest = convertRequest(req, method, uri);

                // Execute through the proxy (interceptor chain)
                ProxyResponse proxyResponse = httpProxy.execute(proxyRequest);

                // Convert ProxyResponse to servlet response
                convertResponse(proxyResponse, resp);

            } catch (Exception e) {
                logger.error("Error processing request", e);
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                resp.setContentType("text/plain");
                resp.getWriter().write("Internal Server Error: " + e.getMessage());
            }
        }

        /**
         * Build the target URI from the servlet request.
         *
         * @param req the servlet request
         * @return the target URI
         * @throws URISyntaxException if the URI is malformed
         */
        private URI buildTargetUri(HttpServletRequest req) throws URISyntaxException {
            String requestUrl = req.getRequestURL().toString();
            String queryString = req.getQueryString();

            // For absolute URI requests (typical proxy scenario)
            if (requestUrl.startsWith("http://") || requestUrl.startsWith("https://")) {
                if (queryString != null && !queryString.isEmpty()) {
                    return new URI(requestUrl + "?" + queryString);
                }
                return new URI(requestUrl);
            }

            // For relative URI requests, construct from parts
            String scheme = req.getScheme();
            String host = req.getServerName();
            int serverPort = req.getServerPort();
            String path = req.getRequestURI();

            StringBuilder uriBuilder = new StringBuilder();
            uriBuilder.append(scheme).append("://").append(host);

            // Add port if not default
            if (("http".equals(scheme) && serverPort != 80)
                    || ("https".equals(scheme) && serverPort != 443)) {
                uriBuilder.append(":").append(serverPort);
            }

            uriBuilder.append(path);

            if (queryString != null && !queryString.isEmpty()) {
                uriBuilder.append("?").append(queryString);
            }

            return new URI(uriBuilder.toString());
        }

        /**
         * Convert servlet request to ProxyRequest.
         *
         * @param req the servlet request
         * @param method the HTTP method
         * @param uri the target URI
         * @return the ProxyRequest
         * @throws IOException if reading the body fails
         */
        private ProxyRequest convertRequest(HttpServletRequest req, String method, URI uri)
                throws IOException {

            // Extract headers
            Map<String, List<String>> headerMap = new LinkedHashMap<>();
            Enumeration<String> headerNames = req.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                List<String> values = new ArrayList<>();
                Enumeration<String> headerValues = req.getHeaders(name);
                while (headerValues.hasMoreElements()) {
                    values.add(headerValues.nextElement());
                }
                headerMap.put(name, values);
            }
            Headers headers = Headers.of(headerMap);

            // Read body
            byte[] body = req.getInputStream().readAllBytes();

            return new ProxyRequest(method, uri, headers, body);
        }

        /**
         * Convert ProxyResponse to servlet response.
         *
         * @param proxyResponse the proxy response
         * @param resp the servlet response
         * @throws IOException if writing fails
         */
        private void convertResponse(ProxyResponse proxyResponse, HttpServletResponse resp)
                throws IOException {

            // Set status code
            resp.setStatus(proxyResponse.getStatusCode());

            // Set headers
            proxyResponse
                    .getHeaders()
                    .forEach(
                            entry -> {
                                String name = entry.getKey();
                                for (String value : entry.getValue()) {
                                    resp.addHeader(name, value);
                                }
                            });

            // Write body
            byte[] body = proxyResponse.getBody();
            if (body != null && body.length > 0) {
                resp.getOutputStream().write(body);
            }
        }
    }
}
