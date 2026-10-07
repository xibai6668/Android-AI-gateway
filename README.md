# AI Gateway (AiGW)

**把你的 Android 手机变成一台私有的 AI 模型聚合服务器。**

手机上装着多个 AI 服务账号（Trae、WorkBuddy、Antigravity、Loomy），它们的客户端协议各不相同、无法直接在第三方工具中使用。AI Gateway 把它们**全部翻译成标准 OpenAI 协议**，暴露在 `http://127.0.0.1:8790/v1`——任何支持 OpenAI 协议的客户端（Cherry Studio、Cline、Continue、curl 脚本……）都能透明使用手机里所有账号的额度。

> 📐 **接盘/二次开发前必读**：[ARCHITECTURE.md](ARCHITECTURE.md) —— 完整架构地图、模块职责、协议陷阱清单、Antigravity 凭证策略铁律、改动守则。

---

## 核心功能

| 功能 | 说明 |
|---|---|
| **OpenAI 兼容网关** | `/v1/chat/completions` + `/v1/models`，流式 SSE / 非流式 / 多模态识图 |
| **五家供应商** | Trae（字节）、WorkBuddy（腾讯）、Antigravity（Google）、Loomy（讯飞）、自定义 OpenAI 中转站 |
| **同款模型 Failover** | 首选供应商失败/无账号时自动切换到其他拥有同款模型的可用供应商 |
| **智能路由** | `provider/model` 前缀路由 + 别名识别（google/ gemini/ agy/…）+ 无前缀模型特征推导 |
| **原生 Gemini 端点** | `/v1beta/models/*:generateContent`，兼容 `x-goog-api-key` 与 `?key=` 鉴权 |
| **国内外智能分流** | 国内上游（腾讯/字节/讯飞）强制直连，Google 按需走代理 |
| **系统快捷开关** | 控制中心磁贴一键启停网关 |
| **后台保活** | 前台服务 + WakeLock/WifiLock + 通知自愈补发 |
| **账号管理** | 多账号轮询、额度查询、跨供应商容灾切换、批量签到、任务中心 |
| **模型测速** | Q 弹按压动效，延迟三色显示（<5s 绿 / ≥5s 橙 / 超时红） |

---

## 快速开始

1. 安装 APK，打开 App，点「启动服务」；
2. 首页复制接入地址（本机 `http://127.0.0.1:8790/v1` 或局域网地址）；
3. 到「供应商」页登录各服务账号；
4. 客户端里这样配：
   - **OpenAI 协议**：Base URL = `http://<手机IP>:8790/v1`，模型填 `antigravity/gemini-3.8-flash-high` 或直接 `gemini-3.8-flash`
   - **Google Gemini 协议**：Base URL = `http://<手机IP>:8790`，模型填 `models/gemini-3.8-flash`
   - API Key：开启「无 Key 调用」时随意填

## 构建容器环境

aarch64 PRoot 容器构建的完整环境搭建步骤与三个必踩的坑（JDK 17 / libdelfix.so 垫片 / aarch64 aapt2），见 [ARCHITECTURE.md](ARCHITECTURE.md) 第 7 节及全局构建记忆。

---

## 版本

当前交付版本与改动历史见 `git log`；版本号纪律（versionCode/versionName 递增、产物命名带版本号）见 ARCHITECTURE.md 第 7 节。
