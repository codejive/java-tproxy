# Java Transparent Proxy (java-tproxy)

An HTTP/HTTPS proxy library with pluggable interceptors.

## Overview

This library provides a simple **transparant proxy** implementation that has no other functionality than passing through requests and responses. It's the pluggable custom interceptors that make this library useful for implementing other features on top.

One obvious use-case is for testing, where you want to be able to intercept and inspect requests and responses being made by 3rd party code out of your direct control.

The proxy itself has no knowledge of testing concepts. All testing functionality you could imagine (for example: recording, replay, comparison) would have to be implemented via interceptors, making the core proxy reusable for many purposes.

**Important Security Note**: This library is designed for controlled testing environments where the user has full control over the test infrastructure. The security implications of transparent HTTPS proxies are well understood and considered acceptable for this specific use case.

## Architecture

The library follows a **layered architecture** with clear separation of concerns:

```
┌─────────────────────────────────────────┐
│     Interceptor Framework (core)        │
│  ┌──────────────────────────────────┐  │
│  │ RequestInterceptor               │  │
│  │ ResponseInterceptor              │  │
│  │ InterceptorChain                 │  │
│  └──────────────────────────────────┘  │
└─────────────────────────────────────────┘
              ↓ used by
┌─────────────────────────────────────────┐
│        Core Proxy Layer (core)          │
│  ┌──────────────────────────────────┐  │
│  │ HttpProxy                        │  │
│  │ ProxyRequest                     │  │
│  │ ProxyResponse                    │  │
│  └──────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

## Requirements

- Java 21 or higher
- Maven 3.6+
