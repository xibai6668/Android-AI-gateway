# AI 聚合网关

Android 上运行的本地 AI 模型聚合网关：把多家私有 AI 服务账号统一翻译成 OpenAI 兼容接口，暴露在 `http://127.0.0.1:8790/v1`。

任何支持 OpenAI 协议的客户端（Cline、Cherry Studio、Continue、curl 脚本……）都能直接接上手机里的所有账号额度。

`Kotlin` · `Jetpack Compose` · `NanoHTTPD` · `Gradle` · `minSdk 26 / targetSdk 36` · `MIT`

> 改动代码前请先读 **[ARCHITECTURE.md](ARCHITECTURE.md)**——完整的架构地图、模块职责、协议陷阱清单与改动守则。

---

## 目录

- [特性](#特性)
- [架构总览](#架构总览)
- [模块结构](#模块结构)
- [接口与鉴权](#接口与鉴权)
- [供应商](#供应商)
- [贡献](#贡献)

---

## 特性

- **OpenAI 兼容网关**：`/v1/chat/completions` + `/v1/models`，流式 SSE / 非流式 / 多模态识图。
- **五家供应商**：Trae、WorkBuddy、Antigravity、Loomy，以及任意自定义 OpenAI 兼容中转站。
- **同款模型 Failover**：首选供应商失败或无可用账号时，自动切换到拥有同款模型的其他供应商。
- **智能路由**：`供应商/模型` 前缀路由，兼支持无前缀的模型名自动推导。
- **国内外智能分流**：国内上游强制直连，Google 按需走代理。
- **后台保活**：前台服务 + WakeLock / WifiLock + 通知自愈，锁屏仍可服务。
- **账号管理**：多账号轮询、额度查询、跨供应商容灾、批量签到、任务中心。

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
| `:app` | Android 应用 | Compose UI、前台服务与保活、加密存储实现、控制中心磁贴 |

供应商协议实现位于 `core/src/main/kotlin/dev/aigw/core/provider/`，每家的私有协议细节都封装在各自子包内（`trae/`、`codebuddy/`、`antigravity/`、`loomy/`、`custom/`），互不干扰。

---

## 接口与鉴权

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/v1/chat/completions` | 对话补全，支持流式 SSE / 非流式 / 多模态 |
| `GET` | `/v1/models` | 模型列表（各供应商就绪即流式返回） |
| `GET` | `/v1/credits` | 额度查询，**仅本机访问** |
| `GET` | `/healthz` | 健康检查，**仅本机访问** |
| `GET` | `/authorize` | OAuth 登录回调入口 |

鉴权支持三种方式，任一即可：`Authorization: Bearer <key>`、`x-goog-api-key: <key>`、查询参数 `?key=<key>`。可在设置中开启「无 Key 调用」。服务同时处理 `OPTIONS` 预检并返回 CORS 头，供浏览器端客户端使用。

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
| `custom:<key>` | 自定义 | 用户填写的 baseUrl | 任意 OpenAI 兼容中转站，一个 key = 一个账号 |

---

## 贡献

1. 改动前先读 [ARCHITECTURE.md](ARCHITECTURE.md)。
2. **新增供应商**只需三步：`core` 实现 `Provider` 接口 → 在 `ProviderBootstrap` 登记 → `app` 实现并登记 `ProviderUi`。框架自动获得账号池、Failover、代理分流、日志与保活。
3. 保持 `:core:test` 全绿。

## 许可证

[MIT](LICENSE)
