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
| `gateway/GatewayHttpServer.kt` | NanoHTTPD 服务器：`/v1/chat/completions`（含 Failover 循环）、`/v1/models`、`/v1/images/generations`、`/v1/audio/transcriptions`、`/v1/credits`、`/api/usage/token`（new-api 形状）、`/v1/health`（近期健康快照）、`/v1/route`（路由预览：这次会走谁、为什么）、`/v1/usage` + `/v1/usage/requests`（聚合与逐条用量）、`/v1/limit`（当前密钥的限额状态）、`/healthz`、`/authorize`（Trae 回调）；请求执行器换成了有界线程池（`BoundedAsyncRunner`） | 鉴权兼容 Bearer/x-goog-api-key/?key=；**多密钥优先于全局单 Key**，命中后按该密钥的白名单/限额执行；`/v1/health`、`/v1/route`、`/v1/usage*`、`/v1/limit` 与本机回环的 `/v1/credits` 一样免 Key（含账号昵称，不向局域网暴露）；**默认 `DefaultAsyncRunner` 是每请求一线程且无上限**，慢客户端能顶到 OOM——已换成 32 线程 / 32 排队、队满直接关连接；socket 读超时 60s（原为 0=永不超时）；**非流式响应一律 `Connection: close`**（空闲 keep-alive 连接会占着工作线程读到超时，并发一高就吃光线程池）；媒体端点只从声明了 `supportsImageGeneration` / `supportsAudioTranscription` 的供应商里选候选，**音频上传必须走 `readBodyBytes`**（`readBody` 会把二进制当 UTF-8 解码而损坏文件）；**余额只能按同单位汇总**（见下面 `creditSnapshot`） |
| `gateway/MultipartForm.kt` | `multipart/form-data` 字节级解析（音频上传用） | 全程在 ByteArray 上定位分隔符，**不得先转字符串**（非法字节会被替换成 U+FFFD）；core 不引第三方上传库 |
| `gateway/BoundedAsyncRunner.kt` | 有界请求执行器：固定线程池 + 有界队列，满了 `handler.close()` 快速失败 | 线程数按「能同时挂多少条流」定（流式转发全程占线程），**不要用 `CallerRunsPolicy`**：拒绝的请求会回到 accept 线程同步执行，把唯一接受循环卡死 |
| `gateway/GatewaySettings.kt` | 全局设置（端口、apiKey、allowNoKey、maxRotate=换号上限、refreshSkewSeconds、defaultProvider） | `maxRotate` 默认 3：单请求最多试 3 个账号 |
| `gateway/ClientKeys.kt` | 客户端密钥：`ClientKeyStore`（增删改查/轮换，常量时间比对）、`ClientKey`（`allowedModels` 白名单 + `limits`）、`KeyLimit`（日/周/月/每 N 小时/每 N 天，tokens 与费用两维）、`ClientKeyUsageLedger`（用量台账，**独立于调用记录开关**） | 密钥与上游凭证完全分离；限额在**打上游之前**拒绝（429 + Retry-After + 重置时刻）；多窗口都超额时报「重置最晚」那条；台账落盘，清日志不会把额度「赚」回来 |
| `gateway/SessionAffinity.kt` | 会话 → (供应商, 账号) 亲和表（TTL 30 分钟、容量 2048、按最久未用淘汰） | 只为命中上游前缀缓存；**复用前仍走完整可用性校验**，不可用就漂移，绝不因亲和让请求失败 |
| `gateway/RoutingGroups.kt` | 路由组与规则：`RoutingGroup`（成员 + 策略 ORDER/WEIGHT + 规则）、`GroupRule`（token 阈值/图片/压缩/工作目录/时段/星期，条件为「与」）、`RoutingGroupResolver`（`orderMembers`/`matches`/请求体解析） | 组对客户端就是一个模型 `group/<id>`；规则只在**一轮开始时**判定，工具回调中途不换模型（否则上游缓存整段作废）；停用的组不应用规则 |
| `gateway/GatewayMiddleware.kt` | 请求改写链：`ModelAliasMiddleware`（别名）、`SystemPromptAppendMiddleware`、`ThinkTagsStripMiddleware`、`MiddlewareChain` | **钩子抛异常就跳过它继续下一个**，改写永远不能让请求失败；空链走零开销路径 |
| `usage/ModelPricing.kt` | 模型价格表（四单价齐全才有效）与 `costOf` | **算不出价格返回 null，绝不当 0**；缓存读/写是 prompt 的子集，不重复计费 |
| `gateway/ProxySettings.kt` | 代理开关 + `DOMESTIC_SUFFIXES` 国内域名强制直连白名单 | 白名单优先级 > 一切代理设置 |
| `gateway/ProviderBootstrap.kt` | 内置 Provider 登记处 | 新增供应商在此加一行 |
| `gateway/LoopbackCallbackServer.kt` | OAuth 登录回调监听（端口/路径由各 provider 声明：Trae 51120 / Antigravity 51121 / ChatGPT 1455） | 临时端口，用完即释放；redirect_uri 是上游注册值，provider 不得随意改 |
| `gateway/OpenAiApi.kt` | OpenAI 报文拼装（error/modelList） | |
| `failover/` | 容灾调度：`FailoverConfig`（候选/重试/熔断/并发/账号冷却参数 + `accountStrategy`）、`AccountStrategy`/`orderAccounts`（SMART/ORDER/ROTATE/USAGE/PACE 五种选号策略，纯函数）、`weightedOrder`（**priority 先决、同 priority 内按 weight 加权随机**）、`FailoverDecision`（错误→重试/换家/终止）、`ProviderCircuitBreaker`（供应商级 + `供应商|模型` 级两套独立熔断器）、`ConcurrencyGate`（全局/单账号在途上限，`concurrency` 配置热生效；**默认单账号串行 `maxPerAccount=1`、满载最多排队 30s 后 429**——上游按账号限并发流，同账号并发会被挂住只回一个空首 chunk，官方客户端本身也是一次一个请求；**选号会优先挑还有在途余量的账号**，否则多账号也会全挤在优先级最高的那个上排队）、`ProviderMetricsTracker`（健康指标，仅观测）、`ModelCooldownTracker`（按 `供应商|模型` 记模型级冷却，仅内存） | 熔断只计上游侧错误，客户端 400 不计；**模型级错误（CodeBuddy `6004`、Trae `1005`）不计熔断、不换号**，只冷却该模型；并发许可覆盖含流式转发的全过程，`Handle.release` 幂等；PACE 下「额度未知」与「已失效」分档，已失效排最后 |
| `usage/ModelHealthWindow.kt` | 供应商 × 模型的近期健康窗口（最近 50 次）：成功率、耗时/TTFB 的 p50/p95（**nearest-rank，不做插值**）、失败原因 Top-5；仅内存 | 由 `recordCall` 统一写入，**不受「调用记录开关」影响**（它是排障视图，不占存储） |
| `pool/AccountPool.kt` | 账号池：选号（优先级→余额）、硬禁用（disabled）、本地冷却（`coolDown`/`isCooling`，默认 15s，仅内存）、持久化 | 冷却不改变 enabled/disabled（界面仍显示可用），到期自动恢复；重新登录/换新凭证与删除账号都会清掉冷却 |
| `protocol/OpenAiGemini.kt` | OpenAI⇄Gemini 双向转换（Antigravity 专用）：严格 user/model 角色交替、tool 聚合、thinking、safetySettings | **上游强制要求 contents 角色交替**，连续同 role 会 400 |
| `provider/Provider.kt` | Provider 接口 + AuthKind 枚举 | 抽象边界，勿随意扩接口 |
| `provider/ChatCall.kt` | 上游响应封装（stream/aggregated/failure 三态） | |
| `provider/StreamSupport.kt` | `LineTransformStream`（逐行翻译）、`OpenAiSseAggregator`（SSE聚合/completionAsSse/isEmptyCompletion） | SSE 帧必须以 `\n\n` 结尾 |
| `provider/chatgpt/` | ChatGPT 订阅（Codex 后端，OpenAI Responses 协议）| 请求转换封死在 `OpenAiResponses.kt`：system→developer、store:false、include encrypted_content、tools `strict:false`；**上游只支持流式**；凭证含 refresh_token 轮换 |
| `provider/MediaCall.kt` · `provider/MediaCalls.kt` | 图片生成 / 语音转写的内部语义与返回封装 | 媒体端点没有流式语义，只有成功/失败两态；provider 用 `supportsImageGeneration` / `supportsAudioTranscription` 声明能力，网关只在声明者之间做 Failover |

#### 语音转写的两条链路（能力必须来自上游声明）

`/v1/audio/transcriptions` 有两类实现，靠 `ConversationalTranscriber` 标记区分：

1. **原生转写端点**：`custom` 直接把 multipart 转发到 `<baseUrl>/audio/transcriptions`，这是真正的 ASR。
2. **对话模型代转写**：Antigravity / Loomy 把音频作为 `input_audio` part 发给模型，让它逐字复述。
   质量不如 ASR（长音频会漏字、专名易错），且**只在模型目录真的声明了音频输入时**才允许：
   - Antigravity：`fetchAvailableModels` 里的 `inputModalities`（兼容 `supportedInputModalities` / `input_modalities`）含 audio → `ProviderModel.extra["audio"]="1"`
   - Loomy：`modalities.input` 含 audio → 同上

铁律：**目录没声明音频能力就不得硬塞给文本模型**。模型会静默丢掉音频并回一段与录音无关的文本，
那是凭空编造。`audioCandidateRoutes` 里有原生端点的供应商排在前面，不指定 model 时不会被悄悄导向差链路。
| `provider/antigravity/` | Google Antigravity（Gemini 协议）| **凭证策略见下文第 5 节，严禁回退**；额度查询完全对接 CLIProxyAPI |
| `provider/codebuddy/` | 腾讯 WorkBuddy（国内 copilot.tencent.com / 国际 workbuddy.ai）| 上游拒绝非流式，必须 stream:true |
| `provider/trae/` | 字节 Trae SOLO 通道（最重的协议转换）| |
| `provider/loomy/` | 讯飞 Loomy（HTTP 200 + 业务码陷阱）| 模型目录 `modalities.input` 的 audio 位要采集，转写靠它路由 |
| `provider/raccoon/` | 商汤小浣熊（`xiaohuanxiong.com`，web 通道，网页回调登录）| OpenAI 结构包在 `data` 里、`delta` 是字符串；字段用标准名（v2 是 LiteLLM 层，`max_new_tokens` 会被 500 拒）；有积分余额接口 |
| `provider/custom/` | 任意 OpenAI 兼容中转站 | 一个 key = 一个账号 |
| `store/KeyValueStore.kt` | 存储抽象（App 侧用 EncryptedSharedPreferences 实现） | |
| `security/` | `SecuritySettings`（脱敏/限速开关）、`RequestSanitizer`（system 提示词零宽字符脱敏 + 出网取证）、`AccountRateLimiter`（账号级最小间隔 + 抖动） | 只改 system 消息，不碰 user 输入 |
| `usage/` | `CallLogStore`（调用记录，每条一键；超出 8K 摘要的完整原文溢出到缓存目录供详情页回读）、`RequestLog`（环形运行日志，容量可运行期调整）、`StorageAudit`（按前缀统计占用） | 记录有容量淘汰与保留天数清理；溢出文件随删除/清空/过期清理/截断一并回收；**请求与尝试分层计量**（`tries`/`retried` 是一次请求内的尝试数，不是请求数） |
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
settings/client_keys.json          # 客户端密钥（含白名单与限额）
settings/routing_groups.json       # 路由组与规则
settings/model_pricing.json        # 模型价格表
settings/middleware.json           # 请求改写（别名/system 追加/think 标签）
clientkey/usage|window|cycle/...   # 密钥用量台账与限额窗口
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

### ChatGPT（Codex 订阅，同类风险）
同一套刷新纪律，差异点只有两个：
- **登录回调有 state**：`completeCallback` 校验 state 并一次性消费 verifier（内存，不落盘）；
  `state` 不匹配 = 直接拒绝，不做「容错重试」。
- **refresh 是 JSON，code 交换是 form**：写反只会拿到 `invalid_request`，没有更具体的提示。

---

## 6. 协议陷阱清单（上游实测）

### ChatGPT（Responses 协议）
- **上游只支持流式**：非流式请求在网关侧用 `OpenAiSseAggregator` 聚合，不要给上游发 `stream:false`。
- `store` 必须 `false`；`include` 必须含 `reasoning.encrypted_content`，否则 Codex 直接拒绝。
- **不能带** `temperature` / `top_p` / `max_output_tokens`；system 提示必须转成 `developer` 角色放进 `input`。
- 工具必须显式 `strict: false`，省略会被当成 `strict: true`，schema 里可选参数变必填。
- SSE 事件以 `data:` JSON 里的 `type` 为准；`event:` 行仅作 fallback（部分代理只透传一种）。
- `function_call` 的 `call_id` 才是客户端 `tool_call_id` 的回传键（不是 `id`）。
- 用量是**滚动窗口百分比**（5 小时 / 周），不是剩余次数：`used_percent` 投影成 `limit=100` 的额度包。

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
    - `loadCodeAssist` 与 `retrieveUserQuotaSummary` 一律用裸 hub UA `antigravity/hub/<ver> darwin/arm64`，base 固定 `QUOTA_BASE`（prod）；
    - `loadCodeAssist` 的 metadata **只带 `ideType=ANTIGRAVITY`**（不带 platform/pluginType）；
    - 只发 `Authorization` / `Content-Type` / `User-Agent` 三个头，不发 `X-Goog-Api-Client` / `Client-Metadata`；
    - 失败回退：剥 `project` 重试 → sandbox → prod；只有 401 才报登录失效，403 报真实状态码。
    > 历史弯路（不要再重复）：曾试过 `antigravity/cli/<ver>`、带 `(aidev_client; cl=..)` 段的完整 hub 形态、`ideType` 在 `ANTIGRAVITY`/`IDE_UNSPECIFIED` 间探测、给 metadata 补齐 `platform`/`pluginType`——这些都没能解决 #3501，且客户端形态已被官方论坛证伪（同一账号官方 IDE 正常、直连 API 仍 403）。
  - `resolveModel` 先查「模型目录里真有的 id」再做子串路由：`gemini-4-argon` 含 `pro`，否则会被改写成 `gemini-pro-agent`。
  - **对话/模型列表与额度查询的域名必须分开**（`CHAT_BASE` vs `QUOTA_BASE`）：对话走 `daily-cloudcode-pa`，prod 对消费级账号的对话请求会直接回 `429 RESOURCE_EXHAUSTED`（与额度无关，模型页「测试」会原样弹出）；配额查询反过来只有 prod 给完整数据。两者绝不能共用一个 base——曾因把 `QUOTA_BASE` 改成 prod 顺带把对话也换掉，导致全站对话 429。
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
- 成长任务由 `ACTION_TASKS`（任务中心的「批量任务」/账号页的「成长中心」）一并做完：**先接单，再按客户端埋点推进进度，最后领奖**，不存在「只接单不做事」的独立动作。进度记在 `POST /v2/report`，body 是**裸数组**（不是 `{code,msg,data}` 信封）——官方客户端的「去完成」就是这个请求。四条铁律：
  - **先 accept 再做事**：没接单的一律不计进度（前置 `first_buddy` 不满足时其余任务连 accept 都会被拒）；
  - **埋点里的会话必须真实**：`conversationId` 用刚发出的那次对话的会话 id（自造 UUID 上游不认），对话类任务因此还要真发一次对话，拿不到真实会话时才退回参照实现的合成 id 兜底；
  - 设备指纹（`machineId`/`sessionId`/`qimei36`）必须**按 uid 稳定派生**：随机值会被当成新设备，多账号共用同一组又会被当成同一台机器；
  - 领奖判据用 `progress.current >= progress.target`，不用可能延迟或回滚的 `accept_status`。
  事件配方集中在 `CodeBuddyTaskComplete`（照抄 L0NE-6/WorkBuddy-Daily，见 THIRD-PARTY-NOTICES.md），覆盖云端成长任务；对话类任务（`chat_5` 等）还要真发一次对话，夜猫子只在 CST 23:00-08:00 计数。小程序链式任务、需真人操作的任务（关注公众号/绑定微信）与已下线的活动均不在内。

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
