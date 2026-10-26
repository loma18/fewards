<div align="center">

<img src="docs/images/app-icon.png" width="112" alt="Fewards" />

# Fewards

**Android auto check-in and credit dashboard for WorkBuddy — one tap runs the daily task and growth rewards.**

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

**English** | [简体中文](doc/README_zh-CN.md)
</div>

---

Kotlin + Jetpack Compose, with a **Miuix** UI throughout (HyperOS style).

<p align="center">
  <img src="docs/screenshots/home-light.png" width="24%" alt="Home (light)" />
  <img src="docs/screenshots/account-light.png" width="24%" alt="Accounts (light)" />
  <img src="docs/screenshots/settings-light.png" width="24%" alt="Settings (light)" />
  <img src="docs/screenshots/theme-light.png" width="24%" alt="Theme (light)" />
</p>
<p align="center">
  <sub>Light · Home · Accounts · Settings · Theme</sub>
</p>

<p align="center">
  <img src="docs/screenshots/home-dark.png" width="24%" alt="Home (dark)" />
  <img src="docs/screenshots/account-dark.png" width="24%" alt="Accounts (dark)" />
  <img src="docs/screenshots/settings-dark.png" width="24%" alt="Settings (dark)" />
  <img src="docs/screenshots/theme-dark.png" width="24%" alt="Theme (dark)" />
</p>
<p align="center">
  <sub>Dark · Home · Accounts · Settings · Theme</sub>
</p>

## Download

Download the latest `Fewards-<version>-release.apk` (release + R8) from [Releases](https://github.com/functy23/fewards/releases/latest).

## What it can do

| | |
|---|---|
| **WorkBuddy check-in** | WeChat / QQ QR-code authorization, or paste the desktop accessToken; automatic daily points check-in |
| **Growth center** | Travel gift, Buddy dispatch, tasks, missed-day make-up, streak rewards, blind boxes — runs right after check-in, or on demand with "Claim all" |
| **Credits** | Per-account balance and package expiry, plus a dashboard on the Accounts tab: **total usable credits**, and how much expires **within 3 / 5 days** (per account too) |
| **Credential upkeep** | Optional auto-refresh of near-expiry tokens, with a "Check & refresh" action and per-account time-to-live |
| **Device sync** | Pairs with the desktop console over LAN: refresh → push → pull back, so both sides always share one refresh-token chain |

- **Multiple accounts** — re-authorizing the same account updates it instead of adding a new one
- **One pipeline** — the home screen's "Start" button and the Control Center "check-in" tile run the same flow, with live progress notifications while it runs
- **Keeps running in the background** — execution goes through WorkManager, so it still finishes after the process is killed
- **Local-first** — credentials are stored only in the device's own SharedPreferences, and logs never print tokens

> HoYoLAB (米游社) and Microsoft Rewards support was removed in this version: the app is WorkBuddy-only now.
> Old `sec.mhy.accounts` / `sec.bing.accounts` keys are left untouched but no longer read.

## Usage

1. **Accounts** — tap "+" in the top-right corner and scan with WeChat / QQ, or paste the desktop accessToken. The account row shows its remaining credits and how much expires within 3 / 5 days; the star button opens the per-package credit details
2. **Settings** — toggle the growth center, credential auto-refresh, device sync, notifications and the completion overview
3. **Home** — keep WorkBuddy ticked → Start

On macOS, to grab the desktop token directly:

```bash
chmod +x scripts/workbuddy-token.sh
./scripts/workbuddy-token.sh
```

## Sync with the desktop console

Fewards can hand WorkBuddy credentials to [workbuddy-console](https://github.com/loma18/workbuddy-console) over the local network, and take back whatever the desktop refreshed. Optional — the phone works fine on its own.

### Why it exists

WorkBuddy's `refresh_token` is **rotating**: one successful refresh invalidates the previous value everywhere else. Before this feature the phone stored the token but never used it, so an account silently died once its `access_token` expired — the only fix was re-scanning the QR code.

### Setup

1. On the desktop, open **Settings → 手机同步（局域网）**, turn it on, copy the **address** and **pair code**
2. On the phone, open **Settings → WorkBuddy → 设备同步**, paste both values
3. Tick the accounts you want (all ticked by default) → **同步选中账号**

### One button, three steps

**同步选中账号** does all of it in one tap:

| Step | What happens |
|---|---|
| 1. Refresh | Calls the official refresh endpoint with each selected account's `refresh_token` |
| 2. Push | Sends the fresh credentials to the desktop |
| 3. Pull back | Writes whatever the desktop returns (it may have refreshed some accounts itself) |

Why merged: a refresh **rotates** the token, so the desktop's copy dies the moment the refresh succeeds. With two separate buttons, forgetting the second step silently kills the account on the desktop. Done together, there is no window in between.

If no address / pair code is configured, it degrades to "refresh locally only" and says so in the result line.

### Notes

- Both devices must be on the **same LAN** (same Wi-Fi, or the desktop on the phone's hotspot). Guest networks with AP isolation will not work.
- Only **private addresses** are accepted (`10.x` / `172.16-31.x` / `192.168.x` / `127.x`). Public IPs and domain names are rejected on purpose — these are long-lived credentials.
- Cleartext HTTP is allowed for this feature (`res/xml/network_security_config.xml`). Android 9+ blocks it by default and a home LAN has no certificate.
- The account the desktop is **currently signed in with** is protected: the desktop returns it untouched, and the phone keeps its own copy. Overwriting it would sign the desktop out.
- Accounts imported by pasting a token have no `uid`; the desktop shows them as `wb_<timestamp>`. Functionality is unaffected.
- **No background auto-refresh.** Doze and OEM battery savers make scheduled work unreliable, and a refresh without a successful push would leave the desktop broken. Open the app and tap refresh instead.
- If the desktop is offline the push fails with a readable message and nothing is written locally, so you can retry later.

Covered by unit tests: `./gradlew :app:testDebugUnitTest` (protocol fields, time units, address validation, client request headers).

## Build

The package you ship to users must be **release + R8**:

```bash
./gradlew :app:assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

Tests: `./gradlew :app:testDebugUnitTest`

Toolchain: AGP 9.4.0 / Kotlin 2.4.10 / Compose BOM 2026.08.00 / miuix 0.9.4 / miuix-nav 0.9.4 / minSdk 31 / target 37 / Java 21.

## Architecture

```
app/src/main/java/com/functy/fewards/
├── core/workbuddy/       # check-in engine, growth center, credits, QR authorization, refresh
├── core/lansync/         # LAN sync protocol and HTTP client
├── data/repository/      # settings, accounts, config import/export
├── ui/screen/            # home / accounts / settings / theme / about (Miuix)
├── ui/viewmodel/
└── work/                 # WorkManager workers, notifications
```

Agent conventions live in [AGENTS.md](AGENTS.md).

## APIs and references

- WorkBuddy check-in: `copilot.tencent.com/billing/meter/*`
- WorkBuddy QR authorization: `copilot.tencent.com/v2/plugin/auth/state|token` + `/v2/plugin/login/account` (`platform=CLI`; a non-zero code before the QR code is scanned is the normal waiting state)
- WorkBuddy growth center: `www.codebuddy.cn/v2/activity/growth/*` (travel / tasks / streak / makeup-cards / redeem / lottery / buddy boxes)
- WorkBuddy credits: `www.codebuddy.cn/billing/meter/get-user-resource-summary` for the authoritative amounts, plus `get-user-resource-paid-packages` / `-free-packages` for package expiry. Per-package expiry is the earliest still-funded record, and `DeductionEndTime` is treated as a 30-day placeholder when it is a whole month past `CycleEndTime`
- WorkBuddy token refresh: `POST /v2/plugin/auth/token/refresh` with `X-Refresh-Token` + the desktop fingerprint headers
- UI references: [KernelSU manager](https://github.com/tiann/KernelSU) and [InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived)

Earlier repositories with the same functionality are archived — use only this one: [autofewards](https://github.com/functy23/autofewards), [autorewards-runner](https://github.com/functy23/autorewards-runner) (the Flutter original).

## Disclaimer

For learning and study only; comply with each platform's terms; you bear the account risk yourself.
