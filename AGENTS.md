# Fewards — Agent 约定

给改这个仓库的 agent 用。人读 README。版本号、依赖坐标、SDK 以 `build.gradle.kts` / `gradle/libs.versions.toml` / `settings.gradle.kts` 为准，不要把某次构建的版本号抄进本文件当永久事实。

## 文档约定（双语 + 徽章）

README 为**英文主文档**（`README.md`）+ **中文全量翻译**（`doc/README_zh-CN.md`），
两份内容一一对应，**改一边必须同步另一边**。两份文件顶部是同一组 shields.io 徽章
（语言/平台/CI/License/Release/Downloads/Stars/Repo Size/Contributors 按仓库实际能力裁剪，
没有的能力不放，避免死链），徽章下面一行语言切换：
`README.md` 用 `**English** | [简体中文](doc/README_zh-CN.md)`，
中文版用 `[English](../README.md) | **简体中文**`。增删徽章时两份一起改。

## 这是什么

Android 自动签到与积分看板 App：**只有 WorkBuddy**（每日积分签到 + 成长中心 + 积分统计 + 凭据维护 + 局域网同步）。Kotlin + Compose，**界面只有 Miuix**。仓库：`https://github.com/functy23/fewards`。包名 `com.functy.fewards`。

权威母本（只认这些，不要另找「更完整」的旧工程）：

| 层 | 认 | 不认 |
|---|---|---|
| UI / 导航 / 主题页手感 | [KernelSU manager](https://github.com/tiann/KernelSU)、导航与主题页可对照 [InstallerX-Revived](https://github.com/wxxsfxyzm/InstallerX-Revived) | Flutter、`rewards_runner`、autofewards 归档仓、本机 `fewards2` |
| WorkBuddy 签到 / 成长中心 | 本仓 `WorkBuddyEngine` / `WorkBuddyGrowth` + 公开 HTTP（`copilot.tencent.com/billing/meter/*`、`www.codebuddy.cn/v2/activity/growth/*`） | `workbuddy-checkin` 空脚手架当实现、臆造头像接口 |
| WorkBuddy 积分 | 本仓 `WorkBuddyCredits` + `workbuddy-console` 的 `server/workbuddy/credits.mjs`（口径一致，改一边要同步另一边） | 自己猜字段名、把逐笔明细相加当余额 |

`rewards_runner` / Flutter 母本含难修细节与严重 BUG，**禁止参考、禁止对照、禁止移植**。旧文档里的「踩坑须知 / 需求细节」作废；只允许引用仍成立的工具链事实（镜像、坐标后缀），并以本文件与源码为准。

产品范围停在：WorkBuddy 每日签到 + 成长中心 + 积分展示与到期统计 + 凭据刷新 + 局域网同步。
米游社（HoYoLAB）与微软积分（Bing）**已整体移除**，不要再加回来（代码、设置项、字符串、
图标、文档都已删干净）。

不要做：云游戏、商城兑换、定时闹钟、导航徽标、Markdown 更新弹窗。

## 动手前

1. 需求没到约 95% 把握就先问；相关问题一次问完。
2. 逻辑改动（DS / cookie / retcode / WorkBuddy / 配置导入 / 头像补全 / 执行入队）必须让 `./gradlew :app:testDebugUnitTest` 保持全绿。
3. 不要加 instrumented Compose UI 测试，除非用户明确要求。JVM 单测钉的是结果映射，不是像素。
4. 不要为了「跑通」去改测试里的 act_id / 域名 / 路径；先改实现或先确认接口真变了，再同步测试。
5. 功能改完（含修崩溃、加磁贴这类用户会装到手机上的改动）**直接出包**，不必等用户再说「出包」。只改文档、只改 AGENTS.md 不出包。
6. **软件版本（`fewardsVersionName`）只在推送时加。** 本地开发出包只动构建位（`fewardsVersionCode` 末两位）。推送后本地必须与远程同一 commit、同一对 versionName/versionCode，不要在本地多 bump 一截没推上去的软件版本。

## 出包（每次用户向 APK）

给用户装或对照的包必须是 **release + R8**（`optimization.enable`）。Debug 首帧 composition hitch 是预期，不要用进场 delay 糊弄。只 `compile` 不算交付。签名目前用 debug signingConfig（见 `app/build.gradle.kts`），这是有意的。

`fewardsVersionCode` 由 `fewardsVersionName` 推导，不是独立计数器：`M` `MM` `PP` 是版本号各位，末两位 `BB` 是构建号。

| versionName | versionCode |
|---|---|
| 2.0.1 | 2000100（发布构建；本地构建位 +1 → 2000101） |
| 2.0.2 | 2000200 |
| 2.1.0 | 2010000 |
| 3.0.0 | 3000000 |
| 1.3.20 | 1032000 |

小数位在 versionCode 里是两位定长，补零；`BB` 在发布包里为 `00`，本地出包时 +1（可递增到 99，进位到 `PP`）。

### 本地开发出包

1. 只把 `fewardsVersionCode` 的构建位 +1（保留当前 versionName 对应的 `M` `MM` `PP`）。**不要改 `fewardsVersionName`。**
2. `./gradlew :app:assembleRelease`
3. 复制到 Downloads，文件名带软件版本与构建号，**不要互相覆盖**：

```bash
cp app/build/outputs/apk/release/app-release.apk ~/Downloads/Fewards-<versionName>-<versionCode>-release.apk
```

### 推送

1. 把 `fewardsVersionName` patch +1（例如 1.3.19 → 1.3.20），`fewardsVersionCode` 同步改成新版本对应的发布号（构建位 `00`，如 1.3.20 → 1032000）。
2. 提交（版本向：`v<versionName>: …`），再 `git push`。
3. 用**新的** versionName 再打一份 release APK 拷到 Downloads，保证用户装到的包、git 标签语义、远程仓库三者一致。
4. 推完后确认 `git status` 干净、本地 HEAD == `origin/<branch>`。

### 发 Release

推送后每个推送版本都要有 GitHub Release（v1.1.0 起的历史里程碑已补齐，后续只发新版本）：

1. 注释 tag 打在版本提交上：`git tag -a v<versionName> -m "v<versionName>: …"`，再 `git push origin v<versionName>`。
2. `gh release create v<versionName> --verify-tag --title "v<versionName>" --notes-file <notes>`，**附上该版本的 release APK**（`~/Downloads` 里那份，重命名成 `Fewards-<versionName>-release.apk`）。
3. 只有最新版本是 Latest；补发历史版本时加 `--latest=false`，且历史 Release 只写说明、不挂 APK（旧版本不重建）。

## 构建环境

- JDK 21。不要降到 17。
- Maven 走 Aliyun 镜像链（`settings.gradle.kts`）。不要改成直连 Maven Central。
- miuix 坐标必须带 `-android` 后缀（`miuix-ui-android` 等）。
- **导航库互斥**：用 `top.yukonga.miuix.kmp:miuix-nav-android`（`miuixNav`）。`miuix-navigation3-ui-android` 的包名覆盖 `androidx.navigation3.ui`，与 `androidx.navigation3:navigation3-ui` **二选一**；两个都留会 Duplicate class。本仓只要 `miuix-nav` + `androidx.navigation3:navigation3-runtime`。
- Room / DataStore 在 gradle 里有坐标，**源码未使用**。设置与账号走 SharedPreferences。不要顺手接 Room。
- 已删除 commonmark / webkit 依赖；「关于」页与配置导入导出整块也已删除，不要为它们加回来。

## 目录

```
app/src/main/java/com/functy/fewards/
  FewardsApplication.kt   # okhttp、今日状态水合、WorkManager Configuration.Provider
  core/workbuddy/         # 签到 engine、成长中心、积分、扫码协议、凭据刷新、JWT 标签
  core/lansync/           # 局域网同步：协议、HTTP 客户端
  data/repository/        # settings、accounts
  ui/screen/              # home, account, settings, colorpalette（全 Miuix）
  ui/viewmodel/           # TaskRunner = 执行逻辑；入队不在这里
  ui/navigation/          # Route.Main / ColorPalette
  work/                   # TaskWorker 入队、通知、NotificationActionReceiver、QS Tile
scripts/workbuddy-token.sh
.github/workflows/test.yml
app/src/test/java/com/functy/fewards/
  core/workbuddy/         # 签到判定、成长中心补登日历、积分解析、扫码协议、JWT 标签
  core/lansync/           # 同步协议字段与单位换算、客户端请求
  data/repository/        # 配置导入解析
```

`TaskRunner` 在 `ui/viewmodel` 是历史位置；**入队入口是 `work.TaskWorker.enqueue`**。不要再让 `TaskRunner` import `TaskWorker`（会环依赖）。Worker 可以调 `TaskRunner.execute`。

## 执行路径

首页「开始执行」与控制中心「签到」磁贴走同一条链：

1. `TaskNotifier.startRun`（立刻出通知）
2. `TaskWorker.enqueue(context, runWb)`（WorkManager 一次性任务）
3. `TaskWorker.doWork` → `TaskRunner.execute(runWb)`
4. 只有一个分支；`finally` 里 `running = false` 并 `RunTasksTileService.refresh`

现在只有 WorkBuddy 一个任务，`HomeUiState` 只保留 `wb*` 字段。**将来再加第二个任务时**，
`HomeUiState.summary()` 是可见性/完成判定的单一事实来源，把新任务加进那个判定即可；
其余位置（`HomeActions.onRun`、`TaskRunner.TaskUiState`、`TaskWorker.enqueue`）逐个补参数，
不要把旧的三任务并行结构照搬回来。

磁贴：执行中 `Tile.STATE_ACTIVE`，结束 `INACTIVE`。执行中再点不取消。无已配置任务则保持关。Manifest 里 `WorkManagerInitializer` 被 `tools:node="remove"` 掉了，所以 `FewardsApplication` **必须**实现 `Configuration.Provider`（`workManagerConfiguration`）。缺这个，一点「开始执行」就会 `WorkManager is not initialized properly` 崩。

没有 AlarmManager / BootReceiver / 精确闹钟权限。不要加回「每日定时」。

控制中心磁贴类名 `com.functy.fewards.work.RunTasksTileService`，R8 已 keep。用户侧：控制中心 → 编辑 → 添加「签到」。

## WorkBuddy

- `POST https://copilot.tencent.com/billing/meter/checkin-status` 与 `/daily-checkin`，Bearer token，body `{}`。
- 最终成败看 daily-checkin：HTTP 2xx + `code==0` 成功；HTTP 400 + `code==10001` **今日已签，视为成功**；401/403 token 过期。`data.today_checked_in` 不可靠，只作短路。
- 列表名从 JWT 解：`nickname`（trim 控制字符）优先，否则 `preferred_username`。实现：`WorkBuddyLabel.decodeProfile`。
- **没有头像接口。** JWT 若带 `picture` / `avatar_url` 等 http 地址就用；否则账号列表用昵称首字母色块。不要接腾讯用户资料 API。
- macOS 取 token：`scripts/workbuddy-token.sh`（读 CodeBuddyExtension 本地 auth）。不要把 token 写进仓库或日志。
- **扫码授权**（`WorkBuddyLogin`，移植自 workbuddy-manager `server/services/tencent.py`）：
  1. `POST /v2/plugin/auth/state?platform=CLI` → `data{state, authUrl}`；`authUrl` 即二维码内容。
  2. `GET /v2/plugin/auth/token?state=…` → **未扫码返回非 0 码（实测 11217 "login ing..."）**，别当错误；`code==0` 时 `data` 是**驼峰** `accessToken/refreshToken/expiresIn/domain`。
  3. `GET /v2/plugin/login/account?state=…`，带 `Authorization: Bearer {accessToken}` → `data{uid, nickname, enterpriseId}`。
  - 统一信封 `{code,msg,data}`：判定只看 `code`。**缺 `code` 字段必须算失败**（`hasOkCode`），不能因为「没有 code」就当成功。
  - 本地会话 TTL 5 分钟（`STATE_TTL_MS`），轮询 2 秒一次。轮询期间没有单独的「已扫码」信号。
  - 扫码成功后**只纳管，不自动签到**；签到仍由首页「开始执行」统一触发。
- **积分**（`WorkBuddyCredits`）：汇总接口 `get-user-resource-summary` 给权威数额，
  付费/免费明细只补到期时间（逐笔历史相加会和周期口径重复计算，不要那样算）。
  包的到期取「还有额度的那几笔里最早的」；`DeductionEndTime` 比 `CycleEndTime` 晚**整 30 天**时
  视为长期占位（判断用 `>=`，用 `>` 会多算一个月）；距现在超过 365 天视为长期有效。
  界面不显示 0：查询失败写「查询失败」，缺 uid 写「缺 uid」。
- **到期统计**（`CreditStats`，账号页顶部与每行）：只算未过期且 `remaining > 0` 的包；
  3 日 / 5 日是独立口径（5 日包含 3 日，所以 3 日的数不会超过 5 日）；缺 uid 的账号跳过，
  不进"查询中"的计数。
- **刷新与同步**：refresh_token 是轮换式的，刷新成功后旧值立刻作废。设置页拆成三个动作，别再合并：
  「刷新选中账号」只刷本机（会轮换，刷完要用户自己推）、「同步选中账号」只做 **推送 → 取回**
  （电脑端没有的账号由电脑端的合并规则 `create` 落库，所以手机推过去就等于在电脑端建号）、
  「从电脑同步凭据」按电脑端全量走：手机上**已失效**的账号补 token，手机上**没有**的账号直接建号
  （不必先扫码），手机上仍有效的账号一律不动，已有账号只写 token 字段（label / 头像不动）。
  "失效"的判定统一走 `CredentialHealth`（`expiresAt` 是秒，0 = 不知道，不能当已过期）。
- **两个过期时间不能混**：`expiresAt` 是 access 的（几小时），`refreshExpiresAt` 是 refresh 的
  （几十天）。access 过期只需换一份，refresh 过期才是真的没救。账号页「查看凭据详情」与设置页
  的凭据列表都必须**分开显示**，不许用一句"有效期至"顶替；0 要显示"未知"（写成 1970 会被当成已过期）。
  电脑端 `POST /api/lansync/fetch` 是**只读**端点，一行都不写账号库；旧版控制台没有它（404）时
  手机端退化成一次 `obtainedAt = 0` 的只读推送——这个 0 是让电脑端走"新鲜度未知 → 保留本地"，
  不要换成真实时间戳，否则可能用手机端的死凭据覆盖电脑端。
  电脑端刷新后会入队，a 主动拉取，不要改成 b 反向推给 a（手机 IP 会变、后台监听不可靠）。

- **档位（国内版 / 国际版）**：两档是**两套独立客户端**（`WorkBuddy` / `WorkBuddy AI`），
  两套域名、两套登录态文件、进程也不同，**绝不跨档请求**——国际版 token 打国内域只会 401，
  看起来像"凭据失效"。单一事实来源是 `core/workbuddy/WorkBuddyVariant.kt`（复刻电脑端
  `server/workbuddy/variant.mjs`）：
  - 账号库带 `variant`（`cn` / `ai`），历史账号缺失=国内版（零迁移）；`WorkBuddyAccount.wbVariant`
    是唯一取用入口，不要自己比字符串。
  - 积分走 `apiEndpoint`，刷新走 `chatBase`，扫码走 `chatBase` + `oauthPlatform`
    （国内版固定 `CLI`，国际版 `workbuddy-ai`）；UA 的平台名也随档位换。
  - 签到（`WorkBuddyEngine`）与成长中心（`WorkBuddyGrowth`）只有国内版有接口，
    国际版账号**显式跳过**（返回"跳过"而不是失败），首页与「一键领取」要分别说明跳过了几个。
  - 同步协议里 `Payload.variant` / `Remote.variant` 与电脑端同口径
    （`LanSyncProtocol.normalizeVariant`，只认 `cn` / `ai`）。「从电脑同步凭据」用电脑端的
    `variant` **建号**，并只做 `cn → ai` 一个方向的纠正（本版本之前国际版账号被存成国内版，
    之后查积分 / 刷新全打错域名）；反方向不纠，因为 `cn` 是历史默认值。
  - 账号 id 在国际版要带前缀（`wb_ai_<uid>`）：同一 uid 可能在两档各有一份，键撞了会互相覆盖。
  - 界面两档**分开成组**（账号页各自一张积分汇总卡 + 分区；设置页同步清单分区；添加弹窗先选档位）。

## 账号与配置

- 凭据只存在本机 SharedPreferences（`AccountRepository` / `SettingsRepositoryImpl`）。日志禁止打印 token / cookie / stoken。
- **账号库变更必须能被页面感知**：`AccountRepository` 的 companion 里有 `revision`（StateFlow），
  `saveWorkBuddy` 每次写盘都自增；账号页与设置页两个 ViewModel 都 `drop(1)` 后订阅它。
  原因是三页常驻同一个 pager，切页既不重建 ViewModel 也不触发 resume——只靠 init 时读一次，
  就会出现"设置页同步过来的账号在账号页看不见"。**新加的写库路径必须走到 `saveWorkBuddy`**。
- 账号页右上角有两个按钮：刷新（`account_refresh`，**强刷**：重读本地库 + 绕过 2 分钟缓存把所有账号的余额重查一遍）与「+」。
  按钮走 `refresh(force = true)`，切回账号页的自动刷新走 `refresh()`（命中缓存就不查，见 `AccountPager` 的 `LaunchedEffect(isCurrentPage)`）。
  别把按钮接回 `accountViewModel::refresh`：函数引用不吃默认参数，会退化成非强刷，点了数字不变。
- 强刷期间要看得见：刷新图标换成转圈、行内显示「刷新中…」、汇总卡写「正在刷新 N 个账号」。
  队列是**串行**的，只有当前账号会真正在查，靠状态位把"已受理"铺到所有待查账号上。
  `wbCredits` 是只增不减的键集合，删号必须同步删条目（`removeWb` / 队列里 `acc == null` 两处都要），
  否则残留的 `loading = true` 会让顶部转圈永远停不下来。
- 配置导入导出（`ConfigTransferParser` / `ConfigTransferRepository` / 设置页「导入 / 导出配置」卡片）**已整体移除**：
  迁移账号现在走「从电脑同步凭据」。不要再加回来，也不要恢复 `_format=fewards-config` 备份格式。
- 输入框用 miuix `basic.TextField`（`MultilineInputField` 包装）。不要用已删除的 `EditText.kt`。
- **WorkBuddy 多账号**：`WorkBuddyAccount` 带 `uid/enterpriseId/refreshToken/expiresAt`（老数据没有这些字段，读出来是空/0）。
  - `AccountRepository.addWorkBuddyAccount` 去重顺序：同 `uid` → 老账号（uid 空）同 `label` → 同 `id`；命中原地替换，不新增。**不要退回「只按 id 去重」**，否则同一账号重复扫码会多出一条。
  - `WorkBuddyLabel.decodeProfile` 的 `uid` 取 JWT `sub`（实测与 `/login/account` 的 uid 一致），`expiresAt` 取 `exp`，仅用于导入去重；权威 uid 以扫码接口为准。

## UI 约定

- **只有 Miuix。** 不要加回 MD3 / 双 UI。
- 设置子项跟父开关 **展开/收起**（Monet 模式），不要灰掉。
- 停留时间选择用 **OverlaySpinnerPreference**（列表 + 确定），不要一排 TextButton。
- 主题页：WindowSpinnerPreference；不要把 `Scaffold.popupHost` 置空。
- 主题颜色模式用轮廓 Tab（不是下拉）。
- 主页图标 28.dp；复选框行文字 `weight(1f)`，Checkbox 靠右。
- 图标/头像圆角：miuix `squircleClip(cornerRadius = size * 0.30f)`（`SquircleIcon` / 账号头像共用）。禁止自写 `G2SquircleShape`：`mid = √2−1` 会把 45° 点放到 0.414r，四角内缩约 2×。也不要用普通 `RoundedCornerShape` 充 squircle。
- 图标 png 在 `drawable-nodpi/`（只剩 `workbuddy`）。README 宣传图在 `docs/screenshots/`，每个页面**浅色深色各一张**：`<page>-light.png` / `<page>-dark.png`（page = home / account / settings / theme），README 里两行分别展示。换界面后两套都要同步更新。
- 账号头像网络图：`AccountMiuix.UrlImage`（OkHttp + G2 裁剪）。加载中 `InfiniteProgressIndicator`，失败回落到字母头像。
- **添加账号弹窗只有一份**：`ui/component/miuix/AddAccountDialog`。
  - 账号页只列**已登录账号**；顶部是积分汇总卡（`CreditSummaryCard`），下面是 WorkBuddy 分组。
    没有账号时显示 `account_empty` 提示，汇总卡也不出现。
  - 右上角「+」**直接打开** `AddAccountDialog`（只剩一种账号类型，不再需要二级菜单；
    以前的 `WindowListPopup` + `AddMenuRow` 已删，不要为"以后可能加"而恢复）。
  - 「+」是**带圈按钮**（`IconButton` + `backgroundColor = surfaceContainerHigh`；minWidth/minHeight 与默认 cornerRadius 都是 40dp，给底色即正圆）。它和大标题同一条中线：`TopAppBar` 把 actions 垂直居中在 52dp 的收起高度里，大标题却排在 52dp 之下，所以用 `Modifier.offset` 下移「半个收起高度 + 半个 title1 行高」，再按 `scrollBehavior.state.collapsedFraction` 收回原位。
  - 弹窗内用 `TabRowWithContour` 切「主登录方式 / 凭据登录」：默认主区是二维码，凭据区是输入框 + 导入。
    WorkBuddy 的凭据是 Token 粘贴。`primaryContent` 参数仍保留（主区可整体替换），当前没有调用方传它。
    说明文案与「重新获取」按钮由 `QrFooter` 渲染。
  - 开合由调用方的 `show` 状态驱动，只有 `QrState.Confirmed` 才自动关；过期/失败保持打开以露出「重新获取」。凭据导入成功（`onImportCredential` 返回 true）也自动关。
  - 弹窗的**挂载**由调用方的 `mounted` 标志控制（不是 `qrState`）：切到凭据 Tab 会立刻把 `qrState` 归零，只按 `qrState` 判断会让关闭动画演一半就消失。关闭走 `WindowDialog` 下滑淡出动画，`onDismissFinished` 里才置 `mounted=false` 并取消轮询。
  - 二维码申请以「弹窗开合 + Tab」为 key 的 `LaunchedEffect` 驱动，弹出动画结束（`delay(450)`）后才申请；切走再切回扫码 Tab 会重新申请一张新码，过期/失败后重开能自愈。
- 实时任务通知 ongoing，完成后再提升；自动消失跟 `overviewAutoDismiss` / `overviewHoldSeconds`。

## 导航与预测返回

- 路由只有 `Route.Main` / `About` / `ColorPalette`。主页三页是 pager，不是三条 Route。不要加回 `Route.Home/Account/Settings`。
- `NavDisplayEffects.dimAmount = 0.5`（库在页面之间的 scrim）。入场 route 第 0 帧必须是不透明 surface。
- 圆角裁剪：`if (roundAllCorners && navCornerRadius <= 0.dp) 32.dp else navCornerRadius`。不要无条件 32.dp（MIUIX/None 在 Leading 模式、系统没报半径时会闪黑）。
- 系统预测返回：manifest `android:enableOnBackInvokedCallback="true"` 静态声明。没有「启用预测返回」设置项，不要加回来。
- 返回**动画**是纯 Compose，读 `MainActivityUiState.predictiveBackAnimation`。要立即生效，`MainActivityViewModel.observedKeys` 必须包含 `predictive_back_animation`。选项：无 / MIUIX / AOSP / 缩放 / 经典。

## 不要加回去的东西

这些是评过、确认过、已经删掉的。加回等于制造死链或崩溃：

- `enablePredictiveBack` 设置链
- 导航徽标（`NavigationBadgeState` / `LocalEnableNavigationBadge` / 底栏 badge）
- 定时闹钟（`TaskScheduler`、`BootReceiver`、`SCHEDULE_EXACT_ALARM`、`USE_EXACT_ALARM`、`RECEIVE_BOOT_COMPLETED`）
- 可配置签到游戏/分区 prefs（改常量，不改设置项）
- `Dialog.kt` / `DialogMiuix.kt` / `MarkdownContent.kt` / `GithubMarkdown.kt` / Monet CSS WebView
- `EditText.kt`、`WarningCard.kt`、`ScrollToTop.kt`、`ThemeExt.kt`、`ui/util/Colors.kt`、`MonetColorsProvider`
- 未使用的 strings（预测返回开关、定时、导航徽标）
- 米游社 / 微软积分（`core/mihoyo`、`core/bing`、`BingAuthWebView`、对应设置项与字符串、`docs/bing-rewards.md`）
- 首页「支持项目」「了解更多」两张卡片（`SupportLinks`，`home_support_*` / `home_learn_more*` 字符串）
- 设置页「关于」（`Route.About`、`ui/screen/about/`、`about` / `about_source_code` 字符串）
- 设置页「导入 / 导出配置」（`ConfigTransferViewModel` / `ConfigTransferRepository` / `ConfigTransferParser` 与其测试）

## 测试

`./gradlew :app:testDebugUnitTest`，CI：`.github/workflows/test.yml`。

改了下面这些必须有/更新 JVM 测试：WorkBuddy `interpretDailyCheckin`、扫码协议（`WorkBuddyLoginTest`：信封 code 优先、11217 视为等待、驼峰字段、缺 uid 未就绪、缺 code 不算成功）、JWT 的 uid/exp（`WorkBuddyLabelTest`）、成长中心补登日历（`WorkBuddyGrowthTest`）、积分解析与到期口径（`WorkBuddyCreditsTest`）、积分统计（`CreditStatsTest`）、同步协议与客户端（`LanSyncProtocolTest` / `LanSyncClientTest`）、凭据有效性判定（`CredentialHealthTest`）、凭据展示格式（`WbCredentialFormatTest`）。

`CreditStatsTest` 钉的是账页顶部汇总的口径，改动前先看注释：只算未过期额度、已用完的包不算"即将到期"、
3 日与 5 日是独立口径（5 日包含 3 日）、缺 uid 的账号跳过。`HomeSummaryTest` 钉首页可见性与完成判定。

## 提交

版本向提交跟现有风格：`v<versionName>: 一句话说明用户能感知的变化`。测试向：`test: …`。文档向：`docs: …`。不要把 `scripts/stutter_ab.py`（未跟踪的对照脚本）塞进提交。不要改 git config。不要在没要求时 push。
