package org.codejive.tproxy;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Immutable, case-insensitive HTTP header collection. Supports multiple values per header name.
 *
 * <p>Header names are stored in their original case but all lookups are case-insensitive, matching
 * HTTP semantics (RFC 7230 §3.2).
 */
public final class Headers implements Iterable<Map.Entry<String, List<String>>> {

    private static final Headers EMPTY = new Headers(Map.of());

    // TreeMap with case-insensitive keys preserves iteration order by name while
    // giving O(log n) case-insensitive lookups without allocating lowercase copies.
    private final NavigableMap<String, List<String>> map;

    private Headers(Map<String, List<String>> headers) {
        TreeMap<String, List<String>> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((k, v) -> m.put(k, List.copyOf(v)));
        this.map = Collections.unmodifiableNavigableMap(m);
    }

    /** Empty header collection. */
    public static Headers empty() {
        return EMPTY;
    }

    /** Create headers from a map. */
    public static Headers of(Map<String, List<String>> headers) {
        if (headers.isEmpty()) {
            return EMPTY;
        }
        return new Headers(headers);
    }

    /** Convenience: create headers from single-value pairs. */
    public static Headers of(String name, String value) {
        return new Headers(Map.of(name, List.of(value)));
    }

    /** Convenience: create headers from two single-value pairs. */
    public static Headers of(String n1, String v1, String n2, String v2) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(n1, List.of(v1));
        m.put(n2, List.of(v2));
        return new Headers(m);
    }

    /**
     * Get all values for a header name (case-insensitive).
     *
     * @return the values, or an empty list if the header is not present
     */
    public List<String> getAll(String name) {
        List<String> values = map.get(name);
        return values != null ? values : List.of();
    }

    /**
     * Get the first value for a header name (case-insensitive).
     *
     * @return the first value, or {@code null} if the header is not present
     */
    public String getFirst(String name) {
        List<String> values = map.get(name);
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    /** Check whether a header is present (case-insensitive). */
    public boolean contains(String name) {
        return map.containsKey(name);
    }

    /** Number of distinct header names. */
    public int size() {
        return map.size();
    }

    /** Whether this collection is empty. */
    public boolean isEmpty() {
        return map.isEmpty();
    }

    /** Return an unmodifiable map view. */
    public Map<String, List<String>> toMap() {
        return map;
    }

    /** Return a copy with an added/replaced single-value header. */
    public Headers with(String name, String value) {
        Map<String, List<String>> copy = new LinkedHashMap<>(map);
        copy.put(name, List.of(value));
        return new Headers(copy);
    }

    /** Return a copy with an added/replaced multi-value header. */
    public Headers with(String name, List<String> values) {
        Map<String, List<String>> copy = new LinkedHashMap<>(map);
        copy.put(name, values);
        return new Headers(copy);
    }

    /** Return a copy with the given header removed. */
    public Headers without(String name) {
        if (!map.containsKey(name)) {
            return this;
        }
        Map<String, List<String>> copy = new LinkedHashMap<>(map);
        copy.remove(name);
        return new Headers(copy);
    }

    @Override
    public Iterator<Map.Entry<String, List<String>>> iterator() {
        return map.entrySet().iterator();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Headers other = (Headers) o;
        return map.equals(other.map);
    }

    @Override
    public int hashCode() {
        return map.hashCode();
    }

    @Override
    public String toString() {
        return map.entrySet().stream()
                .map(e -> e.getKey() + ": " + String.join(", ", e.getValue()))
                .collect(Collectors.joining("; ", "Headers{", "}"));
    }
}
