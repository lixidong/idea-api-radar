package io.github.lixidong.apiradar.agent.interceptor;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 拦截 Spring RestTemplate 的请求执行，注入 ClientHttpRequestInterceptor 采集完整数据。
 *
 * <p>策略：用 ByteBuddy 在 {@code RestTemplate.doExecute()} 进入时调用 {@link #ensureInjected}，
 * 反射往 RestTemplate 实例里插入我们的 RadarInterceptor。
 * 后续的执行链由 Spring 自己驱动 interceptor，我们的代码在那里采集数据。
 *
 * <p>同时把原本的 {@code requestFactory} 替换成 {@code BufferingClientHttpRequestFactory}，
 * 使 response body 可重复读取。
 *
 * <p><b>注意</b>：所有被 Advice inline 引用的成员必须是 {@code public}，否则 inline 后的代码
 * 在 RestTemplate 类的访问上下文下会触发 {@link IllegalAccessError}。
 */
public final class RestTemplateInstrumenter {

    /** 已注入过 interceptor 的 RestTemplate 实例集合（弱引用，不阻止 GC）。 */
    public static final Map<Object, Boolean> INJECTED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RestTemplateInstrumenter() {}

    public static void install(Instrumentation inst) {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
                .type(ElementMatchers.named("org.springframework.web.client.RestTemplate"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(DoExecuteAdvice.class)
                                .on(ElementMatchers.named("doExecute")))
                )
                .installOn(inst);

        System.out.println("[API Radar] RestTemplate instrumenter installed");
    }

    /**
     * 由 Advice 调用：确保给定的 RestTemplate 实例已注入 RadarInterceptor。
     * 此方法必须是 {@code public}（详见类注释）。
     */
    public static void ensureInjected(Object restTemplate) {
        try {
            if (INJECTED.containsKey(restTemplate)) return;
            synchronized (INJECTED) {
                if (INJECTED.containsKey(restTemplate)) return;
                inject(restTemplate);
                INJECTED.put(restTemplate, Boolean.TRUE);
            }
        } catch (Throwable t) {
            System.err.println("[API Radar] inject interceptor failed: " + t);
            t.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private static void inject(Object restTemplate) throws Throwable {
        ClassLoader cl = restTemplate.getClass().getClassLoader();
        Object interceptor = RadarInterceptorFactory.create(cl);

        // 1. 注入 ClientHttpRequestInterceptor
        java.lang.reflect.Method getInterceptors =
                restTemplate.getClass().getMethod("getInterceptors");
        List<Object> interceptors = (List<Object>) getInterceptors.invoke(restTemplate);
        interceptors.add(interceptor);

        // 2. 包装 requestFactory 为 BufferingClientHttpRequestFactory
        wrapRequestFactory(restTemplate, cl);

        System.out.println("[API Radar] interceptor injected into RestTemplate@"
                + System.identityHashCode(restTemplate));
    }

    private static void wrapRequestFactory(Object restTemplate, ClassLoader cl) throws Throwable {
        java.lang.reflect.Method getRf =
                restTemplate.getClass().getMethod("getRequestFactory");
        Object rf = getRf.invoke(restTemplate);
        if (rf == null) return;

        Class<?> bufferingCls = cl.loadClass(
                "org.springframework.http.client.BufferingClientHttpRequestFactory");
        if (bufferingCls.isInstance(rf)) return; // 已经包装过

        Class<?> rfIface = cl.loadClass(
                "org.springframework.http.client.ClientHttpRequestFactory");
        Object wrapped = bufferingCls
                .getConstructor(rfIface)
                .newInstance(rf);

        java.lang.reflect.Method setRf =
                restTemplate.getClass().getMethod("setRequestFactory", rfIface);
        setRf.invoke(restTemplate, wrapped);
    }

    /**
     * Advice 类的代码会被 inline 到 RestTemplate.doExecute 字节码里。
     * 这里只调用 outer 类的 public static 方法，避免访问权限问题。
     */
    public static class DoExecuteAdvice {

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.This Object restTemplate) {
            RestTemplateInstrumenter.ensureInjected(restTemplate);
        }
    }
}
