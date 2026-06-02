package com.lingshi.apiradar.agent.interceptor;

import com.lingshi.apiradar.agent.AgentConfig;
import com.lingshi.apiradar.agent.transport.EventReporter;
import com.lingshi.apiradar.agent.transport.HttpEvent;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Apache HttpClient 4 拦截器的捕获助手。
 * <p>Advice 在 {@code HttpRequestExecutor.execute} 进出时调用本类的 public static 方法。
 * 所有 Apache 类都通过反射访问，避免 agent ClassLoader 看不见目标类。
 *
 * <p>请求体：从 {@code HttpEntityEnclosingRequest.getEntity()} 反射读取；读完后用
 * {@code BufferedHttpEntity} 包装重新 set 回去，业务侧可重复消费。
 * 响应体：同上。
 */
public final class ApacheHttpClient4Capture {

    private ApacheHttpClient4Capture() {}

    public static HttpEvent onEnter(Object request) {
        if (request == null) return null;
        if (AgentConfig.paused()) return null;

        try {
            String url = readUri(request);
            if (!AgentConfig.shouldCapture(url)) return null;

            HttpEvent event = new HttpEvent();
            event.id = UUID.randomUUID().toString();
            event.timestamp = System.currentTimeMillis();
            event.source = "ApacheHttpClient4";
            event.url = url;
            event.method = readMethod(request);
            event.requestHeaders = readHeaders(request);
            event.requestBody = readEntityBody(request, event.requestHeaders);
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

    private static String readMethod(Object request) {
        try {
            Class<?> reqLine = Class.forName("org.apache.http.RequestLine", false, request.getClass().getClassLoader());
            Method getRequestLine = request.getClass().getMethod("getRequestLine");
            Object line = getRequestLine.invoke(request);
            if (line == null) return null;
            Method getMethod = reqLine.getMethod("getMethod");
            Object v = getMethod.invoke(line);
            return v == null ? null : v.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readUri(Object request) {
        try {
            Method getRequestLine = request.getClass().getMethod("getRequestLine");
            Object line = getRequestLine.invoke(request);
            if (line == null) return null;
            Class<?> reqLine = Class.forName("org.apache.http.RequestLine", false, request.getClass().getClassLoader());
            Method getUri = reqLine.getMethod("getUri");
            Object v = getUri.invoke(line);
            return v == null ? null : v.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Map<String, String> readHeaders(Object obj) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            Method getAllHeaders = obj.getClass().getMethod("getAllHeaders");
            Object arr = getAllHeaders.invoke(obj);
            if (!(arr instanceof Object[])) return result;
            for (Object h : (Object[]) arr) {
                if (h == null) continue;
                Method getName = h.getClass().getMethod("getName");
                Method getValue = h.getClass().getMethod("getValue");
                String name = String.valueOf(getName.invoke(h));
                String value = String.valueOf(getValue.invoke(h));
                String prev = result.get(name);
                result.put(name, prev == null ? value : (prev + ", " + value));
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    /** 如果是 HttpEntityEnclosingRequest，读取并替换为 BufferedHttpEntity。 */
    private static String readEntityBody(Object request, Map<String, String> headers) {
        try {
            Class<?> enclosingIface = Class.forName(
                    "org.apache.http.HttpEntityEnclosingRequest", false, request.getClass().getClassLoader());
            if (!enclosingIface.isInstance(request)) return null;

            Method getEntity = enclosingIface.getMethod("getEntity");
            Object entity = getEntity.invoke(request);
            if (entity == null) return null;

            byte[] bytes = consumeEntity(entity, request, enclosingIface, /* isResponse= */ false);
            return CaptureUtil.decodeBody(bytes, CaptureUtil.charsetOf(headers));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void fillResponse(HttpEvent event, Object response) {
        try {
            Method getStatusLine = response.getClass().getMethod("getStatusLine");
            Object line = getStatusLine.invoke(response);
            if (line != null) {
                Class<?> statusLine = Class.forName(
                        "org.apache.http.StatusLine", false, response.getClass().getClassLoader());
                Method getStatusCode = statusLine.getMethod("getStatusCode");
                event.responseCode = (int) getStatusCode.invoke(line);
            }

            event.responseHeaders = readHeaders(response);

            long len = CaptureUtil.contentLengthOf(event.responseHeaders);
            int skip = AgentConfig.skipBodyOverBytes();
            if (len > 0 && len > skip) {
                event.responseBody = "[SKIPPED: content-length=" + len + " exceeds skipBodyOverBytes=" + skip + "]";
                return;
            }

            Method getEntity = response.getClass().getMethod("getEntity");
            Object entity = getEntity.invoke(response);
            if (entity == null) return;

            byte[] bytes = consumeEntity(entity, response, null, /* isResponse= */ true);
            event.responseBody = CaptureUtil.decodeBody(bytes, CaptureUtil.charsetOf(event.responseHeaders));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 读完 entity 内容并用 BufferedHttpEntity 替换回原 holder，保证业务侧可二次消费。
     * @param entity        原 HttpEntity
     * @param holder        持有该 entity 的对象（HttpRequest 或 HttpResponse）
     * @param requestIface  当 holder 是 request 时传入 HttpEntityEnclosingRequest.class
     */
    private static byte[] consumeEntity(Object entity, Object holder, Class<?> requestIface, boolean isResponse) {
        try {
            ClassLoader cl = entity.getClass().getClassLoader();
            Class<?> entityIface = Class.forName("org.apache.http.HttpEntity", false, cl);

            Method getContent = entityIface.getMethod("getContent");
            Object stream = getContent.invoke(entity);
            byte[] bytes = CaptureUtil.readAll((java.io.InputStream) stream);
            try { ((java.io.InputStream) stream).close(); } catch (Throwable ignored) {}

            // 用 BufferedHttpEntity 包装，让业务可重复消费
            Class<?> bufferedCls = Class.forName("org.apache.http.entity.BufferedHttpEntity", false, cl);
            Class<?> byteArrayCls = Class.forName("org.apache.http.entity.ByteArrayEntity", false, cl);
            Object replacement;
            try {
                replacement = bufferedCls.getConstructor(entityIface).newInstance(entity);
            } catch (Throwable t) {
                // BufferedHttpEntity 需要原 entity 可再次 getContent，否则用 ByteArrayEntity 兜底
                replacement = byteArrayCls.getConstructor(byte[].class).newInstance((Object) bytes);
            }

            if (isResponse) {
                Method setEntity = holder.getClass().getMethod("setEntity", entityIface);
                setEntity.invoke(holder, replacement);
            } else if (requestIface != null) {
                Method setEntity = requestIface.getMethod("setEntity", entityIface);
                setEntity.invoke(holder, replacement);
            }
            return bytes;
        } catch (Throwable ignored) {
            return new byte[0];
        }
    }
}
