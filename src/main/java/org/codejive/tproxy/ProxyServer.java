package org.codejive.tproxy;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
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
    private final boolean httpsInterceptionEnabled;
    private final CertificateAuthority certificateAuthority;
    private Server server;

    /**
     * Create a new proxy server.
     *
     * @param httpProxy the HTTP proxy to delegate requests to
     * @param port the port to listen on
     * @param httpsInterceptionEnabled whether to enable HTTPS interception
     * @param certificateAuthority the certificate authority (required if interception enabled)
     */
    ProxyServer(
            HttpProxy httpProxy,
            int port,
            boolean httpsInterceptionEnabled,
            CertificateAuthority certificateAuthority) {
        this.httpProxy = httpProxy;
        this.port = port;
        this.httpsInterceptionEnabled = httpsInterceptionEnabled;
        this.certificateAuthority = certificateAuthority;

        if (httpsInterceptionEnabled && certificateAuthority == null) {
            throw new IllegalArgumentException(
                    "CertificateAuthority is required when HTTPS interception is enabled");
        }
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

            // Create servlet context for regular HTTP requests
            ServletContextHandler servletContext =
                    new ServletContextHandler(ServletContextHandler.SESSIONS);
            servletContext.setContextPath("/");
            ServletHolder servletHolder = new ServletHolder(new ProxyServlet());
            servletContext.addServlet(servletHolder, "/*");

            // Create CONNECT handler based on interception mode
            if (httpsInterceptionEnabled) {
                // Create SSL context with certificate-generating key manager
                SSLContext sslContext = createMitmSslContext();

                SslContextFactory.Server sslFactory = new SslContextFactory.Server();
                sslFactory.setSslContext(sslContext);
                sslFactory.setSniRequired(false);

                // Configure HTTP after SSL termination
                HttpConfiguration httpConfig = new HttpConfiguration();
                SecureRequestCustomizer secureCustomizer = new SecureRequestCustomizer();
                secureCustomizer.setSniHostCheck(false);
                httpConfig.addCustomizer(secureCustomizer);

                // Create MITM connector: SSL termination → HTTP parsing
                SslConnectionFactory ssl = new SslConnectionFactory(sslFactory, "http/1.1");
                HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);
                ServerConnector mitmConnector = new ServerConnector(server, ssl, http);
                mitmConnector.setPort(0); // Random available port
                mitmConnector.setHost("127.0.0.1");
                server.addConnector(mitmConnector);

                // Use intercepting handler that redirects CONNECT tunnels to MITM connector
                logger.info("Using InterceptingConnectHandler for HTTPS MITM");
                InterceptingConnectHandler connectHandler =
                        new InterceptingConnectHandler(mitmConnector);
                connectHandler.setHandler(servletContext);
                server.setHandler(connectHandler);
            } else {
                // Use standard pass-through handler
                logger.info("Using standard ConnectHandler for HTTPS pass-through");
                ConnectHandler connectHandler = new ConnectHandler();
                connectHandler.setConnectTimeout(30000); // 30 seconds
                connectHandler.setHandler(servletContext);
                server.setHandler(connectHandler);
            }

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
    boolean running() {
        return server != null && server.isRunning();
    }

    /**
     * Create an SSLContext with a key manager that generates certificates on-the-fly.
     *
     * @return the configured SSLContext
     * @throws IOException if SSL context creation fails
     */
    private SSLContext createMitmSslContext() throws IOException {
        try {
            CertificateGeneratingKeyManager keyManager =
                    new CertificateGeneratingKeyManager(certificateAuthority);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(new KeyManager[] {keyManager}, null, null);
            return sslContext;
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to create MITM SSL context", e);
        }
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

            // Read body as stream (servlet may buffer internally)
            return ProxyRequest.fromStream(method, uri, headers, req.getInputStream());
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
            resp.setStatus(proxyResponse.statusCode());

            // Set headers
            proxyResponse
                    .headers()
                    .forEach(
                            entry -> {
                                String name = entry.getKey();
                                for (String value : entry.getValue()) {
                                    resp.addHeader(name, value);
                                }
                            });

            // Stream body
            try (InputStream bodyStream = proxyResponse.bodyStream()) {
                bodyStream.transferTo(resp.getOutputStream());
            }
        }
    }
}
