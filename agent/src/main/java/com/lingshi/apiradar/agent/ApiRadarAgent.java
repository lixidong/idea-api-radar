package com.lingshi.apiradar.agent;

import com.lingshi.apiradar.agent.interceptor.ApacheHttpClient4Instrumenter;
import com.lingshi.apiradar.agent.interceptor.ApacheHttpClient5Instrumenter;
import com.lingshi.apiradar.agent.interceptor.JdkHttpClientInstrumenter;
import com.lingshi.apiradar.agent.interceptor.OkHttpInstrumenter;
import com.lingshi.apiradar.agent.interceptor.RestTemplateInstrumenter;
import com.lingshi.apiradar.agent.transport.EventReporter;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Agent 入口。
 *
 * <p>启动参数（逗号分隔的 key=value，多值用 {@code ;} 分隔，url 编码无关字符均按字面量传递）：
 * <ul>
 *   <li>{@code port}：plugin 监听端口，默认 7777</li>
 *   <li>{@code maxBodyBytes}：单 body 最大采集字节，默认 10MB</li>
 *   <li>{@code skipBodyOverBytes}：超过该值的 body 不读取内容（仅记元数据），默认不跳过</li>
 *   <li>{@code includes}：URL 白名单（glob，多个 ; 分隔）</li>
 *   <li>{@code excludes}：URL 黑名单（glob，多个 ; 分隔）</li>
 * </ul>
 */
public class ApiRadarAgent {

    public static void premain(String args, Instrumentation inst) {
        start(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        start(args, inst);
    }

    private static void start(String args, Instrumentation inst) {
        int port = parseInt(args, "port", 7777);
        int maxBodyBytes = parseInt(args, "maxBodyBytes", AgentConfig.DEFAULT_MAX_BODY_BYTES);
        int skipBodyOver = parseInt(args, "skipBodyOverBytes", AgentConfig.DEFAULT_SKIP_BODY_OVER);
        List<String> includes = parseList(args, "includes");
        List<String> excludes = parseList(args, "excludes");

        AgentConfig.init(port, maxBodyBytes, skipBodyOver, includes, excludes);

        System.out.println("[API Radar] agent starting"
                + ", port=" + port
                + ", maxBodyBytes=" + maxBodyBytes
                + ", skipBodyOverBytes=" + skipBodyOver
                + ", includes=" + includes
                + ", excludes=" + excludes);

        EventReporter.init(port);
        safeInstall("RestTemplate", () -> RestTemplateInstrumenter.install(inst));
        safeInstall("ApacheHttpClient4", () -> ApacheHttpClient4Instrumenter.install(inst));
        safeInstall("ApacheHttpClient5", () -> ApacheHttpClient5Instrumenter.install(inst));
        safeInstall("OkHttp", () -> OkHttpInstrumenter.install(inst));
        safeInstall("JdkHttpClient", () -> JdkHttpClientInstrumenter.install(inst));

        System.out.println("[API Radar] agent started");
    }

    private static void safeInstall(String name, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            System.err.println("[API Radar] " + name + " instrumenter install failed: " + t);
        }
    }

    private static int parseInt(String args, String key, int defaultValue) {
        String v = parseString(args, key);
        if (v == null) return defaultValue;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static String parseString(String args, String key) {
        if (args == null || args.isEmpty()) return null;
        for (String kv : args.split(",")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            if (key.equals(kv.substring(0, eq).trim())) {
                return kv.substring(eq + 1);
            }
        }
        return null;
    }

    private static List<String> parseList(String args, String key) {
        String v = parseString(args, key);
        if (v == null || v.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String s : v.split(";")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }
}
