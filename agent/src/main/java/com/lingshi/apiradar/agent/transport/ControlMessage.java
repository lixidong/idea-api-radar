package com.lingshi.apiradar.agent.transport;

import com.lingshi.apiradar.agent.AgentConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Plugin → Agent 的控制消息处理器。
 *
 * <p>协议：每行一条 JSON，键采用扁平化结构，字段缺失则忽略。
 * 例如：{@code {"paused":true,"maxBodyBytes":10485760,"skipBodyOverBytes":1048576,
 * "includes":["*api*"],"excludes":["*health*"]}}
 *
 * <p>为避免 agent 引入第三方依赖（与目标项目冲突），这里手写极简解析器。
 * 仅支持顶层对象，值类型限定为：boolean / int / 字符串 / 字符串数组。
 */
public final class ControlMessage {

    private ControlMessage() {}

    public static void apply(String line) {
        if (line == null) return;
        try {
            new Parser(line).parse();
        } catch (Throwable t) {
            System.err.println("[API Radar] parse control failed: " + line + " -> " + t);
        }
    }

    private static class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
            this.i = 0;
        }

        void parse() {
            skipWs();
            if (i >= s.length() || s.charAt(i) != '{') return;
            i++;
            while (i < s.length()) {
                skipWs();
                if (i >= s.length() || s.charAt(i) == '}') return;
                if (s.charAt(i) != '"') return;
                String key = readString();
                skipWs();
                if (i >= s.length() || s.charAt(i) != ':') return;
                i++;
                skipWs();
                if (i >= s.length()) return;
                char c = s.charAt(i);
                if (c == '"') {
                    dispatchString(key, readString());
                } else if (c == '[') {
                    dispatchArray(key, readStringArray());
                } else if (c == 't' || c == 'f') {
                    boolean isTrue = c == 't';
                    i += isTrue ? 4 : 5;
                    dispatchBoolean(key, isTrue);
                } else if (c == 'n') {
                    i += 4; // null
                } else {
                    dispatchNumber(key, readNumber());
                }
                skipWs();
                if (i < s.length() && s.charAt(i) == ',') i++;
            }
        }

        private String readString() {
            // 当前 i 指向开始的 '"'
            i++;
            StringBuilder out = new StringBuilder();
            while (i < s.length() && s.charAt(i) != '"') {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    out.append(unescape(s.charAt(i + 1)));
                    i += 2;
                } else {
                    out.append(c);
                    i++;
                }
            }
            if (i < s.length()) i++; // 跳过 closing '"'
            return out.toString();
        }

        private List<String> readStringArray() {
            i++; // 跳过 '['
            List<String> list = new ArrayList<>();
            while (i < s.length() && s.charAt(i) != ']') {
                skipWs();
                if (i < s.length() && s.charAt(i) == '"') {
                    list.add(readString());
                }
                skipWs();
                if (i < s.length() && s.charAt(i) == ',') i++;
            }
            if (i < s.length()) i++; // 跳过 ']'
            return list;
        }

        private String readNumber() {
            int start = i;
            if (i < s.length() && s.charAt(i) == '-') i++;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            return s.substring(start, i);
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }

    private static char unescape(char c) {
        switch (c) {
            case 'n': return '\n';
            case 't': return '\t';
            case 'r': return '\r';
            case '"': return '"';
            case '\\': return '\\';
            default: return c;
        }
    }

    private static void dispatchBoolean(String key, boolean v) {
        if ("paused".equals(key)) {
            AgentConfig.setPaused(v);
            System.out.println("[API Radar] control: paused=" + v);
        }
    }

    private static void dispatchNumber(String key, String num) {
        try {
            int v = Integer.parseInt(num.trim());
            if ("maxBodyBytes".equals(key)) {
                AgentConfig.setMaxBodyBytes(v);
                System.out.println("[API Radar] control: maxBodyBytes=" + v);
            } else if ("skipBodyOverBytes".equals(key)) {
                AgentConfig.setSkipBodyOverBytes(v);
                System.out.println("[API Radar] control: skipBodyOverBytes=" + v);
            }
        } catch (NumberFormatException ignored) {}
    }

    private static void dispatchString(String key, String v) {
        // 暂无字符串型控制字段
    }

    private static void dispatchArray(String key, List<String> list) {
        if ("includes".equals(key)) {
            AgentConfig.setIncludes(list);
            System.out.println("[API Radar] control: includes=" + list);
        } else if ("excludes".equals(key)) {
            AgentConfig.setExcludes(list);
            System.out.println("[API Radar] control: excludes=" + list);
        }
    }
}
