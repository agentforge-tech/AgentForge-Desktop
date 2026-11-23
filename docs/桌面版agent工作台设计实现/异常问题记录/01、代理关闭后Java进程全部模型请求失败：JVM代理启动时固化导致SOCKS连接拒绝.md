# 01、代理关闭后 Java 进程全部模型请求失败：JVM 代理启动时固化导致 SOCKS 连接拒绝

> 适用范围：AgentForge Desktop（Electron + 本地 Java Runtime）
>
> 涉及模块：`AgentForge-Desktop`（Electron 启动器、本地服务）、`AgentForge`（`agentforge-model-core` / `agentforge-model-openai`）
>
> 结论一句话：**是这个问题。服务启动时 JVM 固化了 `127.0.0.1:7891` 的 SOCKS/HTTP 代理属性，之后关闭代理但没有重启服务，JVM 仍会继续连接这个已关闭的端口，所有模型渠道一起失败。curl 不读取 JVM 的代理属性，所以直连正常。**

---

## 1. 问题现象

用户本地工作台一直打开，最初使用 love-deepseek-v4.1-flash 渠道正常，后来出现：

- 桌面端所有模型请求失败；
- UI 只显示 `OpenAI streaming request failed`，看不出真实原因；
- 同一台机器、同一个 Key、同一个 Base URL 和模型名，命令行 `curl` 直连 `https://lovetokenai.xyz/v1/chat/completions` 正常返回 200。

复现请求（桌面端）：

```bash
curl 'http://127.0.0.1:43179/api/chat/tasks/submit' \
  -H 'Content-Type: application/json' \
  --data-raw '{"aid":"agt_default","sessionId":"","message":"你好","modelId":6,"workspaceId":null,"projectPath":null,"files":[]}'
```

对应模型配置：

| 配置项 | 值 |
| --- | --- |
| 名称 | `love-deepseek-v4.1-flash` |
| provider | `1`（OpenAI 兼容） |
| modelName | `deepseek-v4.1-flash` |
| Base URL | `https://lovetokenai.xyz/v1` |
| 运行方式 | 本地 Java Runtime（Electron 内置 JRE / IDEA 调试启动） |

---

## 2. 核心结论

### 2.1 直接原因

桌面端 Java 进程的 JVM 系统属性中存在：

```text
socksProxyHost=127.0.0.1
socksProxyPort=7891
http.proxyHost=127.0.0.1
http.proxyPort=7891
https.proxyHost=127.0.0.1
https.proxyPort=7891
```

而 `127.0.0.1:7891` 当前没有进程监听（Clash / 代理软件已关闭或换了端口）。

因此 `HttpURLConnection` 在建立 HTTPS 连接时先连 SOCKS 代理，直接失败：

```text
java.net.SocketException: Can't connect to SOCKS proxy:Connection refused (Connection refused)
```

请求根本没有到达 `lovetokenai.xyz`，所以与渠道、Key、模型名、User-Agent、请求头都没有关系。

### 2.2 为什么 curl 可以

`curl` 不读取 JVM 的 `-DsocksProxyHost` / `http.proxyHost` 系统属性；当前终端也没有设置 `HTTP_PROXY` / `HTTPS_PROXY` / `ALL_PROXY`，因此 curl 走直连：

```text
curl ──直连──> lovetokenai.xyz:443  → 200
Java ──SOCKS 127.0.0.1:7891──> 连接拒绝
```

### 2.3 为什么是“代理关闭后不重启服务”触发

Java 的 HTTP 代理配置来自 JVM 系统属性，属于**进程启动时确定的静态配置**：

1. 服务启动时，运行环境把代理属性注入 JVM（IDEA 运行配置、启动环境变量、系统代理读取等）；
2. 代理软件当时正在监听 `127.0.0.1:7891`，所以请求可以正常通过；
3. 关闭代理软件后，JVM 内部属性不会自动清空；
4. 服务进程没有重启，后续每次请求仍去连 `127.0.0.1:7891`；
5. 端口已关闭 → SOCKS `Connection refused` → 所有渠道一起失败。

这正好解释了：

- 一开始能用；
- 关闭代理后突然所有渠道都不可用；
- 重启服务（或重新打开代理）后又能用。

---

## 3. 证据链

### 3.1 日志中的真实异常

`~/.agentforge-desktop/logs/agentforge-desktop.log` 中，失败请求的完整堆栈为：

```text
cloud.changlu.agentforge.model.exception.ModelException: OpenAI streaming request failed
    at ...OpenAiStreamingChatModel$OpenAiStreamState.onError(...)
    ...
Caused by: java.net.SocketException: Can't connect to SOCKS proxy:Connection refused (Connection refused)
    at java.net.SocksSocketImpl.connect(SocksSocketImpl.java:428)
    at sun.net.www.protocol.https.HttpsClient.New(HttpsClient.java:367)
    at sun.net.www.protocol.https.AbstractDelegateHttpsURLConnection.getNewHttpClient(...)
    at sun.net.www.protocol.https.HttpsURLConnectionImpl.getOutputStream(...)
    at cloud.changlu.agentforge.model.http.JdkHttpTransport.writeBody(JdkHttpTransport.java:296)
    at cloud.changlu.agentforge.model.http.JdkHttpTransport.stream(JdkHttpTransport.java:119)
```

注意：异常发生在 `writeBody` / 建连阶段，HTTP 状态码都还没有产生，所以不可能是 401/403/402。

### 3.2 运行中的 JVM 确实带着代理属性

对正在运行的进程执行：

```bash
jcmd <pid> VM.system_properties | grep -i proxy
```

打包版与 IDEA 调试版进程都返回：

```text
socksProxyHost=127.0.0.1
socksProxyPort=7891
http.proxyHost=127.0.0.1
http.proxyPort=7891
https.proxyHost=127.0.0.1
https.proxyPort=7891
```

其中 IDEA 调试进程的代理来源已定位到：

```text
~/Library/Application Support/JetBrains/IntelliJIdea2024.1/options/proxy.settings.xml
```

```xml
<component name="HttpConfigurable">
  <option name="USE_HTTP_PROXY" value="true" />
  <option name="PROXY_HOST" value="127.0.0.1" />
  <option name="PROXY_PORT" value="7891" />
</component>
```

打包版进程同样存在这组属性，说明它也是**启动时被注入**的；AgentForge/AgentForge-Desktop 源码中没有任何 `System.setProperty("socksProxyHost", ...)` 逻辑。

### 3.3 端口确实已关闭

```bash
nc -z 127.0.0.1 7891   # closed
nc -z 127.0.0.1 7890   # closed
```

### 3.4 对照实验：同一份请求，只差代理

使用与框架相同的请求形态（`Content-Type: application/json`、`Accept: text/event-stream`、`Authorization: Bearer ...`、`User-Agent: AgentForge-Desktop/0.1.0`、`stream=true`、`stream_options.include_usage=true`）做 Java 直连：

| 实验 | 结果 |
| --- | --- |
| Java 直连（无代理属性） | **HTTP 200**，正常返回 SSE |
| Java + `-DsocksProxyHost=127.0.0.1 -DsocksProxyPort=7891` | **同样的 SOCKS Connection refused** |
| JDK 默认 User-Agent | HTTP 200 |
| `AgentForge-Desktop/0.1.0` | HTTP 200 |

结论：请求头、UA、payload 都不是变量，**唯一的变量是代理**。

### 3.5 与历史问题区分

| 现象 | 根因 | 与本问题关系 |
| --- | --- | --- |
| `HTTP 403` + `error code: 1010` | JDK 默认 UA `Java/1.8.x` 被 Cloudflare BIC 拦截 | 已修复，并且本网关对默认 UA 也返回 200 |
| `HTTP 402` | 网关侧余额/配额类响应 | 22:53 曾出现一次，属于渠道计费状态，不是请求头问题 |
| `Can't connect to SOCKS proxy` | 本地 JVM 代理已失效 | 本文问题 |

---

## 4. 影响面

这是**传输层全局问题**，不是某个渠道的问题：

- 只要 JVM 带着失效的 `socksProxyHost`，所有 OpenAI / Anthropic / 兼容渠道都会失败；
- 日志中可以看到，同一时段 model 6（love）和 model 8 都出现了 SOCKS 连接拒绝；
- 只要代理恢复监听，同一个进程立即又能工作；
- 只要重启服务且启动时不再注入代理属性，也能恢复。

因此“love 渠道走不通”只是表象，实际是**所有模型调用一起走不通**。

---

## 5. 为什么现在不容易排查

两个原因叠加：

1. **JVM 代理是隐式的**：代码里看不到任何代理配置，表现却像网络问题；
2. **错误信息被吞掉**：
   - `OpenAiStreamingChatModel` 只抛出 `OpenAI streaming request failed`，没有把 cause 拼进 message；
   - 桌面端 `ModelConfigService.message()`、`ServiceStreamingMiddleware.safeMessage()` 只取 `e.getMessage()`；
   - 真实的 `Can't connect to SOCKS proxy` 只在日志堆栈里，UI 看不到。

---

## 6. 推荐解决与预防方案

### 6.1 立即恢复（无需改代码）

任选其一：

1. 重新启动代理软件，确认 `127.0.0.1:7891` 正在监听；
2. 或者关闭代理注入后重启本地服务：
   - IDEA：`Settings → Appearance & Behavior → System Settings → HTTP Proxy` 关闭代理；
   - 检查启动环境是否存在 `JAVA_TOOL_OPTIONS` / `JDK_JAVA_OPTIONS` / `_JAVA_OPTIONS`；
   - 重新启动 AgentForge Desktop。

注意：只加 `-Djava.net.useSystemProxies=false` **不能**覆盖已经显式存在的 `socksProxyHost`。

### 6.2 代码层根治（推荐）

在 `agentforge-model-core` 的 `JdkHttpTransport` 增加**代理连接失败时自动直连重试一次**：

```text
第一次请求：按 JVM 当前代理配置发送
    │
    ├─ 成功 → 正常返回
    │
    └─ 失败且异常特征为 proxy / SOCKS 连接失败
           │
           ├─ 记录 WARN：代理不可用，改为直连重试
           └─ 使用 Proxy.NO_PROXY 再请求一次
                  ├─ 成功 → 用户无感
                  └─ 失败 → 抛出包含两次根因的错误
```

这样即使工作台一直开着、代理中途关闭，也能自动绕过失效代理，不需要用户重启服务。

注意事项：

- 只在**尚未收到响应**（`onOpen` 之前）时重试，避免重复消费流；
- 只在异常明确与代理相关时重试，普通目标连接拒绝不误判；
- 重试仍失败时，把“代理失败 + 直连失败”的根因都带出来。

### 6.3 错误信息透出（必须补）

建议同时修改三层：

1. **框架层** `OpenAiStreamingChatModel` / `OpenAiChatModel`：

```java
throw new ModelException("OpenAI streaming request failed: " + rootMessage(error), error);
```

2. **框架层** `ModelException` 已带 `responseBody`，上层不要只取 `getMessage()`；
3. **桌面端** `ModelConfigService.message()`、`ServiceStreamingMiddleware.safeMessage()`、
   `GlobalExceptionHandler.message()` 统一改为：

```text
错误消息 | cause: 根因 | response: 上游响应体（截断 500 字符）
```

修改后，UI 会直接显示：

```text
OpenAI streaming request failed: Can't connect to SOCKS proxy:Connection refused
```

### 6.4 启动时打印代理（建议）

在桌面服务启动时输出一条日志：

```text
JVM proxy: socks=127.0.0.1:7891, http=127.0.0.1:7891, https=127.0.0.1:7891
```

这样代理变更或残留一眼可见，避免再次出现“代码没动但突然全部失败”。

---

## 7. 验证清单

| 步骤 | 预期 |
| --- | --- |
| `jcmd <pid> VM.system_properties \| grep -i proxy` | 能看到当前 JVM 实际使用的代理 |
| 关闭代理后再发请求 | 修复前：SOCKS 连接拒绝；修复后：自动直连成功 |
| 重新打开代理 | 第一次请求走代理成功 |
| 代理端口换端口 | 修复后自动直连，不依赖重启 |
| UI 错误展示 | 能看到 `Can't connect to SOCKS proxy` 等根因，而不是只有 `OpenAI streaming request failed` |

---

## 8. 最终结论

**是的，就是“服务启动时代理生效、之后关闭代理但没有重启服务”导致的。**

更准确地说：

- 代理配置在 JVM 启动时被固化为系统属性；
- 关闭代理软件不会修改运行中 JVM 的系统属性；
- JVM 每次请求仍会连接 `127.0.0.1:7891`；
- 端口关闭后所有模型请求在建连阶段失败；
- curl 因为不读取这些 JVM 属性，所以直连正常。

这个问题与 love 渠道无关，也与请求头 / User-Agent 无关。建议优先落地两件事：

1. `JdkHttpTransport` 代理失败自动直连重试（根治“代理中途关闭”）；
2. 全链路错误透出根因（避免下次再次误判为渠道问题）。
