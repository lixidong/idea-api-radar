package com.lingshi.apiradar.agent.interceptor;

import com.lingshi.apiradar.agent.transport.HttpEvent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

/**
 * 拦截 JDK 内置 HttpClient 的实现类 {@code jdk.internal.net.http.HttpClientImpl}。
 *
 * <p>注意：JDK 17+ 上，目标项目可能需要启动参数
 * {@code --add-opens java.net.http/jdk.internal.net.http=ALL-UNNAMED}
 * 才能让 ByteBuddy 顺利 retransform。如果失败会在控制台打印警告，业务不受影响。
 */
public final class JdkHttpClientInstrumenter {

    private JdkHttpClientInstrumenter() {}

    public static void install(Instrumentation inst) {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
                .type(ElementMatchers.named("jdk.internal.net.http.HttpClientImpl"))
                .transform((builder, td, cl, module, pd) ->
                        builder.visit(Advice.to(SendAdvice.class)
                                .on(ElementMatchers.named("send")
                                        .and(ElementMatchers.takesArguments(2)))))
                .installOn(inst);

        System.out.println("[API Radar] JDK HttpClient instrumenter installed");
    }

    public static class SendAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static HttpEvent onEnter(@Advice.Argument(0) Object request) {
            return JdkHttpClientCapture.onEnter(request);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Return Object response,
                                  @Advice.Thrown Throwable thrown,
                                  @Advice.Enter HttpEvent event) {
            JdkHttpClientCapture.onExit(event, response, thrown);
        }
    }
}
