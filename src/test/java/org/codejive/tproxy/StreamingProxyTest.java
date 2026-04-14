package org.codejive.tproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for streaming body support in ProxyRequest and ProxyResponse. */
@DisplayName("Streaming Proxy Tests")
public class StreamingProxyTest {

    @Test
    @DisplayName("ProxyResponse with byte array body supports multiple bodyStream() calls")
    public void testByteArrayBodyMultipleStreams() throws IOException {
        byte[] body = "test data".getBytes();
        ProxyResponse response = ProxyResponse.fromBytes(200, Headers.empty(), body);

        // First stream read
        String content1 = new String(response.bodyStream().readAllBytes());
        assertThat(content1).isEqualTo("test data");

        // Second stream read should work
        String content2 = new String(response.bodyStream().readAllBytes());
        assertThat(content2).isEqualTo("test data");

        // body() should also work
        assertThat(new String(response.body())).isEqualTo("test data");
    }

    @Test
    @DisplayName("ProxyResponse with stream body is single-use")
    public void testStreamBodySingleUse() throws IOException {
        InputStream stream = new ByteArrayInputStream("test data".getBytes());
        ProxyResponse response = ProxyResponse.fromStream(200, Headers.empty(), stream);

        // First bodyStream() call works
        String content = new String(response.bodyStream().readAllBytes());
        assertThat(content).isEqualTo("test data");

        // Second bodyStream() call should throw
        assertThatThrownBy(() -> response.bodyStream())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already consumed");
    }

    @Test
    @DisplayName("ProxyResponse body() materializes stream and enables multiple bodyStream() calls")
    public void testBodyMaterializesStream() throws IOException {
        InputStream stream = new ByteArrayInputStream("test data".getBytes());
        ProxyResponse response = ProxyResponse.fromStream(200, Headers.empty(), stream);

        // Call body() to materialize
        byte[] body = response.body();
        assertThat(new String(body)).isEqualTo("test data");

        // Now bodyStream() should work multiple times
        String content1 = new String(response.bodyStream().readAllBytes());
        assertThat(content1).isEqualTo("test data");

        String content2 = new String(response.bodyStream().readAllBytes());
        assertThat(content2).isEqualTo("test data");

        // body() should return cached result
        assertThat(response.body()).isSameAs(body);
    }

    @Test
    @DisplayName("ProxyRequest with byte array body supports multiple bodyStream() calls")
    public void testRequestByteArrayBodyMultipleStreams() throws Exception {
        byte[] body = "request data".getBytes();
        ProxyRequest request =
                ProxyRequest.fromBytes(
                        "POST", new URI("http://example.com"), Headers.empty(), body);

        // Multiple reads should work
        String content1 = new String(request.bodyStream().readAllBytes());
        assertThat(content1).isEqualTo("request data");

        String content2 = new String(request.bodyStream().readAllBytes());
        assertThat(content2).isEqualTo("request data");
    }

    @Test
    @DisplayName("ProxyRequest with stream body is single-use")
    public void testRequestStreamBodySingleUse() throws Exception {
        InputStream stream = new ByteArrayInputStream("request data".getBytes());
        ProxyRequest request =
                ProxyRequest.fromStream(
                        "POST", new URI("http://example.com"), Headers.empty(), stream);

        // First call works
        String content = new String(request.bodyStream().readAllBytes());
        assertThat(content).isEqualTo("request data");

        // Second call should throw
        assertThatThrownBy(() -> request.bodyStream())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already consumed");
    }

    @Test
    @DisplayName("ProxyRequest body() materializes stream and enables multiple bodyStream() calls")
    public void testRequestBodyMaterializesStream() throws Exception {
        InputStream stream = new ByteArrayInputStream("request data".getBytes());
        ProxyRequest request =
                ProxyRequest.fromStream(
                        "POST", new URI("http://example.com"), Headers.empty(), stream);

        // Materialize
        byte[] body = request.body();
        assertThat(new String(body)).isEqualTo("request data");

        // Multiple bodyStream() calls should work
        String content1 = new String(request.bodyStream().readAllBytes());
        assertThat(content1).isEqualTo("request data");

        String content2 = new String(request.bodyStream().readAllBytes());
        assertThat(content2).isEqualTo("request data");
    }

    @Test
    @DisplayName("Large stream body can be handled without full materialization")
    public void testLargeStreamBody() throws IOException {
        // Create a large virtual stream (10MB) without actually allocating memory
        int size = 10 * 1024 * 1024; // 10MB
        InputStream largeStream = new VirtualLargeInputStream(size);

        ProxyResponse response = ProxyResponse.fromStream(200, Headers.empty(), largeStream);

        // Read stream in chunks to verify it works without loading everything
        try (InputStream bodyStream = response.bodyStream()) {
            byte[] buffer = new byte[8192];
            long totalRead = 0;
            int bytesRead;
            while ((bytesRead = bodyStream.read(buffer)) != -1) {
                totalRead += bytesRead;
            }
            assertThat(totalRead).isEqualTo(size);
        }

        // Attempting to read again should fail
        assertThatThrownBy(() -> response.bodyStream())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already consumed");
    }

    @Test
    @DisplayName("Supplier-based body allows re-readable streams")
    public void testSupplierBasedBody() throws IOException {
        // Supplier that creates fresh ByteArrayInputStream on each call
        byte[] data = "repeatable data".getBytes();
        ProxyResponse response =
                ProxyResponse.fromSupplier(
                        200, Headers.empty(), () -> new ByteArrayInputStream(data));

        // Multiple reads should work
        String content1 = new String(response.bodyStream().readAllBytes());
        assertThat(content1).isEqualTo("repeatable data");

        String content2 = new String(response.bodyStream().readAllBytes());
        assertThat(content2).isEqualTo("repeatable data");
    }

    /**
     * Virtual InputStream that generates data on-the-fly without allocating large byte arrays. This
     * simulates a large response body for testing streaming without using excessive memory.
     */
    private static class VirtualLargeInputStream extends InputStream {
        private final long size;
        private long position = 0;
        private final Random random = new Random(42); // Fixed seed for reproducibility

        public VirtualLargeInputStream(long size) {
            this.size = size;
        }

        @Override
        public int read() throws IOException {
            if (position >= size) {
                return -1;
            }
            position++;
            return random.nextInt(256);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (position >= size) {
                return -1;
            }
            int bytesToRead = (int) Math.min(len, size - position);
            for (int i = 0; i < bytesToRead; i++) {
                b[off + i] = (byte) random.nextInt(256);
            }
            position += bytesToRead;
            return bytesToRead;
        }
    }
}
