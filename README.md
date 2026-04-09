# Java Transparent Proxy (java-tproxy)

A high-performance HTTP/HTTPS proxy library with pluggable interceptors, built on Jetty and Java 21's virtual threads.

## Overview

This library provides a **transparent proxy** implementation with flexible request/response interception capabilities. The core proxy has minimal built-in functionality - it's the pluggable custom interceptors that make this library powerful for implementing features like request recording, modification, mocking, and testing.

**Key Features:**
- ✅ **HTTP Proxy** - Full HTTP/1.1 support with header filtering per RFC 7230
- ✅ **HTTPS Pass-through** - Secure tunneling via CONNECT method
- ✅ **Certificate Authority** - Dynamic SSL certificate generation for future HTTPS interception
- ✅ **Virtual Threads** - Java 21 virtual threads for high concurrency
- ✅ **Pluggable Interceptors** - Chain-of-responsibility pattern for request/response modification
- ✅ **Production Ready** - 35 passing tests with WireMock integration testing

**Use Cases:**
- Testing 3rd party HTTP clients
- Recording and replaying HTTP traffic
- Mocking external APIs during tests
- Inspecting requests/responses from opaque libraries
- Building custom proxy features via interceptors

**Important Security Note**: This library is designed for controlled testing environments where the user has full control over the test infrastructure. The security implications of transparent HTTPS proxies are well understood and considered acceptable for this specific use case.

## Architecture

The library follows a **layered architecture** with clear separation of concerns:

```
┌────────────────────────────────────────────────────┐
│         Application Layer (user code)              │
│  ┌──────────────────────────────────────────────┐ │
│  │ Custom Interceptors                          │ │
│  │ - Capture requests/responses                 │ │
│  │ - Modify headers/body                        │ │
│  │ - Mock responses                             │ │
│  └──────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────┘
                       ↓
┌────────────────────────────────────────────────────┐
│       Interceptor Framework (tproxy-core)          │
│  ┌──────────────────────────────────────────────┐ │
│  │ Interceptor, InterceptorChain                │ │
│  │ ProxyRequest, ProxyResponse, Headers         │ │
│  └──────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────┘
                       ↓
┌────────────────────────────────────────────────────┐
│         Proxy Core (tproxy-core)                   │
│  ┌──────────────────────────────────────────────┐ │
│  │ HttpProxy - Interceptor execution            │ │
│  │ ProxyServer - Jetty embedded server          │ │
│  │ CertificateAuthority - SSL cert generation   │ │
│  └──────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────┘
                       ↓
┌────────────────────────────────────────────────────┐
│     Infrastructure (Jetty 11 + Java 21)            │
│  ┌──────────────────────────────────────────────┐ │
│  │ ConnectHandler - HTTPS tunneling             │ │
│  │ ServletContextHandler - HTTP requests        │ │
│  │ HttpClient - Forwarding to target servers    │ │
│  │ Virtual Thread Pool - Concurrency            │ │
│  └──────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────┘
```

## Requirements

- Java 21 or higher (uses virtual threads)
- Maven 3.6+

## Quick Start

### Basic HTTP Proxy

```java
// Create and start the proxy
HttpProxy proxy = new HttpProxy();
proxy.start(8080);

// Configure your HTTP client to use the proxy
HttpClient client = HttpClient.newBuilder()
    .proxy(ProxySelector.of(new InetSocketAddress("localhost", 8080)))
    .build();

// Make requests - they'll be proxied through port 8080
HttpResponse<String> response = client.send(
    HttpRequest.newBuilder()
        .uri(URI.create("http://example.com"))
        .build(),
    HttpResponse.BodyHandlers.ofString());

// Stop when done
proxy.stop();
```

### Adding Interceptors

```java
// Create interceptor to capture requests
Interceptor captureInterceptor = new Interceptor() {
    @Override
    public ProxyResponse intercept(ProxyRequest request, InterceptorChain chain) {
        System.out.println("Request: " + request.method() + " " + request.uri());
        return chain.proceed(request);
    }
};

// Create proxy with interceptor
HttpProxy proxy = new HttpProxy(captureInterceptor);
proxy.start(8080);
```

### Modifying Requests

```java
Interceptor modifyInterceptor = new Interceptor() {
    @Override
    public ProxyResponse intercept(ProxyRequest request, InterceptorChain chain) {
        // Add custom header
        ProxyRequest modified = new ProxyRequest(
            request.method(),
            request.uri(),
            request.headers().with("X-Custom-Header", "ProxyValue"),
            request.body()
        );
        return chain.proceed(modified);
    }
};
```

### Mocking Responses

```java
Interceptor mockInterceptor = new Interceptor() {
    @Override
    public ProxyResponse intercept(ProxyRequest request, InterceptorChain chain) {
        if (request.uri().getPath().equals("/api/users")) {
            // Return mock response instead of forwarding
            return new ProxyResponse(
                200,
                Headers.of("Content-Type", "application/json"),
                "{\"users\":[]}".getBytes()
            );
        }
        return chain.proceed(request);
    }
};
```

## HTTPS Support

### HTTPS Pass-through (Current Implementation)

The proxy currently supports **HTTPS pass-through** mode via the `CONNECT` method. This allows HTTPS traffic to tunnel through the proxy without decryption:

```java
HttpProxy proxy = new HttpProxy();
proxy.start(8080);

// HTTPS requests will tunnel through without interception
HttpClient client = HttpClient.newBuilder()
    .proxy(ProxySelector.of(new InetSocketAddress("localhost", 8080)))
    .build();

// This request tunnels through the proxy but interceptors CAN'T inspect it
HttpResponse<String> response = client.send(
    HttpRequest.newBuilder()
        .uri(URI.create("https://example.com"))  // HTTPS
        .build(),
    HttpResponse.BodyHandlers.ofString());
```

**How it works:**
1. Client sends `CONNECT example.com:443` to proxy
2. Proxy connects to target server
3. Proxy responds with `200 Connection Established`
4. Client and server exchange encrypted TLS traffic
5. Proxy passes bytes through without decryption
6. **Interceptors do NOT see HTTPS traffic content**

### Certificate Authority for HTTPS Interception (Future Enhancement)

The `CertificateAuthority` class is implemented and tested, providing the foundation for man-in-the-middle HTTPS interception:

```java
// Generate a Certificate Authority
CertificateAuthority ca = new CertificateAuthority();

// CA certificate saved to: tproxy-ca.crt (import into browser)
// CA keystore saved to: tproxy-ca.p12

// Generate certificate for a specific hostname
KeyStore serverCert = ca.generateServerCertificate("example.com");
```

**What's implemented:**
- ✅ Self-signed CA generation and persistence
- ✅ Dynamic server certificate generation per hostname
- ✅ Subject Alternative Name (SAN) support
- ✅ Proper certificate chain validation
- ✅ Export CA certificate for browser import

**What's needed for full HTTPS interception:**
- Socket-level SSL context switching (requires going below Jetty's servlet abstraction)
- Upgrade connection to SSL after `CONNECT` using generated certificate
- Decrypt client traffic, pass through interceptors, re-encrypt
- Full implementation would be in `InterceptingConnectHandler` (skeleton created)

**Why not fully implemented:**
- HTTPS interception requires low-level socket programming
- Needs careful handling of SSL handshake timing
- Complex bidirectional SSL proxying
- Current pass-through approach is simpler and sufficient for many use cases

**To enable HTTPS interception in the future:**
1. Import `tproxy-ca.crt` into your browser's trusted certificates
2. Replace `ConnectHandler` with`InterceptingConnectHandler` in `ProxyServer`
3. Implement SSL socket wrapping in the CONNECT handler
4. All HTTPS traffic will be decryptable by interceptors

## Technical Details

### Header Filtering

The proxy correctly filters hop-by-hop headers per **RFC 7230 §6.1**:

- Removed headers: `Connection`, `Keep-Alive`, `Proxy-Authenticate`, `Proxy-Authorization`, `TE`, `Trailer`, `Transfer-Encoding`, `Upgrade`
- `Host` header is managed automatically by `HttpClient`

### Virtual Threads

Uses Java 21 virtual threads for handling concurrent connections:

```java
QueuedThreadPool threadPool = new QueuedThreadPool();
threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
```

Benefits:
- Lightweight concurrency (millions of virtual threads possible)
- No thread pool tuning needed
- Blocking I/O operations don't waste OS threads

### Error Handling

- **408 Request Timeout** - Connection to target times out (30s default)
- **502 Bad Gateway** - Cannot connect to target server
- **503 Service Unavailable** - Request interrupted
- **500 Internal Server Error** - Unexpected proxy errors

## Testing

Run all tests:
```bash
mvn test
```

Current test coverage:
- **35 passing tests**
- HTTP proxy core (8 tests)
- HTTP integration with WireMock (8 tests)
- HTTPS pass-through tunneling (4 tests)
- Proxy model (8 tests)
- Certificate Authority (7 tests)

## Build and Package

```bash
# Compile
mvn compile

# Run tests
mvn test

# Package JAR
mvn package

# Clean build
mvn clean install
```

## Implementation Phases

### ✅ Phase 1: HTTP Proxy Core (Complete)
- Jetty embedded server integration
- HttpClient-based request forwarding
- Header filtering per RFC 7230
- Interceptor chain execution
- Virtual thread pool
- Comprehensive HTTP testing

### ✅ Phase 2: HTTPS Pass-through (Complete)
- CONNECT method handler
- Secure tunneling without decryption
- Integration with Jetty ConnectHandler
- HTTPS testing with WireMock

### ✅ Phase 3: Certificate Authority (Complete)
- Self-signed root CA generation
- Dynamic server certificate creation
- BouncyCastle integration
- Certificate persistence
- Comprehensive CA testing

### 🔄 Phase 4: HTTPS Interception (Skeleton Created)
- `InterceptingConnectHandler` class created
- **Future work**: Socket-level SSL context switching
- **Future work**: Decrypt → Intercept → Re-encrypt pipeline
- **Future work**: Two-way SSL proxying

## Project Structure

```
java-tproxy/
├── src/main/java/org/codejive/tproxy/
│   ├── HttpProxy.java                    # Main proxy class
│   ├── ProxyServer.java                  # Jetty server wrapper
│   ├── Interceptor.java                  # Interceptor interface
│   ├── InterceptorChain.java             # Chain-of-responsibility
│   ├── ProxyRequest.java                 # Immutable request model
│   ├── ProxyResponse.java                # Immutable response model
│   ├── Headers.java                      # Header map abstraction
│   ├── HeaderFilter.java                 # RFC 7230 header filtering
│   ├── CertificateAuthority.java         # Dynamic SSL cert generation
│   └── InterceptingConnectHandler.java   # HTTPS interception (future)
├── src/test/java/org/codejive/tproxy/
│   ├── HttpProxyTest.java               # Unit tests
│   ├── HttpProxyIntegrationTest.java    # HTTP integration tests
│   ├── HttpsPassThroughTest.java        # HTTPS tunneling tests
│   ├── ProxyModelTest.java              # Model tests
│   └── CertificateAuthorityTest.java    # CA tests
├── pom.xml                              # Maven configuration
└── README.md                            # This file
```

## Dependencies

- **Jetty 11.0.24** - Embedded HTTP server
- **BouncyCastle 1.78.1** - Certificate generation
- **SLF4J 2.0.17** - Logging facade
- **JUnit 5** - Testing framework
- **AssertJ** - Fluent assertions
- **WireMock 3.4.2** - HTTP mocking for tests

## License

This project is for educational and testing purposes. Use responsibly in controlled environments.
