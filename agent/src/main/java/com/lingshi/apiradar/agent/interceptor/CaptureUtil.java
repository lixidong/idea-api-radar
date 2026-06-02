package com.lingshi.apiradar.agent.interceptor;

import com.lingshi.apiradar.agent.AgentConfig;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 各个客户端拦截器共用的反射工具与 body 解码逻辑。
 * <p>所有方法都以 {@code public static} 暴露，方便 Advice 在 inline 后调用，
 * 同时所有反射调用都通过传入的 Method 实例进行，由各 instrumenter 自行确保
 * Method 来自 <b>public 接口</b> 以避免 IllegalAccess。
 */
public final class CaptureUtil {

    private CaptureUtil() {}

    public static String invokeStr(Method m, Object target) {
        try {
            Object v = m.invoke(target);
            return v == null ? null : v.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object invoke(Method m, Object target) {
        try {
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object invoke(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 解码 body bytes，遵循 maxBodyBytes 截断规则。 */
    public static String decodeBody(byte[] body, String charsetName) {
        if (body == null || body.length == 0) return null;
        String charset = charsetName == null ? "UTF-8" : charsetName;
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

    /** 从 Content-Type 头里提取 charset，失败回退 UTF-8。 */
    public static String charsetOf(Map<String, String> headers) {
        String contentType = headerCi(headers, "Content-Type");
        if (contentType == null) return "UTF-8";
        String lc = contentType.toLowerCase();
        int idx = lc.indexOf("charset=");
        if (idx < 0) return "UTF-8";
        String cs = lc.substring(idx + 8).trim();
        int semi = cs.indexOf(';');
        if (semi > 0) cs = cs.substring(0, semi).trim();
        return cs.isEmpty() ? "UTF-8" : cs;
    }

    public static String headerCi(Map<String, String> headers, String key) {
        if (headers == null) return null;
        String v = headers.get(key);
        if (v != null) return v;
        return headers.get(key.toLowerCase());
    }

    public static long contentLengthOf(Map<String, String> headers) {
        String v = headerCi(headers, "Content-Length");
        if (v == null) return -1;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return -1; }
    }

    /** 把 InputStream 一次性读完。 */
    public static byte[] readAll(java.io.InputStream input) {
        if (input == null) return new byte[0];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            int n;
            while ((n = input.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Throwable ignored) {
        }
        return out.toByteArray();
    }

    public static Map<String, String> newHeaderMap() {
        return new LinkedHashMap<>();
    }
}
