package org.codejive.tproxy;

import java.util.Objects;

/**
 * Immutable representation of an HTTP response in the proxy layer. Use {@code with*()} methods to
 * create modified copies.
 */
public class ProxyResponse {
    private final int statusCode;
    private final Headers headers;
    private final byte[] body;

    public ProxyResponse(int statusCode, Headers headers, byte[] body) {
        this.statusCode = statusCode;
        this.headers = headers != null ? headers : Headers.empty();
        this.body = body != null ? body.clone() : new byte[0];
    }

    public int statusCode() {
        return statusCode;
    }

    public Headers headers() {
        return headers;
    }

    public byte[] body() {
        return body.clone();
    }

    public ProxyResponse withStatusCode(int statusCode) {
        return new ProxyResponse(statusCode, headers, body);
    }

    public ProxyResponse withHeaders(Headers headers) {
        return new ProxyResponse(statusCode, headers, body);
    }

    public ProxyResponse withHeader(String name, String value) {
        return new ProxyResponse(statusCode, headers.with(name, value), body);
    }

    public ProxyResponse withBody(byte[] body) {
        return new ProxyResponse(statusCode, headers, body);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProxyResponse that = (ProxyResponse) o;
        return statusCode == that.statusCode
                && Objects.equals(headers, that.headers)
                && java.util.Arrays.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(statusCode, headers);
        result = 31 * result + java.util.Arrays.hashCode(body);
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
                + body.length
                + '}';
    }
}
