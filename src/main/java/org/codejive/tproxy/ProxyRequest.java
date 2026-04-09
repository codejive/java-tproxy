package org.codejive.tproxy;

import java.net.URI;
import java.util.Objects;

/**
 * Immutable representation of an HTTP request in the proxy layer. Use {@code with*()} methods to
 * create modified copies.
 */
public class ProxyRequest {
    private final String method;
    private final URI uri;
    private final Headers headers;
    private final byte[] body;

    public ProxyRequest(String method, URI uri, Headers headers, byte[] body) {
        this.method = Objects.requireNonNull(method, "method cannot be null");
        this.uri = Objects.requireNonNull(uri, "uri cannot be null");
        this.headers = headers != null ? headers : Headers.empty();
        this.body = body != null ? body.clone() : new byte[0];
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

    public byte[] body() {
        return body.clone();
    }

    public ProxyRequest withMethod(String method) {
        return new ProxyRequest(method, uri, headers, body);
    }

    public ProxyRequest withUri(URI uri) {
        return new ProxyRequest(method, uri, headers, body);
    }

    public ProxyRequest withHeaders(Headers headers) {
        return new ProxyRequest(method, uri, headers, body);
    }

    public ProxyRequest withHeader(String name, String value) {
        return new ProxyRequest(method, uri, headers.with(name, value), body);
    }

    public ProxyRequest withBody(byte[] body) {
        return new ProxyRequest(method, uri, headers, body);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProxyRequest that = (ProxyRequest) o;
        return Objects.equals(method, that.method)
                && Objects.equals(uri, that.uri)
                && Objects.equals(headers, that.headers)
                && java.util.Arrays.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(method, uri, headers);
        result = 31 * result + java.util.Arrays.hashCode(body);
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
                + body.length
                + '}';
    }
}
