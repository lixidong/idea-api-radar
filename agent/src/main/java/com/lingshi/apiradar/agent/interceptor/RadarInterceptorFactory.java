package com.lingshi.apiradar.agent.interceptor;

import com.lingshi.apiradar.agent.AgentConfig;
import com.lingshi.apiradar.agent.transport.EventReporter;
import com.lingshi.apiradar.agent.transport.HttpEvent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 通过 JDK 动态代理生成 Spring {@code ClientHttpRequestInterceptor} 实例，
 * 然后反射注入到目标 RestTemplate 中。
 *
 * <p><b>关键约束</b>：Spring 的 {@code InterceptingRequestExecution}、{@code SimpleClientHttpResponse}
 * 等具体实现类都是 package-private 的，agent 不能直接通过 {@code obj.getClass().getMethod} 反射调用，
 * 否则会触发 {@link IllegalAccessException}。
 * 因此所有反射调用都必须从 <b>public 接口</b>（HttpRequest / ClientHttpRequestExecution /
 * ClientHttpResponse / HttpHeaders）上获取 Method，再用该 Method 对实例 invoke。
 */
public final class RadarInterceptorFactory {

    private RadarInterceptorFactory() {}

    public static Object create(ClassLoader cl) throws ClassNotFoundException {
        Class<?> interceptorIface =
                cl.loadClass("org.springframework.http.client.ClientHttpRequestInterceptor");
        return Proxy.newProxyInstance(
                cl,
                new Class<?>[]{interceptorIface},
                new RadarInvocationHandler(cl)
        );
    }

    private static class RadarInvocationHandler implements InvocationHandler {
        private final ClassLoader cl;

        // 缓存接口方法对象，避免每次请求都查找
        private final Method httpRequestGetMethod;
        private final Method httpRequestGetURI;
        private final Method httpRequestGetHeaders;
        private final Method executionExecute;
        private final Method responseGetStatusCode;
        private final Method responseGetHeaders;
        private final Method responseGetBody;
        private final Method responseClose;
        private final Method statusCodeValue;
        private final Class<?> httpRequestIface;

        RadarInvocationHandler(ClassLoader cl) {
            this.cl = cl;
            try {
                httpRequestIface = cl.loadClass("org.springframework.http.HttpRequest");
                Class<?> executionIface =
                        cl.loadClass("org.springframework.http.client.ClientHttpRequestExecution");
                Class<?> responseIface =
                        cl.loadClass("org.springframework.http.client.ClientHttpResponse");
                Class<?> httpStatusCodeCls =
                        cl.loadClass("org.springframework.http.HttpStatusCode");

                httpRequestGetMethod = httpRequestIface.getMethod("getMethod");
                httpRequestGetURI = httpRequestIface.getMethod("getURI");
                httpRequestGetHeaders = httpRequestIface.getMethod("getHeaders");
                executionExecute = executionIface.getMethod("execute", httpRequestIface, byte[].class);
                responseGetStatusCode = responseIface.getMethod("getStatusCode");
                responseGetHeaders = responseIface.getMethod("getHeaders");
                responseGetBody = responseIface.getMethod("getBody");
                responseClose = responseIface.getMethod("close");
                statusCodeValue = httpStatusCodeCls.getMethod("value");
            } catch (Throwable t) {
                throw new IllegalStateException("API Radar: failed to resolve Spring interfaces", t);
            }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!"intercept".equals(method.getName()) || args == null || args.length != 3) {
                return defaultMethod(method, args);
            }
            return handleIntercept(args[0], (byte[]) args[1], args[2]);
        }

        private Object defaultMethod(Method method, Object[] args) {
            String name = method.getName();
            if ("toString".equals(name)) return "RadarClientHttpRequestInterceptor";
            if ("hashCode".equals(name)) return System.identityHashCode(this);
            if ("equals".equals(name)) return args != null && args.length == 1 && args[0] == this;
            return null;
        }

        private Object handleIntercept(Object request, byte[] body, Object execution) throws Throwable {
            // ===== 快速路径：暂停时直接放行，零开销 =====
            if (AgentConfig.paused()) {
                return executionExecute.invoke(execution, request, body);
            }

            // ===== URL 过滤：不匹配的 URL 不采集，直接放行 =====
            String url = null;
            try {
                Object uri = httpRequestGetURI.invoke(request);
                url = uri == null ? null : uri.toString();
            } catch (Throwable ignored) {}
            if (!AgentConfig.shouldCapture(url)) {
                return executionExecute.invoke(execution, request, body);
            }

            long start = System.currentTimeMillis();
            HttpEvent event = new HttpEvent();
            event.id = UUID.randomUUID().toString();
            event.timestamp = start;
            event.source = "RestTemplate";
            event.url = url;

            try {
                fillRequest(event, request, body);
            } catch (Throwable t) {
                // 反射失败不影响业务
            }

            Object response;
            try {
                response = executionExecute.invoke(execution, request, body);
            } catch (Throwable thrown) {
                Throwable real = unwrap(thrown);
                event.error = real.getClass().getSimpleName() + ": " + real.getMessage();
                event.duration = System.currentTimeMillis() - start;
                EventReporter.report(event);
                throw real;
            }

            try {
                response = wrapResponse(event, response);
            } catch (Throwable t) {
                // 反射失败不影响业务
            }
            event.duration = System.currentTimeMillis() - start;
            EventReporter.report(event);
            return response;
        }

        private void fillRequest(HttpEvent event, Object request, byte[] body) throws Throwable {
            Object httpMethod = httpRequestGetMethod.invoke(request);
            event.method = httpMethod == null ? null : httpMethod.toString();

            Object uri = httpRequestGetURI.invoke(request);
            event.url = uri == null ? null : uri.toString();

            Object headers = httpRequestGetHeaders.invoke(request);
            event.requestHeaders = headersToMap(headers);

            event.requestBody = decodeBody(body, headers);
        }

        /**
         * 同时完成两件事：把元数据采集到 event；如果 body 不超过阈值就读到内存并包装 response，
         * 否则直接返回原 response（不读 body，业务流式消费），event.responseBody 写占位文本。
         */
        private Object wrapResponse(HttpEvent event, Object response) throws Throwable {
            Object status = responseGetStatusCode.invoke(response);
            if (status != null) {
                event.responseCode = (int) statusCodeValue.invoke(status);
            }
            Object headers = responseGetHeaders.invoke(response);
            event.responseHeaders = headersToMap(headers);

            long contentLength = contentLengthOf(headers);
            int skipThreshold = AgentConfig.skipBodyOverBytes();
            if (contentLength > 0 && contentLength > skipThreshold) {
                event.responseBody = "[SKIPPED: content-length=" + contentLength
                        + " exceeds skipBodyOverBytes=" + skipThreshold + "]";
                return response;
            }

            // 读到内存并包装。BufferingClientHttpRequestFactory 已经做过一次缓冲，
            // 我们这里再读一次就是从 ByteArrayInputStream 复制，无 IO 开销。
            Class<?> respIface = cl.loadClass("org.springframework.http.client.ClientHttpResponse");
            Object originalStream = responseGetBody.invoke(response);
            byte[] bytes = readAll((java.io.InputStream) originalStream);
            event.responseBody = decodeBody(bytes, headers);
            return Proxy.newProxyInstance(cl, new Class<?>[]{respIface},
                    new BufferingResponseHandler(response, bytes, responseClose));
        }

        @SuppressWarnings("unchecked")
        private static long contentLengthOf(Object headers) {
            try {
                if (!(headers instanceof Map)) return -1;
                Map<String, List<String>> map = (Map<String, List<String>>) headers;
                List<String> values = map.get("Content-Length");
                if (values == null || values.isEmpty()) values = map.get("content-length");
                if (values == null || values.isEmpty()) return -1;
                return Long.parseLong(values.get(0).trim());
            } catch (Throwable ignored) {
                return -1;
            }
        }

        private static byte[] readAll(java.io.InputStream input) {
            if (input == null) return new byte[0];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = input.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } catch (Throwable ignored) {
            } finally {
                try { input.close(); } catch (Throwable ignored) {}
            }
            return out.toByteArray();
        }

        @SuppressWarnings("unchecked")
        private Map<String, String> headersToMap(Object headers) {
            Map<String, String> result = new LinkedHashMap<>();
            if (headers == null) return result;
            // HttpHeaders 实现了 MultiValueMap<String, String>，是 Map<String, List<String>>
            try {
                Map<String, List<String>> map = (Map<String, List<String>>) headers;
                for (Map.Entry<String, List<String>> e : map.entrySet()) {
                    List<String> values = e.getValue();
                    result.put(e.getKey(), values == null ? "" : String.join(", ", values));
                }
            } catch (Throwable ignored) {
            }
            return result;
        }

        private static String decodeBody(byte[] body, Object headers) {
            if (body == null || body.length == 0) return null;
            String charset = detectCharset(headers, "UTF-8");
            int max = AgentConfig.maxBodyBytes();
            int len = Math.min(body.length, max);
            String text;
            try {
                text = new String(body, 0, len, charset);
            } catch (Exception e) {
                text = new String(body, 0, len);
            }
            if (body.length > max) {
                text += "\n[TRUNCATED: original=" + body.length + " bytes, captured=" + max + "]";
            }
            return text;
        }

        private static String readStream(Object stream, Object headers) {
            if (stream == null) return null;
            byte[] bytes = readAll((java.io.InputStream) stream);
            return decodeBody(bytes, headers);
        }

        @SuppressWarnings("unchecked")
        private static String detectCharset(Object headers, String fallback) {
            try {
                if (!(headers instanceof Map)) return fallback;
                Map<String, List<String>> map = (Map<String, List<String>>) headers;
                List<String> values = map.get("Content-Type");
                if (values == null || values.isEmpty()) {
                    values = map.get("content-type");
                }
                if (values == null || values.isEmpty()) return fallback;
                String contentType = values.get(0).toLowerCase();
                int idx = contentType.indexOf("charset=");
                if (idx < 0) return fallback;
                String cs = contentType.substring(idx + 8).trim();
                int semi = cs.indexOf(';');
                if (semi > 0) cs = cs.substring(0, semi).trim();
                return cs.isEmpty() ? fallback : cs;
            } catch (Throwable ignored) {
                return fallback;
            }
        }

        private static Throwable unwrap(Throwable t) {
            if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) {
                return t.getCause();
            }
            return t;
        }
    }

    /**
     * 包装 ClientHttpResponse：getBody 返回内存中的 byte[]，其他方法委托给原 response。
     * 所有方法分发都通过接口 Method 反射调用，避免访问 package-private 实现类。
     */
    private static class BufferingResponseHandler implements InvocationHandler {
        private final Object delegate;
        private final byte[] body;
        private final Method closeMethod;

        BufferingResponseHandler(Object delegate, byte[] body, Method closeMethod) {
            this.delegate = delegate;
            this.body = body;
            this.closeMethod = closeMethod;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("getBody".equals(name)) {
                return new java.io.ByteArrayInputStream(body);
            }
            if ("close".equals(name)) {
                try {
                    closeMethod.invoke(delegate);
                } catch (Throwable ignored) {}
                return null;
            }
            // 用传入 method（来自接口）反射调用 delegate
            try {
                return method.invoke(delegate, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
        }
    }
}
