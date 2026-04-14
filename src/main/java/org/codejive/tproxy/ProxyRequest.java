package org.codejive.tproxy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Immutable representation of an HTTP request in the proxy layer. Use {@code with*()} methods to
 * create modified copies.
 *
 * <p>The body can be accessed either as a stream via {@link #bodyStream()} or as a byte array via
 * {@link #body()}. Bodies created from {@code InputStream} or {@code Supplier<InputStream>} are
 * single-use - calling {@link #bodyStream()} a second time will throw an exception. Bodies created
 * from byte arrays support multiple {@link #bodyStream()} calls.
 *
 * <p>The {@link #body()} method materializes the stream into a byte array and caches it, so
 * subsequent calls return the same cached array.
 */
public class ProxyRequest {
    private final String method;
    private final URI uri;
    private final Headers headers;
    private final Supplier<InputStream> bodySupplier;
    private final boolean isByteArrayBased;
    private final boolean isSupplierBased;
    private boolean streamConsumed = false;
    private byte[] cachedBody = null;

    /**
     * Create a request with a byte array body. The body can be read multiple times.
     *
     * @param method the HTTP method
     * @param uri the request URI
     * @param headers the request headers
     * @param body the request body as a byte array
     * @return a new ProxyRequest
     */
    public static ProxyRequest fromBytes(String method, URI uri, Headers headers, byte[] body) {
        byte[] bodyBytes = body != null ? body : new byte[0];
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(bodyBytes);
        return new ProxyRequest(method, uri, headers, supplier, true, false, bodyBytes);
    }

    /**
     * Create a request with a streaming body from an InputStream. The stream can only be read once.
     *
     * @param method the HTTP method
     * @param uri the request URI
     * @param headers the request headers
     * @param bodyStream the request body as an InputStream (single-use)
     * @return a new ProxyRequest
     */
    public static ProxyRequest fromStream(
            String method, URI uri, Headers headers, InputStream bodyStream) {
        Supplier<InputStream> supplier =
                bodyStream != null ? () -> bodyStream : () -> new ByteArrayInputStream(new byte[0]);
        return new ProxyRequest(method, uri, headers, supplier, false, false, null);
    }

    /**
     * Create a request with a body supplier that provides re-readable streams.
     *
     * @param method the HTTP method
     * @param uri the request URI
     * @param headers the request headers
     * @param bodySupplier supplier that provides a fresh InputStream on each call
     * @return a new ProxyRequest
     */
    public static ProxyRequest fromSupplier(
            String method, URI uri, Headers headers, Supplier<InputStream> bodySupplier) {
        Supplier<InputStream> supplier =
                bodySupplier != null ? bodySupplier : () -> new ByteArrayInputStream(new byte[0]);
        return new ProxyRequest(method, uri, headers, supplier, false, true, null);
    }

    /**
     * Main constructor used by factory methods.
     *
     * @param method the HTTP method
     * @param uri the request URI
     * @param headers the request headers
     * @param bodySupplier supplier that provides the request body stream
     * @param isByteArrayBased whether the body is backed by a byte array
     * @param isSupplierBased whether the body supplier can be called multiple times
     * @param cachedBody optional cached body bytes (for byte array-based bodies)
     */
    private ProxyRequest(
            String method,
            URI uri,
            Headers headers,
            Supplier<InputStream> bodySupplier,
            boolean isByteArrayBased,
            boolean isSupplierBased,
            byte[] cachedBody) {
        this.method = Objects.requireNonNull(method, "method cannot be null");
        this.uri = Objects.requireNonNull(uri, "uri cannot be null");
        this.headers = headers != null ? headers : Headers.empty();
        this.bodySupplier = bodySupplier;
        this.isByteArrayBased = isByteArrayBased;
        this.isSupplierBased = isSupplierBased;
        this.cachedBody = cachedBody;
    }

    /** Copy constructor for wither methods. */
    private ProxyRequest(
            String method,
            URI uri,
            Headers headers,
            Supplier<InputStream> bodySupplier,
            boolean isByteArrayBased,
            boolean isSupplierBased,
            boolean streamConsumed,
            byte[] cachedBody) {
        this.method = method;
        this.uri = uri;
        this.headers = headers;
        this.bodySupplier = bodySupplier;
        this.isByteArrayBased = isByteArrayBased;
        this.isSupplierBased = isSupplierBased;
        this.streamConsumed = streamConsumed;
        this.cachedBody = cachedBody;
    }

    public String method() {
        return method;
    }

    public URI uri() {
        return uri;
    }

    public Headers headers() {
        return headers;
    }

    /**
     * Get the request body as an InputStream.
     *
     * <p>For byte array-based bodies, this method can be called multiple times and returns a new
     * {@code ByteArrayInputStream} on each call.
     *
     * <p>For supplier-based bodies, this method can be called multiple times and the supplier
     * provides a fresh InputStream on each call.
     *
     * <p>For stream-based bodies (created from {@code InputStream}), this method can only be called
     * once unless {@link #body()} has been called to materialize it. Subsequent calls will throw
     * {@code IllegalStateException}.
     *
     * @return an InputStream containing the request body
     * @throws IllegalStateException if called more than once on a stream-based body
     */
    public InputStream bodyStream() {
        // If body has been materialized, always return from cached
        if (cachedBody != null) {
            return new ByteArrayInputStream(cachedBody);
        }

        // Supplier-based bodies are repeatable
        if (isSupplierBased) {
            return bodySupplier.get();
        }

        // Stream-based bodies are single-use
        if (!isByteArrayBased && streamConsumed) {
            throw new IllegalStateException(
                    "Body stream already consumed. Create a new ProxyRequest with a re-readable"
                            + " body source.");
        }
        if (!isByteArrayBased) {
            streamConsumed = true;
        }
        return bodySupplier.get();
    }

    /**
     * Get the request body as a byte array. This method materializes the stream if not already
     * cached, and returns the cached result on subsequent calls.
     *
     * <p>After calling this method, the request becomes byte-array-based, allowing multiple
     * bodyStream() calls.
     *
     * @return the request body as a byte array
     * @throws RuntimeException if an I/O error occurs reading the stream
     */
    public byte[] body() {
        if (cachedBody == null) {
            try {
                cachedBody = bodyStream().readAllBytes();
            } catch (IOException e) {
                throw new RuntimeException("Failed to read request body", e);
            }
        }
        return cachedBody;
    }

    public ProxyRequest withMethod(String method) {
        return new ProxyRequest(
                method,
                uri,
                headers,
                bodySupplier,
                isByteArrayBased,
                isSupplierBased,
                streamConsumed,
                cachedBody);
    }

    public ProxyRequest withUri(URI uri) {
        return new ProxyRequest(
                method,
                uri,
                headers,
                bodySupplier,
                isByteArrayBased,
                isSupplierBased,
                streamConsumed,
                cachedBody);
    }

    public ProxyRequest withHeaders(Headers headers) {
        return new ProxyRequest(
                method,
                uri,
                headers,
                bodySupplier,
                isByteArrayBased,
                isSupplierBased,
                streamConsumed,
                cachedBody);
    }

    public ProxyRequest withHeader(String name, String value) {
        return new ProxyRequest(
                method,
                uri,
                headers.with(name, value),
                bodySupplier,
                isByteArrayBased,
                isSupplierBased,
                streamConsumed,
                cachedBody);
    }

    /**
     * Create a new request with a byte array body.
     *
     * @param body the new body
     * @return a new ProxyRequest
     */
    public ProxyRequest withBody(byte[] body) {
        return ProxyRequest.fromBytes(method, uri, headers, body);
    }

    /**
     * Create a new request with a streaming body.
     *
     * @param bodyStream the new body stream
     * @return a new ProxyRequest
     */
    public ProxyRequest withBody(InputStream bodyStream) {
        return ProxyRequest.fromStream(method, uri, headers, bodyStream);
    }

    /**
     * Create a new request with a body supplier.
     *
     * @param bodySupplier supplier that provides a fresh InputStream on each call
     * @return a new ProxyRequest
     */
    public ProxyRequest withBody(Supplier<InputStream> bodySupplier) {
        return ProxyRequest.fromSupplier(method, uri, headers, bodySupplier);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProxyRequest that = (ProxyRequest) o;
        return Objects.equals(method, that.method)
                && Objects.equals(uri, that.uri)
                && Objects.equals(headers, that.headers)
                && java.util.Arrays.equals(body(), that.body());
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(method, uri, headers);
        result = 31 * result + java.util.Arrays.hashCode(body());
        return result;
    }

    @Override
    public String toString() {
        return "ProxyRequest{"
                + "method='"
                + method
                + '\''
                + ", uri="
                + uri
                + ", headers="
                + headers
                + ", bodyLength="
                + (cachedBody != null ? cachedBody.length : "stream")
                + '}';
    }
}
