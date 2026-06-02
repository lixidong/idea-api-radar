package io.github.lixidong.apiradar.agent.transport;

import java.util.Map;

/**
 * 极简 JSON 序列化（仅用于 HttpEvent），避免 agent 引入额外依赖与目标项目冲突。
 */
public final class JsonWriter {
    private JsonWriter() {}

    public static String toJson(HttpEvent e) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        appendStr(sb, "id", e.id); sb.append(',');
        sb.append("\"timestamp\":").append(e.timestamp).append(',');
        appendStr(sb, "source", e.source); sb.append(',');
        appendStr(sb, "method", e.method); sb.append(',');
        appendStr(sb, "url", e.url); sb.append(',');
        appendMap(sb, "requestHeaders", e.requestHeaders); sb.append(',');
        appendStr(sb, "requestBody", e.requestBody); sb.append(',');
        sb.append("\"responseCode\":").append(e.responseCode).append(',');
        appendMap(sb, "responseHeaders", e.responseHeaders); sb.append(',');
        appendStr(sb, "responseBody", e.responseBody); sb.append(',');
        sb.append("\"duration\":").append(e.duration).append(',');
        appendStr(sb, "error", e.error);
        sb.append('}');
        return sb.toString();
    }

    private static void appendStr(StringBuilder sb, String key, String val) {
        sb.append('"').append(key).append("\":");
        if (val == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escape(val)).append('"');
        }
    }

    private static void appendMap(StringBuilder sb, String key, Map<String, String> map) {
        sb.append('"').append(key).append("\":{");
        if (map != null) {
            boolean first = true;
            for (Map.Entry<String, String> entry : map.entrySet()) {
                if (!first) sb.append(',');
                sb.append('"').append(escape(entry.getKey())).append("\":\"")
                  .append(escape(entry.getValue() == null ? "" : entry.getValue())).append('"');
                first = false;
            }
        }
        sb.append('}');
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
