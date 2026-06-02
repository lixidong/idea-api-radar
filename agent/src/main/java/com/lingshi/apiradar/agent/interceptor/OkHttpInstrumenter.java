package com.lingshi.apiradar.agent.interceptor;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 拦截 {@code OkHttpClient.Builder.build()}，在 build 前往 networkInterceptors
 * 列表里注入我们的代理拦截器。
 *
 * <p>NetworkInterceptor 能看到经过 Bridge/RetryAndFollowUp 处理后的真实 HTTP 请求与响应，
 * 这正是我们想要的数据。
 *
 * <p>{@link #INJECTED} 用 WeakHashMap 标记已注入过的 Builder 实例，避免反复注入。
 */
public final class OkHttpInstrumenter {

    public static final Map<Object, Boolean> INJECTED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private OkHttpInstrumenter() {}

    public static void install(Instrumentation inst) {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
                .type(ElementMatchers.named("okhttp3.OkHttpClient$Builder"))
                .transform((builder, td, cl, module, pd) ->
                        builder.visit(Advice.to(BuildAdvice.class)
                                .on(ElementMatchers.named("build"))))
                .installOn(inst);

        System.out.println("[API Radar] OkHttp instrumenter installed");
    }

    @SuppressWarnings("unchecked")
    public static void ensureInjected(Object okhttpBuilder) {
        try {
            if (INJECTED.containsKey(okhttpBuilder)) return;
            synchronized (INJECTED) {
                if (INJECTED.containsKey(okhttpBuilder)) return;
                ClassLoader cl = okhttpBuilder.getClass().getClassLoader();
                Object interceptor = OkHttpInterceptorFactory.create(cl);
                // Builder.networkInterceptors() 返回内部可变 List
                List<Object> list = (List<Object>) okhttpBuilder.getClass()
                        .getMethod("networkInterceptors").invoke(okhttpBuilder);
                if (!list.contains(interceptor)) list.add(interceptor);
                INJECTED.put(okhttpBuilder, Boolean.TRUE);
                System.out.println("[API Radar] OkHttp interceptor injected into Builder@"
                        + System.identityHashCode(okhttpBuilder));
            }
        } catch (Throwable t) {
            System.err.println("[API Radar] OkHttp inject failed: " + t);
        }
    }

    public static class BuildAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(@Advice.This Object builder) {
            OkHttpInstrumenter.ensureInjected(builder);
        }
    }
}
