# AI Gateway 架构地图（接盘必读）

> 本文档是整个系统的完整认知地图。它回答四个问题：**这个软件是干什么的、它是怎么工作的、每个模块为什么存在、改代码时什么能碰什么不能碰。**
> 任何修改前请先读完本文档；本文档与代码不一致时，以代码为准并同步更新本文档。

---

## 1. 这个软件是干什么的

**一句话**：把你的 Android 手机变成一台私有的 AI 模型聚合服务器。

你的手机上装着多个 AI 服务的账号（Trae、WorkBuddy、Antigravity、Loomy），这些服务的客户端协议各不相同、大多数无法直接在第三方工具中使用。AI Gateway 把它们**全部翻译成标准 OpenAI 协议**，暴露在本机 `http://127.0.0.1:8790/v1`，于是任何支持 OpenAI 协议的客户端（Cherry Studio、Cline、Continue、curl 脚本……）都能透明地使用你手机里所有账号的额度。

**用户价值排序**（功能优先级以此为准）：
1. **稳定可用**——账号不被封、请求不 503，这压倒一切新功能；
2. **协议正确**——上游说什么格式就回什么格式；
3. 其余一切（UI 动效、测速按钮等）都是锦上添花。

---

## 2. 整体架构：一次请求的生命周期

```
客户端 (Cline / Cherry Studio / curl)
   │  POST /v1/chat/completions  {model: "gemini-3.8-flash", ...}
   ▼
┌─────────────────────────────────────────────────┐
│ GatewayHttpServer (NanoHTTPD, app进程内)          │
│  ① 鉴权（Bearer / x-goog-api-key / ?key=）        │
│  ② 解析请求体 → model 名                          │
└───────────────┬─────────────────────────────────┘
                ▼
┌─────────────────────────────────────────────────┐
│ GatewayEngine                                    │
│  ③ resolveCandidateRoutes(model)                 │
│     → [首选路由, 同款模型备用路由1, 备用路由2...]    │
│  ④ 遍历候选：AccountPool.pick() 选号              │
│     → provider.refreshAccount() 预刷新凭证         │
│     → provider.openChat() 发起上游请求             │
│     → 失败则换号/切下一个供应商 (Failover，凭证失效硬禁用) │
│  ⑤ 成功 → 透传 SSE / 回传 JSON，记调用日志          │
└───────────────┬─────────────────────────────────┘
                ▼
┌─────────────────────────────────────────────────┐
│ Provider 实现（每个封装一个上游私有协议）             │
│  trae:        SOLO 私有 SSE → OpenAI SSE          │
│  codebuddy:   腾讯 OpenAI 兼容（强制 stream）       │
│  antigravity: Gemini 风格 → OpenAI（双向翻译）      │
│  loomy:       近乎直通（HTTP 200 + 业务码陷阱）      │
│  raccoon:     小浣熊（OpenAI 结构包在 data 里）       │
│  custom:*:    纯直通                              │
└───────────────┬─────────────────────────────────┘
                ▼
        [ 上游 API ]
   Google（走代理）/ 腾讯、字节、讯飞、商汤（国内直连）
```

**核心不变量（所有代码都必须遵守）**：

> **Provider 的边界 = OpenAI 进、OpenAI 出。**
> `openChat()` 收标准 OpenAI 请求体，返回的 `ChatCall` 已是 OpenAI 语义。
> 上游私有协议的双向转换**全部封死在 Provider 内部**，网关层对供应商差异零感知。

这条边界是整个系统可维护性的根基。任何把上游协议细节泄漏到网关层的改动，都是拆东墙补西墙的开始。

---

## 3. 模块地图（每个文件是干什么的）

### core/（纯 JVM，可单测，无 Android 依赖）

| 文件 | 职责 | 关键约束 |
|---|---|---|
| `gateway/GatewayEngine.kt` | 总调度器：持有 AccountPool/Registry/Settings，`resolveRoute`/`resolveCandidateRoutes` 路由解析，错误分类→重试/换号/跨供应商 Failover 决策（凭证失效才硬禁用） | 路由逻辑改动必须同步 `SmartRoutingTest` |
| `gateway/GatewayHttpServer.kt` | NanoHTTPD 服务器：`/v1/chat/completions`（含 Failover 循环）、`/v1/models`、`/authorize`（Trae 回调） | 鉴权兼容 Bearer/x-goog-api-key/?key= |
| `gateway/GatewaySettings.kt` | 全局设置（端口、apiKey、allowNoKey、maxRotate=换号上限、refreshSkewSeconds、defaultProvider） | `maxRotate` 默认 3：单请求最多试 3 个账号 |
| `gateway/ProxySettings.kt` | 代理开关 + `DOMESTIC_SUFFIXES` 国内域名强制直连白名单 | 白名单优先级 > 一切代理设置 |
| `gateway/ProviderBootstrap.kt` | 内置 Provider 登记处 | 新增供应商在此加一行 |
| `gateway/LoopbackCallbackServer.kt` | OAuth 登录回调监听（Trae 51120 / Antigravity 51121） | 临时端口，用完即释放 |
| `gateway/OpenAiApi.kt` | OpenAI 报文拼装（error/modelList） | |
| `pool/AccountPool.kt` | 账号池：选号（余额最高者）、硬禁用（disabled）、持久化；不做本地冷却，上游错误由换号/Failover 应对 | 状态持久化在 `pool/state/<providerId>.json` |
| `protocol/OpenAiGemini.kt` | OpenAI⇄Gemini 双向转换（Antigravity 专用）：严格 user/model 角色交替、tool 聚合、thinking、safetySettings | **上游强制要求 contents 角色交替**，连续同 role 会 400 |
| `provider/Provider.kt` | Provider 接口 + AuthKind 枚举 | 抽象边界，勿随意扩接口 |
| `provider/ChatCall.kt` | 上游响应封装（stream/aggregated/failure 三态） | |
| `provider/StreamSupport.kt` | `LineTransformStream`（逐行翻译）、`OpenAiSseAggregator`（SSE聚合/completionAsSse/isEmptyCompletion） | SSE 帧必须以 `\n\n` 结尾 |
| `provider/antigravity/` | Google Antigravity（Gemini 协议）| **凭证策略见下文第 5 节，严禁回退** |
| `provider/codebuddy/` | 腾讯 WorkBuddy（国内 copilot.tencent.com / 国际 workbuddy.ai）| 上游拒绝非流式，必须 stream:true |
| `provider/trae/` | 字节 Trae SOLO 通道（最重的协议转换）| |
| `provider/loomy/` | 讯飞 Loomy（HTTP 200 + 业务码陷阱）| |
| `provider/raccoon/` | 商汤小浣熊（`xiaohuanxiong.com`，web 通道，网页回调登录）| OpenAI 结构包在 `data` 里、`delta` 是字符串；`max_tokens`→`max_new_tokens`；有积分余额接口 |
| `provider/custom/` | 任意 OpenAI 兼容中转站 | 一个 key = 一个账号 |
| `store/KeyValueStore.kt` | 存储抽象（App 侧用 EncryptedSharedPreferences 实现） | |

### app/（Android 壳）

| 文件 | 职责 |
|---|---|
| `AiGatewayApp.kt` | Application：构造引擎（单例）+ 通知渠道 |
| `service/GatewayService.kt` | 前台服务：`startForeground` 必须在 `onStartCommand` 第一行（系统超时 5s 强杀），START_STICKY 自重建，WakeLock+WifiLock |
| `service/GatewayTileService.kt` | 控制中心磁贴开关 |
| `data/EncryptedKeyValueStore.kt` | 写操作用 `commit()`（同步）而非 `apply()`——换 token 后必须立刻可读 |
| `ui/ModelsScreen.kt` | 模型列表：点击复制、Q弹测速按钮 |
| `ui/Motion.kt` | 全局动效参数与Modifier |

#### 模型目录流式加载（UI 交接要点）

模型页不再等最慢的供应商拉完才显示：`GatewayEngine.modelsStreaming(onChunk)` 每个供应商
一就绪就回调一条 `ProviderModelsChunk(providerId, models, error?)`（后台线程），
`AppViewModel.refreshModels` 把它合并进 `AppUiState`（主线程串行，代次防并发错乱）。

供 UI 动画使用的状态字段（`AppUiState`）：

| 字段 | 含义 |
|---|---|
| `modelsLoading` | 是否有一轮流式加载在进行 |
| `modelLoadStates: Map<String, ModelLoadState>` | 每个供应商的进度：`Loading` / `Done(count)` / `Failed(reason)`；发起时先置全部参与供应商为 Loading |
| `models` | 各供应商就绪即增量合并（同供应商旧条目被替换）；一轮结束时整体覆盖为最终聚合 |
| `modelsError` | 整体异常 + 各失败供应商汇总（`供应商：原因`）；带内置快照的回退不算失败 |

可做的动画：按 `modelLoadStates` 画每供应商骨架屏、`Done` 后逐组点亮、`Failed` 组内错误态；
`ModelsScreen` 现在的空态「还没有模型」在 `modelsLoading` 时应替换为骨架屏。
注意：刷新发起时若已有旧目录，本次为静默刷新（不弹全屏 busy），旧数据先可见、新数据逐组替换。

### 存储键布局（EncryptedSharedPreferences）

```
account/<providerId>/<uid>.json    # 账号凭证（ProviderAccount.toJson）
pool/state/<providerId>.json       # 账号状态（禁用/额度）
settings/gateway.json              # 网关设置
settings/provider/<id>.json        # 供应商设置（含 region 等 option）
settings/proxy.json                # 代理设置
settings/custom.json               # 自定义供应商配置
call/...                           # 调用日志
```

---

## 4. 供应商速查表

| Provider | id | 登录方式 | 上游 | 协议陷阱 |
|---|---|---|---|---|
| Trae | `trae` | WebView 回调 51120 | `trae-api-cn.mchost.guru` | SOLO 私有 SSE；content 必须是 `[{type:text}]` 数组；tool 参数是字符串；流内 `notify_usage` 上报计费 |
| WorkBuddy | `codebuddy` | 设备授权（state 轮询） | 国内 `copilot.tencent.com` / 国际 `workbuddy.ai` | 上游拒绝非流式（必须 stream:true）；tool_choice 只认字符串；签到/成长中心仅国内版 |
| Antigravity | `antigravity` | OAuth loopback 51121 | `cloudcode-pa.googleapis.com` | Gemini 风格：role 用 user/model；schema 不支持 const/$ref；contents 必须严格交替；tool 参数名是 parametersJsonSchema；response.result 必须是字符串 |
| Loomy | `loomy` | 手机短信 | `xfinfr.com` | 鉴权失败是 **HTTP 200 + 业务码**，不是 4xx |
| 小浣熊 | `raccoon` | 网页回调（`/code/authorize` → `authorization_code`） | `xiaohuanxiong.com`（web 通道） | 对话走 `/api/web/llm/v1/chat/completions`；真正的 OpenAI 结构被包在 `data` 里（`{"status":..,"data":..}`），且 `delta` 是**字符串**不是对象；请求体 `max_tokens` 要改名 `max_new_tokens`；需伪装桌面 Web 客户端 UA |
| 自定义 | `custom:<key>` | API Key | 用户填的 baseUrl | 无 |

---

## 5. ⚠️ Antigravity 凭证策略（血泪教训，严禁回退）

这是本项目踩坑最深的地方，**0.1.40→0.1.57 的「登录频繁失效」全是违反以下原则造成的**：

### 正确策略（0.1.58 现状）
1. **Provider 内部绝不主动刷新 Token**。`openChat`/`listModels`/`creditInfo` 只调 `parse(account)` 取当前凭证。
2. **刷新唯一入口**：网关在每次请求前调 `provider.refreshAccount(account, settings.refreshSkewSeconds)`，而 `refreshAccount` 内部判断：只在 token 临期 5 分钟内（`REQUEST_SAFETY_WINDOW_SECONDS`）才真正调 OAuth。刷新频率天然 ≈ 1小时1次。
3. **刷新成功必须落盘**：`refreshAccount` 内通过 `hooks.onAccountUpdated(toProviderAccount(refreshed, account))` 回存——Google 会轮换 refresh_token，不回存旧值作废后就是永久 invalid_grant。
4. **UID 稳定性**：`toProviderAccount(refreshed, existing)` 必须沿用既有账号的 uid，否则账号池键漂移 = 账号"消失"。
5. **classify 语义**：401/403 → `SESSION_DEAD`（硬禁用，提示重新登录）。**不要**降级成软重试——那会让账号很快回来再次触发刷新，形成刷新风暴加速风控。

### 为什么（根因链条）
Google 对同一个 refresh_token 的高频 token 换新有风控。0.1.53~0.1.57 曾在每次对话前主动刷新 + 401 后"自愈重试"再刷 + 401 降级短冷却回来再刷——三次叠加让一个 token 几分钟内被换新十几次，Google 直接吊销。表现就是「反复需要重新登录」和后续的 503。

### 铁律
> 任何「让 Antigravity 更不容易掉线」的改动，如果最终效果是**增加了对 `oauth2.googleapis.com/token` 的调用频率**，那它一定是错的，无论它看起来多有道理。

---

## 6. 协议陷阱清单（上游实测）

### Google Antigravity（Gemini 风格）
- `contents` 角色必须严格 user→model→user 交替；连续同 role 直接 400。OpenAI 的 tool 消息需按回合聚合成单个 user content。
- assistant 的 reasoning_content → `thought: true` part；functionCall/functionResponse 必须带 `id` 且互相配对。
- `functionResponse.response.result` 必须是**字符串**，解析成 JSON 对象会 400。
- 工具 schema 字段名是 `parametersJsonSchema` 不是 `parameters`；需剔除 const/$ref/$schema/default。
- 默认挂 safetySettings（四类 OFF + CIVIC_INTEGRITY BLOCK_NONE），否则正常内容被安全过滤截断。
- 非claude模型删除 `generationConfig.maxOutputTokens`（上游不接受）。
- envelope：`{project, model, userAgent:"antigravity", requestType:"agent", requestId, request:{...sessionId}}`；sessionId 从首条 user 文本 SHA256 稳定派生。
- 请求头白名单：Content-Type / Authorization / UA(`antigravity/hub/2.9.1 darwin/arm64`)。多余的 X-Goog-Api-Client 等头会被识别。

### 腾讯 WorkBuddy
- 上游**拒绝非流式**：必须强制 `stream:true`，网关侧聚合。
- `tool_choice` 只认字符串。
- 签到 `/v2/daily-checkin` 幂等；成长中心在 `/v2/activity/growth/*` 下；**仅国内版有**，国际版（workbuddy.ai）没有签到制度。

### 字节 Trae（SOLO）
- `content` 必须是 `[{type:"text",text}]` 数组；`tools[].function.parameters` 必须是 JSON 字符串；`tool_choice` 只认字符串；assistant 的 `tool_calls[].function` 要改名 `function_call`。
- 流内 `notify_usage.ide_credits` 是真实积分消耗，需回写账号池。

### 讯飞 Loomy
- **鉴权失败是 HTTP 200 + 业务码**（JSON 里 code 字段），不是 4xx。不做业务码判定会把错误 JSON 当正常回复透传。

### 商汤小浣熊（raccoon）
- 登录是网页回调：`/code/authorize?login_source=desktop&appname=Raccoon&redirect=<回调>`，回调带 `authorization_code`，再 `POST /api/web/auth/v1/login_with_authorization_code` 换 `access_token`(JWT)/`refresh_token`。
- 对话端点 `POST /api/web/llm/v1/chat/completions`：**真正的 OpenAI 结构被包在 `data` 里**（外层 `{"status":{"code":..},"data":{...}}`），且 **`delta` 是字符串**（正文片段）不是对象，需逐行翻译回标准 OpenAI SSE；请求体 `max_tokens` 要改名 `max_new_tokens`、`n=1`、`stop="<|endofmessage|>"`。
- 鉴权 `Authorization: Bearer <access_token>`；需带桌面 Web 客户端 UA + `Accept-Language: zh-Hans`；有组织时带 `X-Org-Code`。
- **有积分余额接口**：`GET /api/web/points/v1/balance`（字段 `available_points`）——这是 plugin 通道（`/api/plugin/...`）没有的。plugin 通道是 IDE 插件在用，web 通道是官网/桌面客户端在用，两套并存。
- 旧主机 `raccoon.sensetime.com` / `code.sensetime.com` 已解析不了，现主机是 `xiaohuanxiong.com`。

### 直通层通用
- SSE 事件必须以空行（`\n\n`）结尾；上游格式不严谨时网关统一规范化。
- HTTP 200 + 空 body ≠ 成功。空回复必须报 `upstream_empty` 错误，否则客户端表现为「输出完成但零内容」。
- 上游回普通 JSON 但客户端要流式：包成单 chunk + [DONE] 的 SSE（`completionAsSse`）。

---

## 7. 改动守则（给未来的自己和接盘者）

1. **修 bug 前先回答**：这个 bug 在 0.1.39（最后一个全绿版本）存在吗？
   - 不存在 → 是 0.1.39 之后某次改动引入的。**先 git diff 找到引入点，恢复原行为，再考虑要不要保留新特性**。不要在引入 bug 的代码上继续叠补丁。
2. **任何触碰 `refreshAccount`/`ensureValidCredential`/`classify` 的改动**：先重读本文档第 5 节。Antigravity 的凭证策略已被验证过一次错误方向，不要用「看起来更健壮」的理由再踩一遍。
3. **新增供应商**：只需 ①core 加 `provider/<id>/` 实现 `Provider` 接口，②`ProviderBootstrap` 加一行，③app 加 `ProviderUi` 实现并在 `ProviderUiRegistry` 登记。框架自动获得账号池、Failover、代理分流、日志、保活。
4. **测试要求**：`:core:test` 全绿才能出包。协议相关改动必须先加失败用例再修（复现→修→锁住）。
5. **构建三坑**（aarch64 PRoot 容器）：JDK 必须 17；必须 `LD_PRELOAD=/opt/android-sdk/lib/libdelfix.so`；aapt2/zipalign 必须用 aarch64 静态版（备份在 `~/.aicode/backup/`）。详见全局记忆 `android-build-arm64-proot`。
6. **版本纪律**：每次交付 versionCode+1 / versionName patch+1；出包后 `aapt2 dump badging` 核对；产物命名 `ai-gateway-<版本>-release.apk`。
