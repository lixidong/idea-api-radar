package io.github.lixidong.apiradar.agent.interceptor;

import io.github.lixidong.apiradar.agent.transport.HttpEvent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

/**
 * 拦截 Apache HttpClient 5 的 {@code HttpRequestExecutor.execute(...)}。
 */
public final class ApacheHttpClient5Instrumenter {

    private ApacheHttpClient5Instrumenter() {}

    public static void install(Instrumentation inst) {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
                .type(ElementMatchers.named("org.apache.hc.core5.http.impl.io.HttpRequestExecutor"))
                .transform((builder, td, cl, module, pd) ->
                        builder.visit(Advice.to(ExecuteAdvice.class)
                                .on(ElementMatchers.named("execute").and(ElementMatchers.isPublic()))))
                .installOn(inst);

        System.out.println("[API Radar] Apache HttpClient 5 instrumenter installed");
    }

    public static class ExecuteAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static HttpEvent onEnter(@Advice.Argument(0) Object request) {
            return ApacheHttpClient5Capture.onEnter(request);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Return Object response,
                                  @Advice.Thrown Throwable thrown,
                                  @Advice.Enter HttpEvent event) {
            ApacheHttpClient5Capture.onExit(event, response, thrown);
        }
    }
}
