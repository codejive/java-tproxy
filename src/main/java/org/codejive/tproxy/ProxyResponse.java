package org.codejive.tproxy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Immutable representation of an HTTP response in the proxy layer. Use {@code with*()} methods to
 * create modified copies.
 *
 * <p>The body can be accessed either as a stream via {@link #bodyStream()} or as a byte array via
 * {@link #body()}. Bodies created from {@code InputStream} or {@code Supplier<InputStream>} are
 * single-use - calling {@link #bodyStream()} a second time will throw an exception. Bodies created
 * from byte arrays support multiple {@link #bodyStream()} calls.
 *
 * <p>The {@link #body()} method materializes the stream into a byte array and caches it, so
 * subsequent calls return the same cached array.
 *
 * <p><b>Important for Interceptors:</b> If your interceptor needs to read the body, you can use
 * {@link #materializeTo(Path)} to save it to a file and get a re-readable response:
 *
 * <pre>{@code
 * ProxyResponse original = chain.proceed(request);
 * Path tempFile = Files.createTempFile("proxy", ".tmp");
 * return original.materializeTo(tempFile);
 * }</pre>
 */
public class ProxyResponse {
    private final int statusCode;
    private final Headers headers;
    private final Supplier<InputStream> bodySupplier;
    private final boolean isByteArrayBased;
    private final boolean isSupplierBased;
    private boolean streamConsumed = false;
    private byte[] cachedBody = null;

    /**
     * Create a response with a byte array body. The body can be read multiple times.
     *
     * @param statusCode the HTTP status code
     * @param headers the response headers
     * @param body the response body as a byte array
     * @return a new ProxyResponse
     */
    public static ProxyResponse fromBytes(int statusCode, Headers headers, byte[] body) {
        byte[] bodyBytes = body != null ? body : new byte[0];
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(bodyBytes);
        return new ProxyResponse(statusCode, headers, supplier, true, false, bodyBytes);
    }

    /**
     * Create a response with a streaming body from an InputStream. The stream can only be read
     * once.
     *
     * @param statusCode the HTTP status code
     * @param headers the response headers
     * @param bodyStream the response body as an InputStream (single-use)
     * @return a new ProxyResponse
     */
    public static ProxyResponse fromStream(
            int statusCode, Headers headers, InputStream bodyStream) {
        Supplier<InputStream> supplier =
                bodyStream != null ? () -> bodyStream : () -> new ByteArrayInputStream(new byte[0]);
        return new ProxyResponse(statusCode, headers, supplier, false, false, null);
    }

    /**
     * Create a response with a body supplier that provides re-readable streams.
     *
     * @param statusCode the HTTP status code
     * @param headers the response headers
     * @param bodySupplier supplier that provides a fresh InputStream on each call
     * @return a new ProxyResponse
     */
    public static ProxyResponse fromSupplier(
            int statusCode, Headers headers, Supplier<InputStream> bodySupplier) {
        Supplier<InputStream> supplier =
                bodySupplier != null ? bodySupplier : () -> new ByteArrayInputStream(new byte[0]);
        return new ProxyResponse(statusCode, headers, supplier, false, true, null);
    }

    /**
     * Main constructor used by factory methods.
     *
     * @param statusCode the HTTP status code
     * @param headers the response headers
     * @param bodySupplier supplier that provides the response body stream
     * @param isByteArrayBased whether the body is backed by a byte array
     * @param isSupplierBased whether the body supplier can be called multiple times
     * @param cachedBody optional cached body bytes (for byte array-based bodies)
     */
    private ProxyResponse(
            int statusCode,
            Headers headers,
            Supplier<InputStream> bodySupplier,
            boolean isByteArrayBased,
            boolean isSupplierBased,
            byte[] cachedBody) {
        this.statusCode = statusCode;
        this.headers = headers != null ? headers : Headers.empty();
        this.bodySupplier = bodySupplier;
        this.isByteArrayBased = isByteArrayBased;
        this.isSupplierBased = isSupplierBased;
        this.cachedBody = cachedBody;
    }

    /** Copy constructor for wither methods. */
    private ProxyResponse(
            int statusCode,
            Headers headers,
            Supplier<InputStream> bodySupplier,
            boolean isByteArrayBased,
            boolean isSupplierBased,
            boolean streamConsumed,
            byte[] cachedBody) {
        this.statusCode = statusCode;
        this.headers = headers;
        this.bodySupplier = bodySupplier;
        this.isByteArrayBased = isByteArrayBased;
        this.isSupplierBased = isSupplierBased;
        this.streamConsumed = streamConsumed;
        this.cachedBody = cachedBody;
    }

    public int statusCode() {
        return statusCode;
    }

    public Headers headers() {
        return headers;
    }

    /**
     * Get the response body as an InputStream.
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
     * @return an InputStream containing the response body
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
                    "Body stream already consumed. Create a new ProxyResponse with a re-readable"
                            + " body source.");
        }
        if (!isByteArrayBased) {
            streamConsumed = true;
        }
        return bodySupplier.get();
    }

    /**
     * Get the response body as a byte array. This method materializes the stream if not already
     * cached, and returns the cached result on subsequent calls.
     *
     * <p>After calling this method, the response becomes byte-array-based, allowing multiple
     * bodyStream() calls.
     *
     * @return the response body as a byte array
     * @throws RuntimeException if an I/O error occurs reading the stream
     */
    public byte[] body() {
        if (cachedBody == null) {
            try {
                cachedBody = bodyStream().readAllBytes();
            } catch (IOException e) {
                throw new RuntimeException("Failed to read response body", e);
            }
        }
        return cachedBody;
    }

    public ProxyResponse withStatusCode(int statusCode) {
        return new ProxyResponse(
                statusCode,
                headers,
                bodySupplier,
                isByteArrayBased,
                isSupplierBased,
                streamConsumed,
                cachedBody);
    }

    public ProxyResponse withHeaders(Headers headers) {
        return new ProxyResponse(
                statusCode,
                headers,
                bodySupplier,
                isByteArrayBased,
                isSupplierBased,
                streamConsumed,
                cachedBody);
    }

    public ProxyResponse withHeader(String name, String value) {
        return new ProxyResponse(
                statusCode,
                headers.with(name, value),
                bodySupplier,
                isByteArrayBased,
                isSupplierBased,
                streamConsumed,
                cachedBody);
    }

    /**
     * Create a new response with a byte array body.
     *
     * @param body the new body
     * @return a new ProxyResponse
     */
    public ProxyResponse withBody(byte[] body) {
        return ProxyResponse.fromBytes(statusCode, headers, body);
    }

    /**
     * Create a new response with a streaming body.
     *
     * @param bodyStream the new body stream
     * @return a new ProxyResponse
     */
    public ProxyResponse withBody(InputStream bodyStream) {
        return ProxyResponse.fromStream(statusCode, headers, bodyStream);
    }

    /**
     * Create a new response with a body supplier.
     *
     * @param bodySupplier supplier that provides a fresh InputStream on each call
     * @return a new ProxyResponse
     */
    public ProxyResponse withBody(Supplier<InputStream> bodySupplier) {
        return ProxyResponse.fromSupplier(statusCode, headers, bodySupplier);
    }

    /**
     * Materialize the response body to a file and return a new ProxyResponse with a re-readable
     * body source that reads from the file. This is useful for interceptors that need to read the
     * body multiple times.
     *
     * <p>The body stream is copied to the specified file, replacing it if it already exists. The
     * returned ProxyResponse will have a supplier-based body that opens a new InputStream from the
     * file on each call to {@link #bodyStream()}.
     *
     * @param path the file path to write the body to
     * @return a new ProxyResponse with a supplier that reads from the file
     * @throws IOException if an I/O error occurs while writing to the file
     */
    public ProxyResponse materializeTo(Path path) throws IOException {
        try (InputStream bodyStream = bodyStream()) {
            Files.copy(bodyStream, path, StandardCopyOption.REPLACE_EXISTING);
        }
        return withBody(
                () -> {
                    try {
                        return Files.newInputStream(path);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to read from " + path, e);
                    }
                });
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProxyResponse that = (ProxyResponse) o;
        return statusCode == that.statusCode
                && Objects.equals(headers, that.headers)
                && java.util.Arrays.equals(body(), that.body());
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(statusCode, headers);
        result = 31 * result + java.util.Arrays.hashCode(body());
        return result;
    }

    @Override
    public String toString() {
        return "ProxyResponse{"
                + "statusCode="
                + statusCode
                + ", headers="
                + headers
                + ", bodyLength="
                + (cachedBody != null ? cachedBody.length : "stream")
                + '}';
    }
}
