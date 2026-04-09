package org.codejive.tproxy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Utility for filtering HTTP headers according to proxy requirements.
 *
 * <p>Removes hop-by-hop headers that should not be forwarded by proxies per RFC 7230 §6.1.
 */
public final class HeaderFilter {

    /**
     * Hop-by-hop headers that must be removed by proxies (RFC 7230 §6.1). These headers are
     * meaningful only for a single transport-level connection and must not be forwarded.
     */
    private static final Set<String> HOP_BY_HOP_HEADERS =
            Set.of(
                    "connection",
                    "keep-alive",
                    "proxy-authenticate",
                    "proxy-authorization",
                    "te",
                    "trailer",
                    "transfer-encoding",
                    "upgrade");

    /**
     * Headers that proxies should typically remove when forwarding requests to avoid issues with
     * target servers or loops.
     */
    private static final Set<String> PROXY_SPECIFIC_HEADERS =
            Set.of("proxy-connection", "proxy-authenticate", "proxy-authorization");

    private HeaderFilter() {
        // Utility class
    }

    /**
     * Filter headers for forwarding through a proxy. Removes hop-by-hop headers and proxy-specific
     * headers that should not be sent to the target server.
     *
     * @param headers the original headers
     * @return filtered headers safe for forwarding
     */
    public static Headers filterForForwarding(Headers headers) {
        Map<String, List<String>> filtered = new LinkedHashMap<>();

        headers.forEach(
                entry -> {
                    String name = entry.getKey();
                    String nameLower = name.toLowerCase(Locale.ROOT);

                    // Skip hop-by-hop headers
                    if (HOP_BY_HOP_HEADERS.contains(nameLower)) {
                        return;
                    }

                    // Skip proxy-specific headers
                    if (PROXY_SPECIFIC_HEADERS.contains(nameLower)) {
                        return;
                    }

                    // Keep all other headers
                    filtered.put(name, entry.getValue());
                });

        return Headers.of(filtered);
    }

    /**
     * Check if a header should be filtered (removed) when forwarding.
     *
     * @param headerName the header name (case-insensitive)
     * @return true if the header should be removed, false otherwise
     */
    public static boolean shouldFilter(String headerName) {
        String nameLower = headerName.toLowerCase(Locale.ROOT);
        return HOP_BY_HOP_HEADERS.contains(nameLower) || PROXY_SPECIFIC_HEADERS.contains(nameLower);
    }

    /**
     * Add or update the Host header based on the target URI.
     *
     * @param headers the current headers
     * @param host the target host (e.g., "example.com:443")
     * @return headers with updated Host header
     */
    public static Headers withHost(Headers headers, String host) {
        return headers.with("Host", host);
    }
}
