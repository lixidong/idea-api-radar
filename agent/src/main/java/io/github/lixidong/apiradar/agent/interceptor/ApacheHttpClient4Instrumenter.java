package io.github.lixidong.apiradar.agent.interceptor;

import io.github.lixidong.apiradar.agent.transport.HttpEvent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

/**
 * 拦截 Apache HttpClient 4 的执行链入口 {@code HttpRequestExecutor.execute(...)}。
 * <p>该方法是所有 4.x 实现汇聚的最低层：CloseableHttpClient / InternalHttpClient
 * 最终都走到 RequestExecutor 这里执行 HTTP 协议。
 */
public final class ApacheHttpClient4Instrumenter {

    private ApacheHttpClient4Instrumenter() {}

    public static void install(Instrumentation inst) {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
                .type(ElementMatchers.named("org.apache.http.protocol.HttpRequestExecutor"))
                .transform((builder, td, cl, module, pd) ->
                        builder.visit(Advice.to(ExecuteAdvice.class)
                                .on(ElementMatchers.named("execute").and(ElementMatchers.isPublic()))))
                .installOn(inst);

        System.out.println("[API Radar] Apache HttpClient 4 instrumenter installed");
    }

    public static class ExecuteAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static HttpEvent onEnter(@Advice.Argument(0) Object request) {
            return ApacheHttpClient4Capture.onEnter(request);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Return Object response,
                                  @Advice.Thrown Throwable thrown,
                                  @Advice.Enter HttpEvent event) {
            ApacheHttpClient4Capture.onExit(event, response, thrown);
        }
    }
}
