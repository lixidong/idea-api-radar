# API Radar

IntelliJ IDEA 插件：**零代码侵入**捕获 Java 项目中的三方 HTTP 接口请求与响应，免去手动加 log/断点的麻烦。

## 模块结构

```
api-radar/
├── plugin/   IDEA 插件（Kotlin，目标 IDEA 2026.1+）
├── agent/    Java Agent（Java 11，ByteBuddy 拦截 HTTP 客户端）
└── ...
```

## 支持的 HTTP 客户端（路线图）

- [x] Spring RestTemplate（Phase 1 - 骨架，待补 body 采集）
- [ ] Apache HttpClient 4/5（Phase 3）
- [ ] OkHttp（Phase 4）
- [ ] JDK HttpClient (11+)（Phase 4）
- [ ] Spring WebClient（Phase 4）
- [ ] Feign（Phase 4）

## 构建与运行

### 前置要求
- JDK 17+（构建插件）
- IntelliJ IDEA 2026.1.x

### 初始化 Gradle Wrapper
首次拉取项目后，在项目根目录执行（需本机已安装 Gradle 8.x）：

```bash
gradle wrapper --gradle-version 8.11
```

### 构建 Agent fat jar
```bash
./gradlew :agent:agentJar
```

产物：`agent/build/libs/api-radar-agent-*-all.jar`

### 本地运行 IDEA 插件（沙箱）
```bash
./gradlew :plugin:runIde
```

### 打包插件分发文件
```bash
./gradlew :plugin:buildPlugin
```

产物：`plugin/build/distributions/api-radar-*.zip`

## 使用方式

1. 安装插件（沙箱 IDEA 或正式 IDEA 均可）
2. 启动你的 Java 项目（JDK 21 需加 VM 参数 `-Djdk.attach.allowAttachSelf=true`）
3. 菜单 **Tools → Attach API Radar**，选择目标进程
4. 打开底部 Tool Window **API Radar**，触发业务接口调用，事件会实时出现在列表

## 通信协议

Agent 与 plugin 通过本地 TCP 通信（默认端口 7777），单行 JSON：

```json
{"id":"...","timestamp":...,"source":"RestTemplate","method":"POST","url":"...","requestHeaders":{...},"requestBody":"...","responseCode":200,"responseHeaders":{...},"responseBody":"...","duration":156,"error":null}
```

## 开发说明

详细架构与设计文档见 `docs/`（待补充）。
