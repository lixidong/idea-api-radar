package com.lingshi.apiradar.agent.interceptor;

import com.lingshi.apiradar.agent.AgentConfig;
import com.lingshi.apiradar.agent.transport.EventReporter;
import com.lingshi.apiradar.agent.transport.HttpEvent;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Apache HttpClient 5 (org.apache.hc.*) 的捕获助手。
 *
 * <p>HC5 把请求/响应的 Body 抽象成 {@code HttpEntity}，但接口包搬到了
 * {@code org.apache.hc.core5.http.HttpEntity}，body 替换用
 * {@code BufferedHttpEntity} 同名类。
 */
public final class ApacheHttpClient5Capture {

    private ApacheHttpClient5Capture() {}

    public static HttpEvent onEnter(Object request) {
        if (request == null) return null;
        if (AgentConfig.paused()) return null;
        try {
            String url = readUri(request);
            if (!AgentConfig.shouldCapture(url)) return null;

            HttpEvent event = new HttpEvent();
            event.id = UUID.randomUUID().toString();
            event.timestamp = System.currentTimeMillis();
            event.source = "ApacheHttpClient5";
            event.url = url;
            event.method = invokeStr(request, "getMethod");
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

    private static String invokeStr(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            return v == null ? null : v.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readUri(Object request) {
        try {
            Method m = request.getClass().getMethod("getUri");
            Object v = m.invoke(request);
            return v == null ? null : v.toString();
        } catch (Throwable ignored) {
            // 兜底：拼 scheme + authority + path
            try {
                String path = invokeStr(request, "getPath");
                String scheme = invokeStr(request, "getScheme");
                String authority = invokeStr(request, "getAuthority");
                if (scheme != null && authority != null) {
                    return scheme + "://" + authority + (path == null ? "" : path);
                }
                return path;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static Map<String, String> readHeaders(Object obj) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            Method m = obj.getClass().getMethod("getHeaders");
            Object arr = m.invoke(obj);
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

    private static String readEntityBody(Object holder, Map<String, String> headers) {
        try {
            ClassLoader cl = holder.getClass().getClassLoader();
            Class<?> entityIface = Class.forName("org.apache.hc.core5.http.HttpEntity", false, cl);
            Method getEntity = holder.getClass().getMethod("getEntity");
            Object entity = getEntity.invoke(holder);
            if (entity == null) return null;
            byte[] bytes = consumeEntity(entity, holder, entityIface, cl);
            return CaptureUtil.decodeBody(bytes, CaptureUtil.charsetOf(headers));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void fillResponse(HttpEvent event, Object response) {
        try {
            Method getCode = response.getClass().getMethod("getCode");
            event.responseCode = (int) getCode.invoke(response);
            event.responseHeaders = readHeaders(response);

            long len = CaptureUtil.contentLengthOf(event.responseHeaders);
            int skip = AgentConfig.skipBodyOverBytes();
            if (len > 0 && len > skip) {
                event.responseBody = "[SKIPPED: content-length=" + len + " exceeds skipBodyOverBytes=" + skip + "]";
                return;
            }
            event.responseBody = readEntityBody(response, event.responseHeaders);
        } catch (Throwable ignored) {
        }
    }

    private static byte[] consumeEntity(Object entity, Object holder, Class<?> entityIface, ClassLoader cl) {
        try {
            Method getContent = entityIface.getMethod("getContent");
            Object stream = getContent.invoke(entity);
            byte[] bytes = CaptureUtil.readAll((java.io.InputStream) stream);
            try { ((java.io.InputStream) stream).close(); } catch (Throwable ignored) {}

            Class<?> bufferedCls = Class.forName(
                    "org.apache.hc.core5.http.io.entity.BufferedHttpEntity", false, cl);
            Class<?> byteArrayCls = Class.forName(
                    "org.apache.hc.core5.http.io.entity.ByteArrayEntity", false, cl);
            Class<?> contentTypeCls = Class.forName(
                    "org.apache.hc.core5.http.ContentType", false, cl);
            Object replacement;
            try {
                replacement = bufferedCls.getConstructor(entityIface).newInstance(entity);
            } catch (Throwable t) {
                replacement = byteArrayCls.getConstructor(byte[].class, contentTypeCls)
                        .newInstance(bytes, null);
            }
            Method setEntity = holder.getClass().getMethod("setEntity", entityIface);
            setEntity.invoke(holder, replacement);
            return bytes;
        } catch (Throwable ignored) {
            return new byte[0];
        }
    }
}
