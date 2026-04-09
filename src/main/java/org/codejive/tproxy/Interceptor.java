package org.codejive.tproxy;

/**
 * Interceptor for proxy request/response exchanges. Interceptors form a chain where each can:
 *
 * <ul>
 *   <li>Modify the request before passing it to the next interceptor
 *   <li>Short-circuit by returning a response without calling {@link InterceptorChain#proceed}
 *   <li>Modify the response returned by {@link InterceptorChain#proceed}
 *   <li>Monitor/record both requests and responses
 * </ul>
 */
public interface Interceptor {

    /**
     * Intercept a request/response exchange.
     *
     * @param request the request
     * @param chain the interceptor chain; call {@link InterceptorChain#proceed} to forward
     * @return the response
     * @throws Exception if an error occurs
     */
    ProxyResponse intercept(ProxyRequest request, InterceptorChain chain) throws Exception;
}
