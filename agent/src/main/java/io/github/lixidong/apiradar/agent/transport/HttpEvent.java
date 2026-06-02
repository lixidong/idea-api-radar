package io.github.lixidong.apiradar.agent.transport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从 agent 上报给 plugin 的 HTTP 调用事件。
 * 与 plugin 端的 HttpEvent 字段保持一致。
 */
public class HttpEvent {
    public String id;
    public long timestamp;
    public String source;        // RestTemplate / OkHttp / ApacheHttpClient ...
    public String method;
    public String url;
    public Map<String, String> requestHeaders = new LinkedHashMap<>();
    public String requestBody;
    public int responseCode;
    public Map<String, String> responseHeaders = new LinkedHashMap<>();
    public String responseBody;
    public long duration;        // ms
    public String error;         // 异常信息（如有）
}
