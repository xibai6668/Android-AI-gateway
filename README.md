# AI 聚合网关

Android 上运行的本地 AI 模型聚合网关：把多家私有 AI 服务账号统一翻译成 OpenAI 兼容接口，暴露在 `http://127.0.0.1:8790/v1`。

任何支持 OpenAI 协议的客户端（Cline、Cherry Studio、Continue、curl 脚本……）都能直接接上手机里的所有账号额度。

`Kotlin` · `Jetpack Compose` · `NanoHTTPD` · `Gradle` · `minSdk 26 / targetSdk 36` · `MIT + 附加使用声明`

> 改动代码前请先读 **[ARCHITECTURE.md](ARCHITECTURE.md)**——完整的架构地图、模块职责、协议陷阱清单与改动守则。

---

## 目录

- [特性](#特性)
- [快速开始](#快速开始)
- [架构总览](#架构总览)
- [模块结构](#模块结构)
- [数据存储](#数据存储)
- [构建与发版](#构建与发版)
- [接口与鉴权](#接口与鉴权)
- [供应商](#供应商)
- [贡献](#贡献)
- [使用声明](#使用声明)
- [许可](#许可)

---

## 特性

- **OpenAI 兼容网关**：`/v1/chat/completions` + `/v1/models`，流式 SSE / 非流式 / 多模态识图；另提供 `/v1/images/generations`（图片生成）与 `/v1/audio/transcriptions`（语音转文字）。
- **六家内置供应商**：Trae、WorkBuddy、Antigravity、ChatGPT、Loomy、小浣熊（商汤），以及任意自定义 OpenAI 兼容中转站。
- **同款模型 Failover**：首选供应商失败或无可用账号时，自动切换到拥有同款模型的其他供应商。
- **智能路由**：`供应商/模型` 前缀路由（含 `trae-cn/`、`codebuddy-global/` 这类区域前缀与 `google/`、`agy/` 等别名），兼支持无前缀的模型名自动推导。
- **账号管理**：同一供应商多账号按余额选号、失败自动换号、额度查询与额度包明细、跨供应商容灾、批量签到与任务中心。
- **模型开放名单**：只把勾选的模型暴露给 `/v1/models`，其余模型对客户端隐藏。
- **用量与日志**：调用记录（请求/转发/返回全链路）、今日与累计用量趋势、运行日志、按前缀统计存储占用与保留期自动清理。
- **网络代理**：HTTP / SOCKS5，支持按供应商与按地址排除；国内上游始终直连。
- **安全防护**：system 提示词零宽字符脱敏 + 账号级最小间隔限速（带抖动）。
- **后台保活**：前台服务 + WakeLock / WifiLock + 控制中心磁贴，锁屏仍可服务。

---

## 快速开始

1. 到 [Releases](https://github.com/xibai6668/Android-AI-gateway/releases) 下载最新的 `ai-gateway-<版本>-release.apk` 安装。
2. 打开 App，首页点「启动网关服务」；首页显示「运行中」即表示端口已在监听。
3. 到「供应商」页登录要用的账号（Trae / WorkBuddy / Antigravity / ChatGPT / Loomy / 小浣熊），或添加自定义 OpenAI 兼容中转站。
4. 客户端里填接入信息：
   - Base URL：手机本机填 `http://127.0.0.1:8790/v1`；局域网内其它设备填首页复制的局域网地址（需先在「接入设置」里打开「同时监听局域网」）。
   - 模型：可填 `供应商/模型`（如 `antigravity/gemini-3.8-flash-high`），也可直接填模型名由网关推导。
   - API Key：默认开启「无 Key 调用」，随意填；关掉后需与「接入设置」里的 Key 一致。

---

## 架构总览

一次请求的生命周期：

```
客户端 (Cline / Cherry Studio / curl)
   │  POST /v1/chat/completions  {model: "...", ...}
   ▼
GatewayHttpServer   ① 鉴权   ② 解析 model
   ▼
GatewayEngine       ③ 解析候选路由
                    ④ 选号 → 预刷新凭证 → 发起上游请求
                    ⑤ 失败则换号，或跨供应商 Failover
   ▼
Provider 实现       上游私有协议 ⇄ OpenAI 协议（双向转换封死在 Provider 内）
   ▼
上游 API
```

**核心不变量**：Provider 的边界是「OpenAI 进、OpenAI 出」。上游私有协议的双向转换全部封死在 Provider 内部，网关层对供应商差异零感知。任何把上游协议细节泄漏到网关层的改动，都会破坏这套可维护性根基。

---

## 模块结构

| 模块 | 类型 | 职责 |
|---|---|---|
| `:core` | 纯 JVM，无 Android 依赖 | 网关引擎、HTTP 服务、账号池、路由与 Failover、各供应商协议实现、存储抽象。可独立单测 |
| `:cli` | 纯 JVM 命令行 | 在本机直接起网关做端到端联调，或不打开 App 也能导入账号 / 签到 / 查用量与日志；可执行名 `aigw` |
| `:app` | Android 应用 | Compose UI、前台服务与保活、加密存储实现、控制中心磁贴 |

供应商协议实现位于 `core/src/main/kotlin/dev/aigw/core/provider/`，每家的私有协议细节都封装在各自子包内（`trae/`、`codebuddy/`、`antigravity/`、`chatgpt/`、`loomy/`、`raccoon/`、`custom/`），互不干扰。

---

## 数据存储

账号凭证与状态**按账号独立存储**（v2.4.0 起）：

```
account/<供应商>/<账号>            # 账号凭证（ProviderAccount.toJson）
pool/state/<供应商>/<账号>.json    # 账号状态（额度 / 启用 / 禁用 / 原因）
settings/...                      # 网关、代理、安全、供应商与自定义供应商等设置
logs/calls/<id>                   # 调用记录，每条一个键
logs/requests.json                # 运行日志（环形缓冲）
usage/...                         # 用量统计
```

- **一个账号一个状态文件**：改某个账号（刷新额度、启用/停用）只重写它自己的键，不再整包重写该供应商的全部账号。
- **旧数据自动迁移**：v2.4.0 之前的状态是「一个供应商一个文件」（`pool/state/<供应商>.json`，内含该供应商全部账号）。新版首次读取时惰性迁移——迁一个账号即从旧文件摘除，旧文件清空后删除；迁移可重复执行，不会回退或丢状态，无需手工处理。
- Android 端凭证经 Android Keystore 加密后存入应用私有目录；命令行端（`aigw`）以明文存于 `--data` 目录（默认 `~/.aigw`）。

---

## 构建与发版

本地构建（产出 debug 包，可与线上 release 包同机共存）：

```sh
./gradlew :app:assembleDebug --console=plain   # 出 debug 包
./gradlew :core:test --console=plain           # 单元测试（:core 是唯一有测试的模块）
./gradlew :cli:installDist --console=plain     # 命令行工具，产出 cli/build/install/aigw/bin/aigw
```

- 产物：`app/build/outputs/apk/debug/app-debug.apk`（包名 `dev.aigw.app.debug`）。
- 版本号硬编码在 `app/build.gradle.kts`（`versionCode` / `versionName`）；改后必须重新构建。
- 本地**不产 release 包**：release 仅由云端 GitHub Actions 手动触发构建并发版，日常 push 不产生任何云端构建。

命令行联调（`:cli`，无需 Android 设备）：

```sh
./gradlew :cli:run --args="serve" --console=plain       # 起网关（默认端口 8790，数据目录 ~/.aigw）
./gradlew :cli:run --args="providers" --console=plain   # 列出供应商
```

`aigw` 子命令：`serve` / `providers` / `accounts` / `credits` / `checkin` / `login` / `browser-login` / `sms` / `models` / `usage` / `logs`；账号与设置存于 `--data` 目录（默认 `~/.aigw`，也可用 `AIGW_DATA` 环境变量改写）。

---

## 接口与鉴权

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/v1/chat/completions` | 对话补全，支持流式 SSE / 非流式 / 多模态 |
| `POST` | `/v1/images/generations` | 图片生成（OpenAI Images API；由具备图像通道的供应商承接，自动换号/切家） |
| `POST` | `/v1/audio/transcriptions` | 语音转文字（multipart 上传；有原生转写端点的供应商优先，否则用声明了音频输入的对话模型代转写） |
| `GET` | `/v1/models` | 模型列表（并发拉取各供应商后聚合返回；按模型开放名单过滤） |
| `GET` | `/v1/credits` | 额度查询（别名 `/credits`；本机免 Key） |
| `GET` | `/api/usage/token` | 令牌用量查询（new-api 形状，`total_available` 为按同单位汇总的真实余额） |
| `GET` | `/healthz` | 健康检查，返回纯文本 `ok`（不带鉴权） |
| `GET` | `/authorize` | Trae 网页登录回调入口（Antigravity / 小浣熊各有独立的本地回调端口） |

鉴权支持三种方式，任一即可：`Authorization: Bearer <key>`、`x-goog-api-key: <key>`、查询参数 `?key=<key>`。可在设置中开启「无 Key 调用」。服务同时处理 `OPTIONS` 预检并返回 CORS 头，供浏览器端客户端使用。

默认只绑定 `127.0.0.1`；打开「接入设置」里的「同时监听局域网」后才会监听全部网卡。注意此时若仍开着「无 Key 调用」，同网段的任何设备都能直接调用网关，`/v1/credits`、`/healthz` 也一样可达。

```sh
curl http://127.0.0.1:8790/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <key>" \
  -d '{"model":"gemini-3.8-flash","stream":true,"messages":[{"role":"user","content":"你好"}]}'
```

---

## 供应商

| id | 供应商 | 上游 | 备注 |
|---|---|---|---|
| `trae` | Trae（字节） | SOLO 私有通道 | 协议转换最重 |
| `codebuddy` | WorkBuddy（腾讯） | 国内 `copilot.tencent.com` / 国际 `workbuddy.ai` | 上游拒绝非流式，强制 `stream:true` |
| `antigravity` | Antigravity（Google） | `cloudcode-pa.googleapis.com` | Gemini 风格协议；凭证策略有铁律，见 ARCHITECTURE.md 第 5 节 |
| `loomy` | Loomy（讯飞） | `xfinfr.com` | 鉴权失败为 HTTP 200 + 业务码，而非 4xx |
| `raccoon` | 小浣熊（商汤） | `xiaohuanxiong.com` | OpenAI 结构被包在 `data` 里；字段用标准名（v2 LiteLLM 层不认 `max_new_tokens`） |
| `chatgpt` | ChatGPT（OpenAI） | `chatgpt.com/backend-api/codex` | Codex 订阅额度（5 小时/周窗口）；OAuth 登录回调端口 1455 |
| `custom:<key>` | 自定义 | 用户填写的 baseUrl | 任意 OpenAI 兼容中转站，一个 key = 一个账号 |

---

## 贡献

1. 改动前先读 [ARCHITECTURE.md](ARCHITECTURE.md)。
2. **新增供应商**只需三步：`core` 实现 `Provider` 接口 → 在 `ProviderBootstrap` 登记 → `app` 实现并登记 `ProviderUi`。框架自动获得账号池、Failover、代理分流、日志与保活。
3. 保持 `:core:test` 全绿。

---

## 使用声明

### 仅供学习与交流

本项目是一个用于学习 HTTP 反向代理、SSE 流式透传、多上游协议适配与 Android 应用（Jetpack Compose）打包等技术主题的实践项目，**仅供个人学习与研究使用**。它不是官方产品，与腾讯公司及 WorkBuddy、商汤及小浣熊、字节跳动及 Trae、科大讯飞及 Loomy、Google 及 Antigravity、OpenAI 及 ChatGPT 均无任何关联，未获得其授权、认可或赞助。

### 关于反向代理行为

本项目实现的是本地反向代理：在你自己的机器上复用你自己账号的登录态，把请求转发到官方上游网关。它不破解、不绕过任何付费或权限校验，使用的额度始终来自你自己账号本就拥有的配额。但需要明确的是，这种「以非官方客户端形态复用登录态」的转发方式，**可能不符合上游服务的用户协议或使用条款**；是否使用、由此产生的一切后果（包括但不限于账号被限流、被风控、被冻结或封禁），均由使用者自行承担。

### 禁止用途

禁止将本项目用于任何商业用途、二次分发牟利、批量账号运营、绕过上游计费或配额限制、或其他违反当地法律法规的活动。如需在生产环境或商业场景中调用相关模型服务，请使用官方渠道与官方 API。

### 凭证与数据风险

本项目会把账号凭证（`accessToken` / `refreshToken` 等）落盘保存：Android 端经 Android Keystore 加密后存入应用私有目录，命令行端（`aigw`）以**明文**文件存放于 `~/.aigw`（可用 `--data` 参数或 `AIGW_DATA` 环境变量改写）。请自行妥善保管，切勿提交到公开仓库、上传到网盘或分享给他人。因凭证泄露造成的损失由使用者自行承担。

### 无担保与权利通知

本项目按「现状」提供，作者不对其可用性、稳定性、安全性或对特定用途的适用性作任何承诺。上游接口随时可能变更，本项目可能随时失效而不再维护。完整条款见 [LICENSE](./LICENSE)。本项目中的接口形态、协议字段等信息来自对各官方客户端公开网络通信的观察与整理，相关商标与服务的权利归其各自所有者；若权利方认为本项目存在不当之处，请联系作者，将及时调整或删除。

---

## 许可

本项目基于 [MIT License](./LICENSE)，可自由使用、修改与分发，须保留版权声明。

需要留意的是：LICENSE 正文之后附有一份**使用声明**，其中第 3 条在 MIT 之上**追加了限制**（禁止商业用途、禁止二次分发牟利、禁止批量账号运营）。因此本项目**不是**纯粹的 MIT 项目——**MIT 条款与使用声明共同构成完整的授权与使用约定**，两者对同一行为给出不同结论时以更严格的一方为准。这也是本项目未在构建脚本中声明 SPDX `"MIT"` 许可标识、而以 [LICENSE](./LICENSE) 共同构成授权约定的原因。
