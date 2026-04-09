package org.codejive.tproxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for the core HttpProxy functionality. */
@DisplayName("HTTP Proxy Core Tests")
public class HttpProxyTest {

    private HttpProxy proxy;

    @BeforeEach
    public void setUp() {
        proxy = new HttpProxy();
    }

    @Test
    @DisplayName("Should create proxy instance")
    public void testProxyCreation() {
        assertThat(proxy).isNotNull();
        assertThat(proxy.isRunning()).isFalse();
    }

    @Test
    @DisplayName("Should add interceptor")
    public void testAddInterceptor() {
        Interceptor interceptor = (request, chain) -> chain.proceed(request);

        HttpProxy result = proxy.addInterceptor(interceptor);

        assertThat(result).isSameAs(proxy); // Fluent API
    }

    @Test
    @DisplayName("Should execute request without interceptors")
    public void testExecuteWithoutInterceptors() throws Exception {
        // Add a short-circuit interceptor to avoid real network call
        proxy.addInterceptor(
                (request, chain) ->
                        new ProxyResponse(
                                200,
                                Headers.of("Content-Type", "text/plain"),
                                "Mock response".getBytes()));

        ProxyRequest request =
                new ProxyRequest(
                        "GET", URI.create("http://example.com/test"), Headers.empty(), null);

        ProxyResponse response = proxy.execute(request);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Should execute request with interceptor modifying response")
    public void testExecuteWithInterceptor() throws Exception {
        proxy.addInterceptor(
                (request, chain) -> {
                    ProxyResponse response = chain.proceed(request);
                    return response.withStatusCode(201);
                });

        ProxyRequest request =
                new ProxyRequest(
                        "POST",
                        URI.create("http://example.com/api"),
                        Headers.of("Content-Type", "application/json"),
                        "{\"test\":true}".getBytes());

        ProxyResponse response = proxy.execute(request);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("Should allow interceptor to short-circuit with custom response")
    public void testShortCircuit() throws Exception {
        // Outer interceptor observes the response
        StringBuilder log = new StringBuilder();
        proxy.addInterceptor(
                (request, chain) -> {
                    ProxyResponse response = chain.proceed(request);
                    log.append("status=").append(response.getStatusCode());
                    return response;
                });

        // Inner interceptor short-circuits — never calls chain.proceed()
        proxy.addInterceptor(
                (request, chain) ->
                        new ProxyResponse(
                                418, Headers.of("X-Mock", "true"), "I'm a teapot".getBytes()));

        ProxyRequest request =
                new ProxyRequest(
                        "GET", URI.create("http://example.com/test"), Headers.empty(), null);

        ProxyResponse response = proxy.execute(request);

        assertThat(response.getStatusCode()).isEqualTo(418);
        assertThat(log.toString()).isEqualTo("status=418"); // outer interceptor saw the mock
    }

    @Test
    @DisplayName("Should start and stop proxy server")
    public void testServerLifecycle() throws Exception {
        assertThat(proxy.isRunning()).isFalse();

        proxy.start(8888);
        assertThat(proxy.isRunning()).isTrue();
        assertThat(proxy.getPort()).isEqualTo(8888);

        proxy.stop();
        assertThat(proxy.isRunning()).isFalse();
    }

    @Test
    @DisplayName("Should clear interceptors")
    public void testClearInterceptors() {
        proxy.addInterceptor((request, chain) -> chain.proceed(request));

        HttpProxy result = proxy.clearInterceptors();

        assertThat(result).isSameAs(proxy); // Fluent API
    }

    @Test
    @DisplayName("Should share context between interceptors")
    public void testInterceptorContext() throws Exception {
        proxy.addInterceptor(
                (request, chain) -> {
                    ProxyResponse response = chain.proceed(request);
                    assertThat(chain.context().get("inner")).isEqualTo("was-here");
                    return response;
                });

        proxy.addInterceptor(
                (request, chain) -> {
                    chain.context().put("inner", "was-here");
                    return chain.proceed(request);
                });

        ProxyRequest request =
                new ProxyRequest(
                        "GET", URI.create("http://example.com/test"), Headers.empty(), null);

        proxy.execute(request);
    }
}
