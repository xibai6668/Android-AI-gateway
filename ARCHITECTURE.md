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
| `failover/` | 容灾调度：`FailoverConfig`（候选/重试/熔断/并发参数；`FailoverCandidate.weight`/`timeoutMs` **未生效**，仅配置兼容）、`FailoverDecision`（错误→重试/换家/终止）、`ProviderCircuitBreaker`（连续失败熔断）、`ConcurrencyGate`（全局/单账号在途上限，`concurrency` 配置热生效；**默认单账号串行 `maxPerAccount=1`、排队超时 120s**——上游按账号限并发流，同账号并发会被挂住只回一个空首 chunk，官方客户端本身也是一次一个请求）、`ProviderMetricsTracker`（健康指标，仅观测）；新增 `ModelCooldownTracker`（按 `供应商|模型` 记模型级冷却，仅内存） | 熔断只计上游侧错误，客户端 400 不计；**模型级错误（CodeBuddy `6004`、Trae `1005`）不计熔断、不换号**，只冷却该模型；并发许可覆盖含流式转发的全过程，`Handle.release` 幂等 |
| `pool/AccountPool.kt` | 账号池：选号（余额最高者）、硬禁用（disabled）、持久化；不做本地冷却，上游错误由换号/Failover 应对 | 状态持久化在 `pool/state/<providerId>/<uid>.json`（一账号一文件；旧版 `pool/state/<providerId>.json` 首次读取时惰性迁移） |
| `protocol/OpenAiGemini.kt` | OpenAI⇄Gemini 双向转换（Antigravity 专用）：严格 user/model 角色交替、tool 聚合、thinking、safetySettings | **上游强制要求 contents 角色交替**，连续同 role 会 400 |
| `provider/Provider.kt` | Provider 接口 + AuthKind 枚举 | 抽象边界，勿随意扩接口 |
| `provider/ChatCall.kt` | 上游响应封装（stream/aggregated/failure 三态） | |
| `provider/StreamSupport.kt` | `LineTransformStream`（逐行翻译）、`OpenAiSseAggregator`（SSE聚合/completionAsSse/isEmptyCompletion） | SSE 帧必须以 `\n\n` 结尾 |
| `provider/antigravity/` | Google Antigravity（Gemini 协议）| **凭证策略见下文第 5 节，严禁回退**；额度查询完全对接 CLIProxyAPI |
| `provider/codebuddy/` | 腾讯 WorkBuddy（国内 copilot.tencent.com / 国际 workbuddy.ai）| 上游拒绝非流式，必须 stream:true |
| `provider/trae/` | 字节 Trae SOLO 通道（最重的协议转换）| |
| `provider/loomy/` | 讯飞 Loomy（HTTP 200 + 业务码陷阱）| |
| `provider/raccoon/` | 商汤小浣熊（`xiaohuanxiong.com`，web 通道，网页回调登录）| OpenAI 结构包在 `data` 里、`delta` 是字符串；字段用标准名（v2 是 LiteLLM 层，`max_new_tokens` 会被 500 拒）；有积分余额接口 |
| `provider/custom/` | 任意 OpenAI 兼容中转站 | 一个 key = 一个账号 |
| `store/KeyValueStore.kt` | 存储抽象（App 侧用 EncryptedSharedPreferences 实现） | |
| `security/` | `SecuritySettings`（脱敏/限速开关）、`RequestSanitizer`（system 提示词零宽字符脱敏 + 出网取证）、`AccountRateLimiter`（账号级最小间隔 + 抖动） | 只改 system 消息，不碰 user 输入 |
| `usage/` | `CallLogStore`（调用记录，每条一键；超出 8K 摘要的完整原文溢出到缓存目录供详情页回读）、`RequestLog`（环形运行日志，容量可运行期调整）、`StorageAudit`（按前缀统计占用） | 记录有容量淘汰与保留天数清理；溢出文件随删除/清空/过期清理/截断一并回收 |
| `util/` | `CappedStringBuilder`（边收边截）、`CountFormat`、`JsonAccess`、`TimeWindow` | |
| `update/` | `UpdateRepository`（Release JSON 解析，多线路竞争取先到）、`MirrorSpeedTest`（Range 探测 512KB 测带宽）、`ApkDownloader`（HEAD 取长度后分段并发下载，单段续传+重试）、`UpdateSources`（直连 + 4 条国内镜像的映射表） | 只登记实测支持 `206` 的镜像；镜像请求绕开用户代理（国内入口），直连尊重代理设置；任一线路失败只是少一个候选 |

### app/（Android 壳）

| 文件 | 职责 |
|---|---|
| `AiGatewayApp.kt` | Application：构造引擎（单例）+ 通知渠道 |
| `service/GatewayService.kt` | 前台服务：`startForeground` 必须在 `onStartCommand` 第一行（系统超时 5s 强杀），START_NOT_STICKY 不自重建（自重建是灰色软件检测特征），WakeLock+WifiLock |
| `service/GatewayTileService.kt` | 控制中心磁贴开关 |
| `service/FloatingKeepAlive.kt` | 可选的「悬浮窗保活」：`TYPE_APPLICATION_OVERLAY` 小悬浮标签（可拖动、点按回应用），由前台服务按开关启停 |
| `data/EncryptedKeyValueStore.kt` | 写操作用 `commit()`（同步）而非 `apply()`——换 token 后必须立刻可读 |
| `data/ApkInstaller.kt` | 应用内更新安装：校验安装包版本、「安装未知应用」权限引导、FileProvider 交付系统安装器 |
| `ui/ModelsScreen.kt` | 模型列表：点击复制、Q弹测速按钮 |
| `ui/ResilienceScreen.kt` | 并发与容错设置页：并发闸门参数、重试退避、熔断器（每个对话框一个参数组） |
| `ui/LogsScreen.kt` | 记录页：调用记录（详情可展开全文、大文本折叠）+ 请求日志（级别过滤/清空/容量设置在标签旁） |
| `ui/Motion.kt` | 全局动效参数与Modifier |

#### 模型目录流式加载（UI 交接要点）

模型页不再等最慢的供应商拉完才显示：`GatewayEngine.modelsStreaming(forceRefresh, onChunk)` 每个供应商
一就绪就回调一条 `ProviderModelsChunk(providerId, models, error?)`（后台线程），
`forceRefresh = true`（模型页刷新按钮）先调各 provider 的 `invalidateModelCache()` 丢弃模型目录
TTL 缓存，保证真正重打上游；目录缓存仍保留最后一次成功结果作失败兜底，不会因一次拉取失败清空列表。
`AppViewModel.refreshModels` 把它合并进 `AppUiState`（主线程串行，代次防并发错乱）。

供 UI 动画使用的状态字段（`AppUiState`）：

| 字段 | 含义 |
|---|---|
| `modelsLoading` | 是否有一轮流式加载在进行 |
| `modelLoadStates: Map<String, ModelLoadState>` | 每个供应商的进度：`Loading` / `Done(count)` / `Failed(reason)`；发起时先置全部参与供应商为 Loading |
| `models` | 各供应商就绪即增量合并（同供应商旧条目被替换）；一轮结束时整体覆盖为最终聚合 |
| `modelsError` | 整体异常 + 各失败供应商汇总（`供应商：原因`）；无账号时不报错，拉取失败即算失败 |

可做的动画：按 `modelLoadStates` 画每供应商骨架屏、`Done` 后逐组点亮、`Failed` 组内错误态；
`ModelsScreen` 现在的空态「还没有模型」在 `modelsLoading` 时应替换为骨架屏。
注意：刷新发起时若已有旧目录，本次为静默刷新（不弹全屏 busy），旧数据先可见、新数据逐组替换。

### 存储键布局（EncryptedSharedPreferences）

```
account/<providerId>/<uid>           # 账号凭证（ProviderAccount.toJson）
pool/state/<providerId>/<uid>.json   # 账号状态（禁用/额度）；旧版为 pool/state/<providerId>.json（自动迁移）
settings/gateway.json              # 网关设置
settings/provider/<id>.json        # 供应商设置（含 region 等 option）
settings/proxy.json                # 代理设置
settings/custom.json               # 自定义供应商配置
call/...                           # 调用日志
```

> 调用记录的完整原文（超出 8K 摘要的部分）不在加密存储里，而是溢出到 `cacheDir/call-fulltext/`
> （单字段 ≤512K 字符、目录总量 ≤64MB）；缓存被系统回收或记录被清理后，详情页回落展示摘要。
> CLI 不传溢出目录，`logs` 命令只展示摘要。

---

## 4. 供应商速查表

| Provider | id | 登录方式 | 上游 | 协议陷阱 |
|---|---|---|---|---|
| Trae | `trae` | WebView 回调 51120 | `trae-api-cn.mchost.guru` | SOLO 私有 SSE；content 必须是 `[{type:text}]` 数组；tool 参数是字符串；流内 `notify_usage` 上报计费 |
| WorkBuddy | `codebuddy` | 设备授权（state 轮询） | 国内 `copilot.tencent.com` / 国际 `workbuddy.ai` | 上游拒绝非流式（必须 stream:true）；tool_choice 只认字符串；签到/成长中心仅国内版 |
| Antigravity | `antigravity` | OAuth loopback 51121 | `cloudcode-pa.googleapis.com` | Gemini 风格：role 用 user/model；schema 不支持 const/$ref；contents 必须严格交替；tool 参数名是 parametersJsonSchema；response.result 必须是字符串 |
| Loomy | `loomy` | 手机短信 | `xfinfr.com` | 鉴权失败是 **HTTP 200 + 业务码**，不是 4xx |
| 小浣熊 | `raccoon` | 网页回调（`/code/authorize` → `authorization_code`） | `xiaohuanxiong.com`（web 通道） | 登录 URL **不能带 `login_source=desktop`**（否则走 `office-raccoon://` 分支跳不回来）；对话走 v2（LiteLLM 层）：字段用标准名（`max_new_tokens` 会被 500 拒）、`delta` 是**字符串**不是对象；有积分余额/流水接口；「签到」实为桌面端登录奖励，须带 `X-Client-Platform`/`X-Client-Version` 才会发放 |
| 自定义 | `custom:<key>` | API Key | 用户填的 baseUrl | 无 |

---

## 5. ⚠️ Antigravity 凭证策略（血泪教训，严禁回退）

这是本项目踩坑最深的地方，**0.1.40→0.1.57 的「登录频繁失效」全是违反以下原则造成的**：

### 正确策略（2.2.1 现状）
1. **Provider 内部绝不主动刷新 Token**。`openChat`/`listModels`/`creditInfo` 只调 `parse(account)` 取当前凭证。
2. **刷新唯一入口**：网关在每次请求前调 `provider.refreshAccount(account, settings.refreshSkewSeconds)`，而 `refreshAccount` 内部判断：只在 token 临期 5 分钟内（`REQUEST_SAFETY_WINDOW_SECONDS`）才真正调 OAuth。刷新频率天然 ≈ 1小时1次。生效窗口 = `min(skewSeconds, 5min)`，故即使全局 `refreshSkewSeconds` 被配大也不会退化成每次请求都刷。
3. **刷新成功必须落盘**：`refreshAccount` 内通过 `hooks.onAccountUpdated(toProviderAccount(refreshed, account))` 回存——Google 会轮换 refresh_token，不回存旧值作废后就是永久 invalid_grant。
4. **UID 稳定性**：`toProviderAccount(refreshed, existing)` 必须沿用既有账号的 uid，否则账号池键漂移 = 账号"消失"。
5. **classify 语义**：401/403 → `SESSION_DEAD`（硬禁用，提示重新登录）。**不要**降级成软重试——那会让账号很快回来再次触发刷新，形成刷新风暴加速风控。
6. **并发去重**：临期判断在锁外，故锁内必须复查「刚被别的线程刷出的凭证」（`lastRefreshed`，按 uid 缓存）并直接复用，否则同一窗口内 N 个在途请求会打出 N 次 OAuth。命中缓存必须返回新凭证而非 null，否则调用方拿旧 token 直接 401。重新登录（`exchangeCode`）要清该 uid 的缓存与冷却，防止旧凭证被回写。
7. **失败冷却**：刷新失败后 60s（`REFRESH_COOLDOWN_SECONDS`，按 uid）内不再重打 OAuth，避免 token 被吊销后按请求频率轰炸端点。

### 为什么（根因链条）
Google 对同一个 refresh_token 的高频 token 换新有风控。0.1.53~0.1.57 曾在每次对话前主动刷新 + 401 后"自愈重试"再刷 + 401 降级短冷却回来再刷——三次叠加让一个 token 几分钟内被换新十几次，Google 直接吊销。表现就是「反复需要重新登录」和后续的 503。
2.2.1 又发现两处残留放大器：`refreshAccount` 的临期判断在锁外、锁内不复查最新凭证（并发 N 请求 = N 次 OAuth），以及刷新失败无退避（token 被吊销后 OAuth 频率 = 请求频率）。两者均已按上面第 6/7 条堵住。

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
- **配额口径（`retrieveUserQuotaSummary`，2026-10 起）**：共享池只有两组 —— `Gemini Models`（`gemini-5h`/`gemini-weekly`，Gemini 4 Argon、3.x Pro/Flash、Image 共用）与 `Claude and GPT models`（`3p-5h`/`3p-weekly`）。
  - **Gemini 4 Argon 有独立的周日额度**（bucketId 形如 `gemini-argon-wtu-weekly` / `gemini-4-argon`，仅周窗口）。它是**模型级**配额：耗尽只影响 argon 模型，绝不能拿来当账号级余额，否则选号会把还有额度的账号判死（同「错误作用域」铁律）。
  - 周配额耗尽时，Google 会把对应 5h 桶标 `disabled: true` 且 `remainingFraction: 1`。**这个 `disabled` 不表示不可用**（2026-10-28 实测：官方客户端里周额度耗尽后 5 小时额度仍可正常使用），所以不能丢掉整桶；只跳过「连 `remainingFraction` 都没有」的占位条目。账号余额取最紧窗口，但同账号还有别的窗口有余量时 detail 追加「仍有可用窗口」。
  - ⚠️ 未解：用户反馈「周额度有余量时也看不到 5h」，且日志里只有两条周窗口。可能上游对当前客户端形态压根不下发 5h 桶（或 displayName 与周桶相同），与上面「两组各含 5h/weekly」的旧结论不符——需真实 `groups` 报文定案，勿再凭假设改解析。
  - 账号级余额 = 共享池最紧窗口；模型专属池单独以 `QuotaPack.scope`（「仅 gemini-4-argon」）展示，并写进 `creditInfo.detail` 的「独立配额」段。
  - **额度/订阅查询完全对接 CLIProxyAPI**（`antigravity_executor_credits.go` / `antigravity_executor_request.go`，MIT）：
    - `loadCodeAssist` 与 `retrieveUserQuotaSummary` 一律用裸 hub UA `antigravity/hub/<ver> darwin/arm64`，base 固定 `daily-cloudcode-pa`；
    - `loadCodeAssist` 的 metadata **只带 `ideType=ANTIGRAVITY`**（不带 platform/pluginType）；
    - 只发 `Authorization` / `Content-Type` / `User-Agent` 三个头，不发 `X-Goog-Api-Client` / `Client-Metadata`；
    - 失败回退：剥 `project` 重试 → sandbox → prod；只有 401 才报登录失效，403 报真实状态码。
    > 历史弯路（不要再重复）：曾试过 `antigravity/cli/<ver>`、带 `(aidev_client; cl=..)` 段的完整 hub 形态、`ideType` 在 `ANTIGRAVITY`/`IDE_UNSPECIFIED` 间探测、给 metadata 补齐 `platform`/`pluginType`——这些都没能解决 #3501，且客户端形态已被官方论坛证伪（同一账号官方 IDE 正常、直连 API 仍 403）。
  - `resolveModel` 先查「模型目录里真有的 id」再做子串路由：`gemini-4-argon` 含 `pro`，否则会被改写成 `gemini-pro-agent`。
  - **额度查询路径的 403 不等于登录失效**：403 常见于权限/订阅分流（free-tier 访问部分 RPC 返回 `SUBSCRIPTION_REQUIRED`），此时应降级继续查配额并把真实状态码写进 detail；只有 401 才是重新登录。对话路径的 `classify` 仍保持 401/403 → `SESSION_DEAD`（见第 5 节铁律，勿改）。

### 腾讯 WorkBuddy
- 上游**拒绝非流式**：必须强制 `stream:true`，网关侧聚合。
- `tool_choice` 只认字符串。
- ⚠️ **请求体形状是硬要求**（不满足直接 400，不是降级）：
  - **首条消息必须是 `system`**，否则 `400 code=11128 "first message is not system prompt"`。我们自己发起的探测（模型页「测试」）最初就是纯 user 消息，直接撞上这条。
  - 角色 `developer` 上游不认，要改叫 `system`；参数 `max_completion_tokens` 要改叫 `max_tokens`。
  - ⚠️ **内容也会被拦**：带 Claude Code / Codex CLI 特征文本的请求会被以「Illegal API invocation from an unapproved channel」拒掉。已知替换（公开实现 magpie 用真实客户端逐词实测）：删 `x-anthropic-billing-header:…` 行；`You are Claude Code, Anthropic's official CLI for Claude` → 去掉 `official`；`Main branch (you will usually use this for PRs)` → `Main branch (usually the base for PRs)`；`Codex CLI is an open source project led by OpenAI.` → 前面补 `The `。都实现在 `prepareCodeBuddyBody`，**任何路径（含探测/自测）都要先过它**。
- 国际/国内是两套端点（见下）；模型目录国内走网页控制台 `/console/enterprises/personal/models`，国际版走客户端 product config `/v3/config`。
- ⚠️ **国内与国际是两套端点，不能混用**：国内主域 `copilot.tencent.com` / `codebuddy.cn`，国际 `workbuddy.ai`（历史域名 `codebuddy.ai`）。
  - 拿国际版 token 打国内站会被直接拒（500/401）；区域靠 JWT `iss` 判定最可靠（`workbuddy.ai`=国际），凭证里的 `domain` 次之。
  - **模型目录两条路径不通用**：国内走网页控制台 `/console/enterprises/personal/models`（无凭证 302 跳 OIDC 登录页）；国际版走客户端 product config `/v3/config`（该路径国内也能用）。国际版拿 Bearer token 打 console 路径会被后端 500，打 `/v2/enterprises/personal/models` 会挂死不返回。两份响应的结构相同（`data.agents[name=cli].models` + `data.models[]`），解析可共用。
  - 设备授权的 state 与 token **不通用**：跨站轮询 `/v2/plugin/auth/token` 对未知 state 恒返回 `{"code":11217}`，不报错、只会静默等到超时——所以「打开登录页」与「轮询」必须用同一个区域。
- 签到 `/v2/daily-checkin` 幂等；成长中心在 `/v2/activity/growth/*` 下；**仅国内版有**，国际版（workbuddy.ai）没有签到制度。

### 字节 Trae（SOLO）
- ⚠️ **agent host 分区域且与 OAuth host 不同**：国内 agent `trae-api-cn.mchost.guru`、额度 `api.trae.cn`、鉴权 `api.trae.com.cn`；国际 agent `trae-api-sg.mchost.guru`、额度 `ug-normal.trae.ai`、鉴权 `api.trae.ai`。国际版把 agent host 写成 `api.trae.ai` 会让对话与模型目录全部 **404**（该域上 `/api/agent/v3/llm_utils_chat`、`/api/ide/v1/get_detail_param` 都不存在）。额度接口版本段也不同：国内 v2、国际 v1。
- `content` 必须是 `[{type:"text",text}]` 数组；`tools[].function.parameters` 必须是 JSON 字符串；`tool_choice` 只认字符串；assistant 的 `tool_calls[].function` 要改名 `function_call`。
- 流内 `notify_usage.ide_credits` 是真实积分消耗，需回写账号池。

### 讯飞 Loomy
- **鉴权失败是 HTTP 200 + 业务码**（JSON 里 code 字段），不是 4xx。不做业务码判定会把错误 JSON 当正常回复透传。

### 商汤小浣熊（raccoon）
- 登录是网页回调：`/code/authorize?appname=Raccoon&redirect=<回调>`，回调带 `authorization_code`，再 `POST /api/web/auth/v1/login_with_authorization_code` 换 `access_token`(JWT)/`refresh_token`。
- ⚠️ **不要带 `login_source=desktop`**：授权页在 `login_source=desktop` 时走 `office-raccoon://auth/callback?code=…` 自定义 scheme 分支（手机上没有 App 能接，表现为「点授权跳首页、登录不回来」）；不传时才会把 `?authorization_code=…` 回推到 `redirect`（`window.open(redirect+code)`）。`redirect` 不传时兜底跳官网首页，这正是 bug 现象。
- 积分：余额 `GET /api/web/points/v1/balance`（`available_points`，另有 `daily_points` / `monthly_points` / `reward_points` / `topup_points`）；流水 `GET /api/web/points/v1/bills?paging.limit=&paging.offset=&type=all`（`data.items[]` 带 `bill_id` / `event_name` / `biz_type` / `points` / `created_at`，**支出是负数**）。
- ⚠️ **桌面端登录奖励不是「每日签到」**：`POST /api/web/desktop/v1/login/points/grant`（官方客户端函数名 `grantDesktopLoginPoints`）**必须带 `X-Client-Platform`（`desktop-windows` / `desktop-macos` / `desktop-linux`）与 `X-Client-Version`（如 `v1.0.38`）**，缺了上游不发放；响应 `data.granted` 表示本次是否发放，**积分在 `data.popup.points`，不在 `data.points`**。只看 HTTP 2xx 就报「签到成功」会骗人。每日赠送积分（免费 300/日、Pro 500/日）由服务端每天 00:00 自动发放、当天登录后有效期延长至 7 天，**不存在「点一下加积分」的签到**。
- ⚠️ **小浣熊没有积分任务体系**：桌面端的「任务中心」是 Agent 任务（新建/定时任务），积分侧只有 balance / bills / grant 三个端点。别再去找「任务领积分」接口（探测过 30+ 候选路径全 404）。
- 对话端点 `POST /api/web/llm/v2/chat/completions`（v2 是 LiteLLM 的 OpenAI 兼容层，字段名用标准 `max_tokens`；**改成 `max_new_tokens` 会被上游 500 拒**）。响应**真正的 OpenAI 结构被包在 `data` 里**（外层 `{"status":{"code":..},"data":{...}}`），且 **`delta` 是字符串**（正文片段）不是对象，需逐行翻译回标准 OpenAI SSE；请求体补 `n=1`、`stop="<|endofmessage|>"`。
- 鉴权 `Authorization: Bearer <access_token>`；需带桌面 Web 客户端 UA + `Accept-Language: zh-Hans`；有组织时带 `X-Org-Code`。
- 旧主机 `raccoon.sensetime.com` / `code.sensetime.com` 已解析不了，现主机是 `xiaohuanxiong.com`。

### 直通层通用
- SSE 事件必须以空行（`\n\n`）结尾；上游格式不严谨时网关统一规范化。
- HTTP 200 + 空 body ≠ 成功。空回复必须报 `upstream_empty` 错误，否则客户端表现为「输出完成但零内容」。
- 上游回普通 JSON 但客户端要流式：包成单 chunk + [DONE] 的 SSE（`completionAsSse`）。

---

## 7. 改动守则（给未来的自己和接盘者）

1. **修 bug 前先回答**：这个 bug 在 0.1.39（最后一个全绿版本）存在吗？
   - 不存在 → 是 0.1.39 之后某次改动引入的。**先 git diff 找到引入点，恢复原行为，再考虑要不要保留新特性**。不要在引入 bug 的代码上继续叠补丁。
2. **任何触碰 `refreshAccount`/`classify` 的改动**：先重读本文档第 5 节。Antigravity 的凭证策略已被验证过一次错误方向，不要用「看起来更健壮」的理由再踩一遍。
3. **新增供应商**：只需 ①core 加 `provider/<id>/` 实现 `Provider` 接口，②`ProviderBootstrap` 加一行，③app 加 `ProviderUi` 实现并在 `ProviderUiRegistry` 登记。框架自动获得账号池、Failover、代理分流、日志、保活。
4. **测试要求**：`:core:test` 全绿才能出包。协议相关改动必须先加失败用例再修（复现→修→锁住）。
5. **构建三坑**（aarch64 PRoot 容器）：JDK 必须 17；必须 `LD_PRELOAD=/opt/android-sdk/lib/libdelfix.so`；aapt2/zipalign 必须用 aarch64 静态版（备份在 `~/.aicode/backup/`）。详见全局记忆 `android-build-arm64-proot`。
6. **版本纪律**：每次交付 versionCode+1 / versionName patch+1；出包后 `aapt2 dump badging` 核对；产物命名 `ai-gateway-<版本>-release.apk`。
7. **外部来源必须可追溯**：上游协议细节、UI 动效参数凡参考了外部项目，都要在代码注释里写明来源，
   并在 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) 汇总（项目名 / 作者 / 地址 / 许可证 / 具体借鉴内容）。
   提交信息正文同样要标注「借鉴自 <仓库>（作者，协议）：<内容>」。没有许可证的项目只参考行为与结论，不复制代码。
