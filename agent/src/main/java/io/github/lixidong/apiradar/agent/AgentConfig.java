package io.github.lixidong.apiradar.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Agent 全局运行时配置。
 * <p>{@code port} 在启动时确定，运行期不变；其余项支持通过 control 消息热更新。
 */
public final class AgentConfig {

    public static final int DEFAULT_MAX_BODY_BYTES = 10 * 1024 * 1024;       // 10MB
    public static final int DEFAULT_SKIP_BODY_OVER = Integer.MAX_VALUE;      // 默认不跳过

    private static int port = 7777;

    private static volatile int maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
    private static volatile int skipBodyOverBytes = DEFAULT_SKIP_BODY_OVER;
    private static volatile List<Pattern> includes = Collections.emptyList();
    private static volatile List<Pattern> excludes = Collections.emptyList();

    private static final AtomicBoolean paused = new AtomicBoolean(false);

    private AgentConfig() {}

    public static void init(int port,
                            int maxBodyBytes,
                            int skipBodyOverBytes,
                            List<String> includes,
                            List<String> excludes) {
        AgentConfig.port = port;
        setMaxBodyBytes(maxBodyBytes);
        setSkipBodyOverBytes(skipBodyOverBytes);
        setIncludes(includes);
        setExcludes(excludes);
    }

    public static int port() { return port; }

    public static int maxBodyBytes() { return maxBodyBytes; }
    public static void setMaxBodyBytes(int v) {
        maxBodyBytes = v <= 0 ? DEFAULT_MAX_BODY_BYTES : v;
    }

    public static int skipBodyOverBytes() { return skipBodyOverBytes; }
    public static void setSkipBodyOverBytes(int v) {
        skipBodyOverBytes = v <= 0 ? DEFAULT_SKIP_BODY_OVER : v;
    }

    public static boolean paused() { return paused.get(); }
    public static void setPaused(boolean v) { paused.set(v); }

    public static void setIncludes(List<String> patterns) {
        includes = compile(patterns);
    }
    public static void setExcludes(List<String> patterns) {
        excludes = compile(patterns);
    }

    /**
     * 判断 URL 是否允许采集。规则：
     * <ul>
     *     <li>excludes 命中 → 拒绝</li>
     *     <li>includes 非空且未命中 → 拒绝</li>
     *     <li>其他情况 → 允许</li>
     * </ul>
     */
    public static boolean shouldCapture(String url) {
        if (url == null) return true;
        for (Pattern p : excludes) {
            if (p.matcher(url).find()) return false;
        }
        if (!includes.isEmpty()) {
            for (Pattern p : includes) {
                if (p.matcher(url).find()) return true;
            }
            return false;
        }
        return true;
    }

    private static List<Pattern> compile(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return Collections.emptyList();
        List<Pattern> out = new ArrayList<>(patterns.size());
        for (String s : patterns) {
            if (s == null || s.isEmpty()) continue;
            try {
                out.add(Pattern.compile(globToRegex(s.trim())));
            } catch (Throwable ignored) {}
        }
        return Collections.unmodifiableList(out);
    }

    /** 极简 glob → regex：只支持 *（任意字符）。其余字符按字面量处理。 */
    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() + 8);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else if ("\\.+?()[]{}|^$".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
