<div align="center">

<img src="../docs/images/app-icon.png" width="112" alt="Fewards" />

# Fewards

**Android 自动签到与积分看板：WorkBuddy 日常任务与成长中心，一键跑完。**

[![Fewards](https://img.shields.io/badge/Fewards-AUTO-orange.svg)](https://github.com/functy23/fewards)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-purple.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Top Language](https://img.shields.io/github/languages/top/functy23/fewards?style=flat)](https://github.com/functy23/fewards)
[![Platform](https://img.shields.io/badge/platform-Android%2012%2B-lightgrey.svg?logo=android&logoColor=white)](https://github.com/functy23/fewards)

[![CI](https://img.shields.io/github/actions/workflow/status/functy23/fewards/test.yml?branch=main&label=CI&logo=githubactions&logoColor=white)](https://github.com/functy23/fewards/actions/workflows/test.yml)

[![Release](https://img.shields.io/github/v/release/functy23/fewards?style=flat&logo=github)](https://github.com/functy23/fewards/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/functy23/fewards/total?label=Downloads&logo=github)](https://github.com/functy23/fewards/releases)
[![Stars](https://img.shields.io/github/stars/functy23/fewards?style=flat&logo=github)](https://github.com/functy23/fewards/stargazers)
[![Repo Size](https://img.shields.io/github/repo-size/functy23/fewards?style=flat&logo=github)](https://github.com/functy23/fewards)
[![Contributors](https://img.shields.io/github/contributors/functy23/fewards?color=ee8449&logo=githubsponsors)](https://github.com/functy23/fewards/graphs/contributors)

[Issues](https://github.com/functy23/fewards/issues) • [AGENTS.md](AGENTS.md) • [Releases](https://github.com/functy23/fewards/releases)

[English](../README.md) | **简体中文**
</div>

---

Kotlin + Jetpack Compose，界面全程 **Miuix**（HyperOS 风格）。

<p align="center">
  <img src="docs/screenshots/home-light.png" width="24%" alt="首页（浅色）" />
  <img src="docs/screenshots/account-light.png" width="24%" alt="账号（浅色）" />
  <img src="docs/screenshots/settings-light.png" width="24%" alt="设置（浅色）" />
  <img src="docs/screenshots/theme-light.png" width="24%" alt="主题（浅色）" />
</p>
<p align="center">
  <sub>浅色 · 首页 · 账号 · 设置 · 主题</sub>
</p>

<p align="center">
  <img src="docs/screenshots/home-dark.png" width="24%" alt="首页（深色）" />
  <img src="docs/screenshots/account-dark.png" width="24%" alt="账号（深色）" />
  <img src="docs/screenshots/settings-dark.png" width="24%" alt="设置（深色）" />
  <img src="docs/screenshots/theme-dark.png" width="24%" alt="主题（深色）" />
</p>
<p align="center">
  <sub>深色 · 首页 · 账号 · 设置 · 主题</sub>
</p>

## 下载

到 [Releases](https://github.com/functy23/fewards/releases/latest) 下载最新 `Fewards-<版本>-release.apk`（release + R8）。

## 能做什么

| | |
|---|---|
| **WorkBuddy 签到** | 微信 / QQ 扫码授权或粘贴桌面端 accessToken；每日积分自动签到 |
| **成长中心** | 旅行礼物 / 派 Buddy / 任务 / 断登补签 / 连登兑换 / 盲盒，签到后自动跑，也可手动「一键领取」 |
| **积分** | 每个账号的余额与积分包到期，账号页顶部汇总**总剩余可用积分**以及 **3 日 / 5 日内到期**的可用积分（每个账号行内也有） |
| **凭据维护** | 临期自动刷新（可关）+「检查并刷新」+ 各账号剩余有效期 |
| **设备同步** | 与电脑端 workbuddy-console 局域网配对：刷新 → 推送 → 取回，两端共用一条 refresh_token 链 |

- **多账号** — 同一账号重复授权按 uid 更新而非新增
- **一条链路** — 首页「开始执行」与控制中心「签到」磁贴走同一套流程，执行中实时进度通知
- **后台可续** — 执行走 WorkManager，进程被杀后仍能跑完
- **本地优先** — 凭据只存本机 SharedPreferences，日志不打印 token / cookie

## 使用

1. **账号** — 右上角「+」用微信 / QQ 扫码授权，或粘贴桌面端 accessToken。账号行会显示剩余积分与 3 / 5 日内到期额度，星标按钮打开积分明细
2. **设置** — 开关成长中心、凭据自动刷新、设备同步、通知与完成总览
3. **首页** — 勾选 WorkBuddy → 开始执行

> 本版本已移除米游社与微软积分（Bing）支持，只保留 WorkBuddy。
> 旧的 `sec.mhy.accounts` / `sec.bing.accounts` 键不再读取，也不会被清掉。

macOS 想直接取桌面端 token：

```bash
chmod +x scripts/workbuddy-token.sh
./scripts/workbuddy-token.sh
```

## 构建

给用户装的包必须是 **release + R8**：

```bash
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

测试：`./gradlew :app:testDebugUnitTest`

工具链：AGP 9.4.0 / Kotlin 2.4.10 / Compose BOM 2026.08.00 / miuix 0.9.4 / miuix-nav 0.9.4 / minSdk 31 / target 37 / Java 21。

## 架构

```
app/src/main/java/com/functy/fewards/
├── core/mihoyo/          # DS 签名、常量、HTTP、引擎
├── core/workbuddy/       # 签到引擎、扫码授权协议
├── core/lansync/         # 局域网同步协议与客户端
├── data/repository/      # 设置、账号、配置导入导出
├── ui/screen/            # 主页 / 账号 / 设置 / 主题 / 关于（Miuix）
├── ui/viewmodel/
└── work/                 # WorkManager worker、通知
```

Agent 约定见 [AGENTS.md](AGENTS.md)。

## 接口与参考

- WorkBuddy 签到：`copilot.tencent.com/billing/meter/*`
- WorkBuddy 扫码授权：`copilot.tencent.com/v2/plugin/auth/state|token` + `/v2/plugin/login/account`（`platform=CLI`；未扫码返回非 0 码，属正常等待）
- WorkBuddy 成长中心：`www.codebuddy.cn/v2/activity/growth/*`（旅行 / 任务 / 连登 / 补登 / 兑换 / 抽奖 / Buddy 盲盒）
- WorkBuddy 积分：`www.codebuddy.cn/billing/meter/get-user-resource-summary` 给权威数额，`get-user-resource-paid-packages` / `-free-packages` 只补到期时间；包的到期取「还有额度的记录里最早一笔」，`DeductionEndTime` 若比 `CycleEndTime` 晚整 30 天视为长期占位
- WorkBuddy 刷新：`POST /v2/plugin/auth/token/refresh`，需带 `X-Refresh-Token` 与桌面端一致的指纹头
- UI 参考 [KernelSU manager](https://github.com/tiann/KernelSU) 与 [InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived)

早期同功能仓库已归档，请只用本仓库：[autofewards](https://github.com/functy23/autofewards)、[autorewards-runner](https://github.com/functy23/autorewards-runner)（Flutter 母本）。

## 免责声明

仅供学习交流，遵守各平台条款；账号风险自负。
