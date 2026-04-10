# Java Transparent Proxy

A lightweight HTTP/HTTPS proxy library with pluggable interceptors and full MITM HTTPS support.

## Features

- **HTTP Proxy** — Full HTTP/1.1 proxy
- **HTTPS Interception** — Man-in-the-Middle mode with on-the-fly certificate generation
- **HTTPS Pass-through** — Secure tunneling via CONNECT (no decryption)
- **Pluggable Interceptors** — Chain-of-responsibility for inspecting/modifying requests and responses
- **Certificate Authority** — Auto-generated CA with per-hostname server certificates (BouncyCastle)

## Usage

### Basic HTTP Proxy

```java
HttpProxy proxy = new HttpProxy();
proxy.start(8080);

HttpClient client = proxy.configureClient(HttpClient.newBuilder()).build();

HttpResponse<String> response = client.send(
    HttpRequest.newBuilder().uri(URI.create("http://example.com")).build(),
    HttpResponse.BodyHandlers.ofString());

proxy.stop();
```

### Intercepting Requests

```java
HttpProxy proxy = new HttpProxy();

// Log all requests
proxy.addInterceptor((request, chain) -> {
    System.out.println(request.getMethod() + " " + request.getUri());
    return chain.proceed(request);
});

// Add a custom header
proxy.addInterceptor((request, chain) -> {
    ProxyRequest modified = request.withHeader("X-Proxy", "tproxy");
    return chain.proceed(modified);
});

proxy.start(8080);
```

### Modifying Responses

```java
proxy.addInterceptor((request, chain) -> {
    ProxyResponse response = chain.proceed(request);
    // Rewrite response body
    String body = new String(response.getBody());
    String modified = body.replace("server", "proxy");
    return response.withBody(modified.getBytes());
});
```

### Mocking Responses

```java
proxy.addInterceptor((request, chain) -> {
    if (request.getUri().getPath().equals("/api/users")) {
        return new ProxyResponse(200,
            Headers.of("Content-Type", "application/json"),
            "{\"users\":[]}".getBytes());
    }
    return chain.proceed(request);
});
```

### HTTPS Interception (MITM)

Enable MITM mode to decrypt, inspect, and modify HTTPS traffic:

```java
HttpProxy proxy = new HttpProxy();
proxy.enableHttpsInterception();

// Interceptors now see decrypted HTTPS requests
proxy.addInterceptor((request, chain) -> {
    System.out.println("HTTPS: " + request.getUri()); // visible!
    return chain.proceed(request);
});

proxy.start(8080);
```

On first run, the proxy generates a CA certificate (`tproxy-ca.crt`) and keystore (`tproxy-ca.p12`). Clients must trust this CA. For `HttpClient`:

```java
CertificateFactory cf = CertificateFactory.getInstance("X.509");
Certificate caCert;
try (FileInputStream fis = new FileInputStream("tproxy-ca.crt")) {
    caCert = cf.generateCertificate(fis);
}

KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
trustStore.load(null, null);
trustStore.setCertificateEntry("tproxy-ca", caCert);

TrustManagerFactory tmf = TrustManagerFactory.getInstance(
    TrustManagerFactory.getDefaultAlgorithm());
tmf.init(trustStore);

SSLContext sslContext = SSLContext.getInstance("TLS");
sslContext.init(null, tmf.getTrustManagers(), null);

HttpClient client = proxy.configureClient(HttpClient.newBuilder())
    .sslContext(sslContext)
    .build();
```

### HTTPS Pass-through

Without MITM enabled (the default), HTTPS tunnels through encrypted. Interceptors cannot see the content:

```java
HttpProxy proxy = new HttpProxy();
proxy.start(8080); // HTTPS passes through unmodified
```

## How MITM Works

1. Client sends `CONNECT example.com:443` to the proxy
2. `InterceptingConnectHandler` (extends Jetty's `ConnectHandler`) redirects the tunnel to a local SSL-terminating connector
3. `CertificateGeneratingKeyManager` reads the SNI hostname from the TLS ClientHello and generates a certificate signed by the proxy's CA
4. After SSL termination, the decrypted HTTP request flows through the normal servlet handler and interceptor chain
5. The proxy forwards the request to the real server using a trust-all `HttpClient`

## Building

```bash
./mvnw compile     # Compile
./mvnw test        # Run tests (38 tests)
./mvnw package     # Package JAR
```

## Requirements

- Java 21+
- Maven 3.6+

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

**Security Warning:** This tool performs man-in-the-middle HTTPS interception. Use only for educational and testing purposes in controlled environments where you have proper authorization.
