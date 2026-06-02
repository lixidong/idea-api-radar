package com.lingshi.apiradar.agent.interceptor;

import com.lingshi.apiradar.agent.AgentConfig;
import com.lingshi.apiradar.agent.transport.EventReporter;
import com.lingshi.apiradar.agent.transport.HttpEvent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 通过 JDK 动态代理生成 {@code okhttp3.Interceptor} 实例，由 OkHttpInstrumenter
 * 在 {@code OkHttpClient.Builder.build()} 中注入到 networkInterceptors。
 *
 * <p>读 request body 不消耗原 RequestBody；读 response body 用 peekBody(N) 在不破坏
 * 业务消费的前提下完整读取（peekBody 内部已是缓冲流）。
 */
public final class OkHttpInterceptorFactory {

    private OkHttpInterceptorFactory() {}

    public static Object create(ClassLoader cl) {
        try {
            Class<?> ifaceCls = cl.loadClass("okhttp3.Interceptor");
            return Proxy.newProxyInstance(cl, new Class<?>[]{ifaceCls}, new Handler(cl));
        } catch (Throwable t) {
            throw new IllegalStateException("API Radar: cannot create OkHttp interceptor", t);
        }
    }

    private static class Handler implements InvocationHandler {
        private final ClassLoader cl;
        private final Class<?> chainIface;
        private final Class<?> requestCls;
        private final Class<?> responseCls;
        private final Class<?> headersCls;
        private final Class<?> bodyCls;
        private final Class<?> responseBodyCls;

        Handler(ClassLoader cl) throws Throwable {
            this.cl = cl;
            this.chainIface = cl.loadClass("okhttp3.Interceptor$Chain");
            this.requestCls = cl.loadClass("okhttp3.Request");
            this.responseCls = cl.loadClass("okhttp3.Response");
            this.headersCls = cl.loadClass("okhttp3.Headers");
            this.bodyCls = cl.loadClass("okhttp3.RequestBody");
            this.responseBodyCls = cl.loadClass("okhttp3.ResponseBody");
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!"intercept".equals(method.getName()) || args == null || args.length != 1) {
                return defaultMethod(method, args);
            }
            return handleIntercept(args[0]);
        }

        private Object defaultMethod(Method method, Object[] args) {
            String name = method.getName();
            if ("toString".equals(name)) return "RadarOkHttpInterceptor";
            if ("hashCode".equals(name)) return System.identityHashCode(this);
            if ("equals".equals(name)) return args != null && args.length == 1 && args[0] == this;
            return null;
        }

        private Object handleIntercept(Object chain) throws Throwable {
            Method getRequest = chainIface.getMethod("request");
            Method proceed = chainIface.getMethod("proceed", requestCls);
            Object request = getRequest.invoke(chain);

            if (AgentConfig.paused()) {
                return proceed.invoke(chain, request);
            }

            String url = readUrl(request);
            if (!AgentConfig.shouldCapture(url)) {
                return proceed.invoke(chain, request);
            }

            HttpEvent event = new HttpEvent();
            event.id = UUID.randomUUID().toString();
            event.timestamp = System.currentTimeMillis();
            event.source = "OkHttp";
            event.url = url;
            event.method = (String) requestCls.getMethod("method").invoke(request);
            event.requestHeaders = headersToMap(requestCls.getMethod("headers").invoke(request));
            event.requestBody = readRequestBody(request, event.requestHeaders);

            Object response;
            try {
                response = proceed.invoke(chain, request);
            } catch (Throwable thrown) {
                Throwable real = unwrap(thrown);
                event.error = real.getClass().getSimpleName() + ": " + real.getMessage();
                event.duration = System.currentTimeMillis() - event.timestamp;
                EventReporter.report(event);
                throw real;
            }

            try {
                fillResponse(event, response);
            } catch (Throwable ignored) {
            }
            event.duration = System.currentTimeMillis() - event.timestamp;
            EventReporter.report(event);
            return response;
        }

        private String readUrl(Object request) {
            try {
                Object url = requestCls.getMethod("url").invoke(request);
                return url == null ? null : url.toString();
            } catch (Throwable t) {
                return null;
            }
        }

        private String readRequestBody(Object request, Map<String, String> headers) {
            try {
                Object body = requestCls.getMethod("body").invoke(request);
                if (body == null) return null;
                Class<?> bufferCls = cl.loadClass("okio.Buffer");
                Object buffer = bufferCls.getConstructor().newInstance();
                Class<?> bufferedSinkCls = cl.loadClass("okio.BufferedSink");
                Method writeTo = bodyCls.getMethod("writeTo", bufferedSinkCls);
                writeTo.invoke(body, buffer);
                byte[] bytes = (byte[]) bufferCls.getMethod("readByteArray").invoke(buffer);
                return CaptureUtil.decodeBody(bytes, CaptureUtil.charsetOf(headers));
            } catch (Throwable ignored) {
                return null;
            }
        }

        private void fillResponse(HttpEvent event, Object response) throws Throwable {
            event.responseCode = (int) responseCls.getMethod("code").invoke(response);
            event.responseHeaders = headersToMap(responseCls.getMethod("headers").invoke(response));

            long len = CaptureUtil.contentLengthOf(event.responseHeaders);
            int skip = AgentConfig.skipBodyOverBytes();
            if (len > 0 && len > skip) {
                event.responseBody = "[SKIPPED: content-length=" + len + " exceeds skipBodyOverBytes=" + skip + "]";
                return;
            }

            // peekBody 不消费原 ResponseBody，业务侧可以正常读取
            int max = AgentConfig.maxBodyBytes();
            Method peekBody = responseCls.getMethod("peekBody", long.class);
            Object peek = peekBody.invoke(response, (long) max);
            byte[] bytes = (byte[]) responseBodyCls.getMethod("bytes").invoke(peek);
            event.responseBody = CaptureUtil.decodeBody(bytes, CaptureUtil.charsetOf(event.responseHeaders));
        }

        private Map<String, String> headersToMap(Object headers) {
            Map<String, String> result = new LinkedHashMap<>();
            if (headers == null) return result;
            try {
                int size = (int) headersCls.getMethod("size").invoke(headers);
                Method name = headersCls.getMethod("name", int.class);
                Method value = headersCls.getMethod("value", int.class);
                for (int i = 0; i < size; i++) {
                    String n = (String) name.invoke(headers, i);
                    String v = (String) value.invoke(headers, i);
                    String prev = result.get(n);
                    result.put(n, prev == null ? v : (prev + ", " + v));
                }
            } catch (Throwable ignored) {
            }
            return result;
        }

        private static Throwable unwrap(Throwable t) {
            if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) {
                return t.getCause();
            }
            return t;
        }
    }
}
