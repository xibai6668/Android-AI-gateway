# 第三方来源与致谢

本项目的上游协议实现与部分 UI 参数，参考了下列开源项目、官方文档与官方客户端的公开产物。
凡直接借鉴代码、常量或算法的地方，均在对应源码注释中标注来源；本文件汇总全部来源、作者、
许可证与具体借鉴内容，便于审计与履行许可证义务。

> 说明：本项目与下表任何项目及其背后的公司均无隶属关系。所有「逆向」「实测」类来源，
> 仅用于让第三方客户端能够使用自己账号的额度，不涉及绕过付费或分发专有资产。

## 一、开源项目

### 1. rikkahub

- 地址：https://github.com/rikkahub/rikkahub
- 作者：rikkahub 及贡献者
- 许可证：AGPL-3.0
- 借鉴内容（ChatGPT/Codex 通道，**仅协议事实与行为结论，未复制任何代码**）：
  - Codex OAuth 的参数形状（client_id、scope、PKCE S256、`localhost:1455/auth/callback`、
    `codex_cli_simplified_flow`、`originator`）与 JWT claim 路径（`https://api.openai.com/auth`）
  - Codex `/responses` 请求体约束（`store:false`、`include reasoning.encrypted_content`、
    system→developer、tools `strict:false`、不支持采样参数）与 SSE 事件表
  - 订阅档位显示名映射（`prolite`→Pro、`pro`→Pro (More)、`promax`→Pro (Max)）
  - 「上游 SSE 不依赖 Content-Type」的兼容事实与 5 分钟刷新余量
  - 说明：以上是行为/协议事实（事实不受版权保护）；实现全部为重写，未复制其代码或结构

### 2. CLIProxyAPI

- 地址：https://github.com/router-for-me/CLIProxyAPI
- 作者：router-for-me 及贡献者
- 许可证：MIT
- 借鉴内容（Antigravity / Gemini 通道为主）：
  - Antigravity 请求体 envelope 结构与 `geminiToAntigravity` 的字段映射（`core/.../protocol/OpenAiGemini.kt`）
  - `parametersJsonSchema` 字段名、`thinkingConfig` 映射、工具响应 `id` 必带、安全过滤默认关闭
  - `paidTier` 积分请求体（`ideType=ANTIGRAVITY`）、订阅层级展示名映射
  - 客户端版本下限 2.23.0 与兜底版本、daily/prod 端点优先级、内部模型过滤清单
  - `invalid_grant` 按 401 语义降温（issue #4130）、临期 5 分钟才刷新的安全窗口
  - 空响应必须保持可检测为失败（`CustomProviderEmptyResponseTest` 锁住的行为）

### 3. magpie

- 地址：https://github.com/yetone/magpie
- 作者：yetone 及贡献者
- 许可证：MIT
- 借鉴内容（WorkBuddy / CodeBuddy 通道）：
  - 工具 schema 归一化规则（`core/.../provider/codebuddy/CodeBuddyToolSchema.kt`）
  - 「Claude Code / Codex CLI 特征文本」替换表——上游以 `Illegal API invocation from an unapproved channel`
    拒绝带这些特征文本的请求，替换表来自其用真实客户端逐词实测的结论
  - 错误分类与重试口径的对照
- 借鉴内容（网关后台能力，**仅设计思路与行为口径，未复制任何代码**；其实现为 Go + 内嵌 JS，本项目为 Kotlin）：
  - 客户端密钥与上游凭证分离、每密钥可命名/启停/轮换、模型白名单、多窗口限额（日/周/月/每 N 小时），
    以及「超额在打上游之前拒绝 + 429 带 Retry-After 与重置时刻」的口径（`gateway/ClientKeys.kt`）
  - 会话亲和的分档思想（一轮内必保持、跨轮在缓存值得留时保持）与「亲和失败绝不阻断请求」
    （`gateway/SessionAffinity.kt` 取「一轮内必保持」为默认）
  - 路由组的规则维度（token 阈值/图片/压缩请求/工作目录/时段与星期）与「规则只在一轮开始时判定」
    （`gateway/RoutingGroups.kt`）
  - 请求/尝试分层计量（一次请求被重试接住不算失败）、未定价调用「不计为 0 而是标未定价」
    （`usage/CallLogStore.kt`、`usage/ModelPricing.kt`）
  - 中间件钩子「抛异常或超时即视为未改动」的容错铁律（`gateway/GatewayMiddleware.kt`）
  - 可观测内部 API 的划分（路由预览 / 用量 / 限额自查）与「密钥只看得到自己的额度」
    （`gateway/GatewayHttpServer.kt`）

### 4. workbuddy2api-panel

- 地址：https://github.com/linguo2625469/workbuddy2api-panel
- 作者：linguo2625469
- 许可证：MIT
- 借鉴内容（WorkBuddy / CodeBuddy 通道）：
  - 企业账号（`enterpriseId` 非空）没有个人成长体系：签到、成长任务、任务列表直接短路不发请求
  - 工具结果后紧跟 user 消息时插入一条空 assistant（上游 400 11153）
  - 成长任务接口 `/v2/activity/growth/tasks` 的字段口径

### 5. workbuddy-api

- 地址：https://github.com/simplast/workbuddy-api
- 作者：simplast
- 许可证：MIT
- 借鉴内容：
  - `deriveConversationId`：`X-Conversation-ID` 由消息前缀确定性派生（同会话稳定、不同会话不同）。
    本项目把该算法抽到 `core/.../protocol/ConversationFingerprint.kt`，WorkBuddy 与 Loomy 共用
  - 官方 CLI 的会话 ID 头族（`X-Conversation-Request-ID` / `X-Conversation-Message-ID` 等）

### 6. multi2api

- 地址：https://github.com/Practice019/multi2api
- 作者：Practice019
- 许可证：MIT
- 借鉴内容：
  - Loomy 错误分类「先正文 marker、后状态码」的顺序（上游失效信号常是 HTTP 200 + 业务码）
  - 多账号池、熔断与重试策略的对照

### 7. loomy2api

- 地址：https://github.com/Patrick130306/loomy2api
- 作者：Patrick130306
- 许可证：MIT
- 借鉴内容：
  - Loomy 协议文档（`docs/PROTOCOL.md`，由其从官方桌面客户端 0.9.38 解包整理）：
    必带头（`traceparent` 缺失会挂到超时、`loomy-version` 仅做存在性校验）、
    `ChatId`/`MsgId`/`TurnId` 属于**可选计费归因头**而非缓存分桶键、session 14 天无刷新令牌、
    积分模型 `availableBalance = balance + dailyBalance`
  - 该文档纠正了本项目 2.11.0 关于「Loomy 会话 id 决定缓存桶」的错误假设（2.11.2 已回滚）

### 8. wild-work

- 地址：https://github.com/rockswang/wild-work
- 作者：rockswang
- 许可证：**仓库未声明许可证**（截至 2026-10-18 根目录无 LICENSE / COPYING / NOTICE）
- 借鉴内容：**仅参考协议行为与错误码结论，未复制任何代码**
  - Trae 签到/积分接口对设备指纹头的要求（缺任一项上游以 9074「当前参与用户太多」拒绝）
  - Trae 国际版错误码与区域端点行为

### 9. open-claude-ui

- 地址：https://github.com/Caho1/open-claude-ui
- 作者：Caho1
- 许可证：MIT
- 借鉴内容（UI / 动效，本项目为 Compose 实现，只移植参数与算法，未复制代码）：
  - 四套物理弹簧预设（`app/.../ui/Motion.kt` 的 `Springs`）：
    Bouncy k=600/c=30/m=1（ζ≈0.612）、Snappy k=320/c=28（ζ≈0.783）、
    Gentle k=380/c=18（ζ≈0.462）、HeavySnap k=1200/c=80/m=0.1（过阻尼）
  - 7 档非线性 Alpha 滚动渐隐遮罩（`Modifier.scrollFade`）：
    15/30/45/60/75/87% 采样点对应 alpha 0.051/0.149/0.302/0.502/0.702/0.851，触顶触底 2px 归零
  - 状态动效时序：陶土呼吸灯（scale 0.86→1，3s）
  - 思考/详情折叠曲线 `cubic-bezier(.19,1,.22,1)` 200ms（`LogsScreen.DetailBlock`）
  - Token 环形仪表做法：`strokeDasharray = 2πr`、`strokeDashoffset = circumference × (1 − ratio)`
    （`MeScreen.CacheHitGauge`）

  按 MIT 要求，随附其许可全文：

  ```
  MIT License

  Copyright (c) Caho1

  Permission is hereby granted, free of charge, to any person obtaining a copy
  of this software and associated documentation files (the "Software"), to deal
  in the Software without restriction, including without limitation the rights
  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  copies of the Software, and to permit persons to whom the Software is
  furnished to do so, subject to the following conditions:

  The above copyright notice and this permission notice shall be included in all
  copies or substantial portions of the Software.

  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  SOFTWARE.
  ```

### 10. WorkBuddy-Daily

- 地址：https://github.com/L0NE-6/WorkBuddy-Daily
- 作者：L0NE-6
- 许可证：MIT
- 借鉴内容（WorkBuddy / CodeBuddy 通道的**成长任务自动完成**）：
  - 任务进度的真实口径：进度记在客户端埋点上（`POST /v2/report`，body 是**裸数组**），
    「去完成」按钮等价于上报对应事件；每个 task_code 的事件配方
  - 桌面指纹口径（`machineId` / `sessionId` / `qimei36` 由 uid 稳定派生、官方 5.9.3 版本三件套、
    CPU/内存/osVersion 字段）与设备类事件链（桌面 6 连对话、Buddy 应用 5 连）
  - 各任务的具体事件组：`template_5` / `expert_5` / `automation_1` / `create_canvas` /
    `playbook_prompt` / `Buddy_App` / `skill_1` 以及 LOCAL 系列专家任务
  - 顺序铁律：**先接单再做事**（没接单不计进度）、领奖判据用进度而非 `accept_status`、
    夜猫子活动只在 CST 23:00-08:00 计数

  按 MIT 要求，随附其许可全文：

  ```
  MIT License

  Copyright (c) 2026 L0NE-6

  Permission is hereby granted, free of charge, to any person obtaining a copy
  of this software and associated documentation files (the "Software"), to deal
  in the Software without restriction, including without limitation the rights
  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  copies of the Software, and to permit persons to whom the Software is
  furnished to do so, subject to the following conditions:

  The above copyright notice and this permission notice shall be included in all
  copies or substantial portions of the Software.

  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  SOFTWARE.
  ```

## 二、官方文档与官方客户端（非代码借鉴）

| 来源 | 用途 | 位置 |
|---|---|---|
| docs.trae.cn《内置模型限时折扣》 | Trae 模型积分倍率（基准/折扣） | `core/.../provider/trae/ModelRate.kt` |
| forum.trae.cn 帖子（173840 / 175814 / 178015 / 178262） | 社区实测倍率与模型上新公告 | 同上（每条倍率 note 均标注帖子号） |
| 商汤小浣熊桌面客户端（`.deb` 解包） | 积分余额/流水/桌面端登录奖励的端点与响应结构 | `core/.../provider/raccoon/RaccoonProvider.kt` |
| 讯飞 Loomy 桌面客户端 0.9.38（Electron 包） | 请求头、签名算法、模型目录结构 | `core/.../provider/loomy/` |

官方文档与官方客户端产物只用于确认字段语义，不随本项目分发任何专有二进制资产
（字体、音频、客户端安装包等均不包含在本仓库中）。
