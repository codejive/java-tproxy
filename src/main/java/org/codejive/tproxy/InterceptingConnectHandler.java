package org.codejive.tproxy;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.KeyStore;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for intercepting HTTPS connections via the CONNECT method.
 *
 * <p>Unlike a standard CONNECT handler that passes through encrypted traffic, this handler decrypts
 * HTTPS traffic by generating certificates on-the-fly, allowing interceptors to inspect and modify
 * HTTPS requests and responses.
 */
public class InterceptingConnectHandler extends AbstractHandler {
    private static final Logger logger = LoggerFactory.getLogger(InterceptingConnectHandler.class);

    private final CertificateAuthority certificateAuthority;

    @SuppressWarnings("unused") // Will be used when HTTPS interception is fully implemented
    private final HttpProxy httpProxy;

    /**
     * Create a new intercepting CONNECT handler.
     *
     * @param certificateAuthority CA for generating server certificates
     * @param httpProxy HTTP proxy for processing requests through interceptors
     */
    public InterceptingConnectHandler(
            CertificateAuthority certificateAuthority, HttpProxy httpProxy) {
        this.certificateAuthority = certificateAuthority;
        this.httpProxy = httpProxy;
    }

    @Override
    public void handle(
            String target,
            Request baseRequest,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException, ServletException {

        if (!"CONNECT".equalsIgnoreCase(request.getMethod())) {
            // Not a CONNECT request, let it pass to the next handler
            return;
        }

        baseRequest.setHandled(true);

        // Parse target hostname and port from the request URI
        String hostPort = request.getRequestURI();
        logger.debug("CONNECT request for: {}", hostPort);

        String[] parts = hostPort.split(":");
        if (parts.length != 2) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid CONNECT target");
            return;
        }

        String hostname = parts[0];
        int port;
        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid port number");
            return;
        }

        try {
            // Generate a certificate for this hostname
            logger.debug("Generating certificate for: {}:{}", hostname, port);
            @SuppressWarnings("unused") // Will be used when socket-level SSL is implemented
            KeyStore serverKeyStore = certificateAuthority.generateServerCertificate(hostname);

            // TODO: Implement SSL interception
            // For now, return 501 Not Implemented
            // The full implementation will:
            // 1. Send 200 Connection Established
            // 2. Upgrade the client connection to SSL using the generated certificate
            // 3. Read HTTP requests from the SSL socket
            // 4. Forward to HttpProxy for processing
            // 5. Connect to target server over HTTPS
            // 6. Return responses to client

            response.sendError(
                    HttpServletResponse.SC_NOT_IMPLEMENTED,
                    "HTTPS interception not yet fully implemented");

        } catch (Exception e) {
            logger.error("Error handling CONNECT for {}", hostPort, e);
            response.sendError(
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error establishing tunnel");
        }
    }
}
