package org.codejive.tproxy;

import jakarta.servlet.http.HttpServletRequest;
import java.io.Closeable;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTPS CONNECT handler that redirects encrypted traffic through a local SSL-terminating connector
 * for Man-in-the-Middle interception.
 *
 * <p>Extends Jetty's {@link ConnectHandler} which handles the CONNECT protocol (sending "200
 * Connection Established" and setting up bidirectional tunneling). The only override redirects the
 * tunnel's server endpoint to a local MITM connector that terminates SSL with dynamically generated
 * certificates. After SSL termination, decrypted HTTP requests flow through the normal handler
 * chain.
 */
public class InterceptingConnectHandler extends ConnectHandler {
    private static final Logger logger = LoggerFactory.getLogger(InterceptingConnectHandler.class);

    private final ServerConnector mitmConnector;

    /**
     * Create a new intercepting CONNECT handler.
     *
     * @param mitmConnector the local SSL connector that terminates HTTPS with generated certs
     */
    public InterceptingConnectHandler(ServerConnector mitmConnector) {
        this.mitmConnector = mitmConnector;
    }

    @Override
    protected void connectToServer(
            HttpServletRequest request, String host, int port, Promise<SocketChannel> promise) {
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open();
            channel.socket().setTcpNoDelay(true);
            channel.configureBlocking(false);

            String mitmHost =
                    mitmConnector.getHost() != null ? mitmConnector.getHost() : "127.0.0.1";
            int mitmPort = mitmConnector.getLocalPort();
            InetSocketAddress address = newConnectAddress(mitmHost, mitmPort);

            logger.debug(
                    "Redirecting CONNECT {}:{} to MITM connector at {}:{}",
                    host,
                    port,
                    mitmHost,
                    mitmPort);

            channel.connect(address);
            promise.succeeded(channel);
        } catch (Throwable x) {
            closeSafely(channel);
            promise.failed(x);
        }
    }

    private void closeSafely(Closeable closeable) {
        try {
            if (closeable != null) closeable.close();
        } catch (Throwable x) {
            // Ignore
        }
    }
}
