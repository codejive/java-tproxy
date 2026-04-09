package org.codejive.tproxy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chain of interceptors for processing proxy exchanges. Each call to {@link #proceed} invokes the
 * next interceptor, or the target when all interceptors have been called.
 */
public class InterceptorChain {

    /** Terminal target that produces the actual response (e.g. an HTTP client call). */
    @FunctionalInterface
    public interface Target {
        ProxyResponse execute(ProxyRequest request) throws Exception;
    }

    private final List<Interceptor> interceptors;
    private final Target target;
    private final Map<String, Object> context;
    private int currentIndex = 0;

    public InterceptorChain(List<Interceptor> interceptors, Target target) {
        this.interceptors = new ArrayList<>(interceptors);
        this.target = target;
        this.context = new HashMap<>();
    }

    /**
     * Proceed to the next interceptor or target in the chain.
     *
     * @param request the request to process
     * @return the response
     * @throws Exception if an error occurs
     */
    public ProxyResponse proceed(ProxyRequest request) throws Exception {
        if (currentIndex < interceptors.size()) {
            Interceptor interceptor = interceptors.get(currentIndex++);
            return interceptor.intercept(request, this);
        }
        return target.execute(request);
    }

    /**
     * Mutable context map shared across all interceptors in this chain. Use this to pass metadata
     * between interceptors (e.g. correlation IDs, timing, flags).
     *
     * @return the context map
     */
    public Map<String, Object> context() {
        return context;
    }
}
