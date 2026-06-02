package com.lingshi.apiradar.agent.interceptor;

import com.lingshi.apiradar.agent.AgentConfig;
import com.lingshi.apiradar.agent.transport.EventReporter;
import com.lingshi.apiradar.agent.transport.HttpEvent;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JDK 内置 {@code java.net.http.HttpClient} 的捕获助手。
 *
 * <p>限制：
 * <ul>
 *     <li>RequestBody 是流式 {@code BodyPublisher}，订阅会消费。这里只记录类型元信息，
 *         不读取内容（避免破坏业务发送）。</li>
 *     <li>ResponseBody 是用户传入的 {@code BodyHandler} 的结果；仅当结果为
 *         String / byte[] 时记录，其他类型仅标注类名。</li>
 * </ul>
 */
public final class JdkHttpClientCapture {

    private JdkHttpClientCapture() {}

    public static HttpEvent onEnter(Object request) {
        if (request == null) return null;
        if (AgentConfig.paused()) return null;
        try {
            String url = invokeStr(request, "uri");
            if (!AgentConfig.shouldCapture(url)) return null;

            HttpEvent event = new HttpEvent();
            event.id = UUID.randomUUID().toString();
            event.timestamp = System.currentTimeMillis();
            event.source = "JdkHttpClient";
            event.url = url;
            event.method = invokeStr(request, "method");
            event.requestHeaders = readHttpHeaders(invoke(request, "headers"));
            event.requestBody = describeBodyPublisher(request);
            return event;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void onExit(HttpEvent event, Object response, Throwable thrown) {
        if (event == null) return;
        try {
            event.duration = System.currentTimeMillis() - event.timestamp;
            if (thrown != null) {
                event.error = thrown.getClass().getSimpleName() + ": " + thrown.getMessage();
            } else if (response != null) {
                fillResponse(event, response);
            }
        } catch (Throwable ignored) {
        } finally {
            EventReporter.report(event);
        }
    }

    private static Object invoke(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String invokeStr(Object target, String methodName) {
        Object v = invoke(target, methodName);
        return v == null ? null : v.toString();
    }

    /** java.net.http.HttpHeaders → Map<String,String> */
    @SuppressWarnings("unchecked")
    private static Map<String, String> readHttpHeaders(Object headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null) return result;
        try {
            Object map = headers.getClass().getMethod("map").invoke(headers);
            if (!(map instanceof Map)) return result;
            for (Map.Entry<String, List<String>> e :
                    ((Map<String, List<String>>) map).entrySet()) {
                List<String> values = e.getValue();
                result.put(e.getKey(), values == null ? "" : String.join(", ", values));
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static String describeBodyPublisher(Object request) {
        try {
            Object opt = request.getClass().getMethod("bodyPublisher").invoke(request);
            if (opt == null) return null;
            Object present = opt.getClass().getMethod("isPresent").invoke(opt);
            if (!(present instanceof Boolean) || !((Boolean) present)) return null;
            Object publisher = opt.getClass().getMethod("get").invoke(opt);
            if (publisher == null) return null;
            long len = -1;
            try {
                Object lenObj = publisher.getClass().getMethod("contentLength").invoke(publisher);
                if (lenObj instanceof Long) len = (Long) lenObj;
            } catch (Throwable ignored) {}
            return "[BodyPublisher: " + publisher.getClass().getName()
                    + (len >= 0 ? ", length=" + len : "") + "]";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void fillResponse(HttpEvent event, Object response) {
        try {
            Object code = response.getClass().getMethod("statusCode").invoke(response);
            if (code instanceof Integer) event.responseCode = (Integer) code;

            event.responseHeaders = readHttpHeaders(invoke(response, "headers"));

            long len = CaptureUtil.contentLengthOf(event.responseHeaders);
            int skip = AgentConfig.skipBodyOverBytes();
            if (len > 0 && len > skip) {
                event.responseBody = "[SKIPPED: content-length=" + len + " exceeds skipBodyOverBytes=" + skip + "]";
                return;
            }

            Object body = response.getClass().getMethod("body").invoke(response);
            if (body == null) return;
            if (body instanceof String) {
                event.responseBody = (String) body;
                return;
            }
            if (body instanceof byte[]) {
                event.responseBody = CaptureUtil.decodeBody(
                        (byte[]) body, CaptureUtil.charsetOf(event.responseHeaders));
                return;
            }
            event.responseBody = "[Body type: " + body.getClass().getName() + "]";
        } catch (Throwable ignored) {
        }
    }
}
