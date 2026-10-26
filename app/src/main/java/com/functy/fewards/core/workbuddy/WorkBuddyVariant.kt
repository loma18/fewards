package com.functy.fewards.core.workbuddy

import com.functy.fewards.data.repository.AccountRepository

/**
 * WorkBuddy 的两个档位（国内版 / 国际版）。
 *
 * ## 为什么必须单独一层
 *
 * 两档是**两个独立安装的客户端**（`WorkBuddy` / `WorkBuddy AI`），域名、登录态文件、
 * 进程都不相同，而且**绝不能跨档位请求**：国内版 token 打国际域会被网关的一致性校验拒绝，
 * 国际版 token 打国内域同理（拿到的是 401 / 未知客户端，而不是"凭据失效"）。
 *
 * 所以凡是「发请求 / 建账号 / 分组展示」的地方，都必须先确定账号属于哪一档：
 * - 积分：`WorkBuddyCredits` 按 `apiEndpoint` 选域名；
 * - 刷新：`WorkBuddyTokenRefresh` 按 `chatBase` 选刷新域，`Origin` / `Referer` / UA 也跟着换；
 * - 扫码：`WorkBuddyLogin` 按 `chatBase` + `oauthPlatform` 申请 state；
 * - 签到 / 成长中心：只有国内版有接口（[supportsCheckin] / [supportsGrowth]），国际版显式跳过。
 *
 * ## 与电脑端一致
 *
 * 字段口径照搬 `workbuddy-console/server/workbuddy/variant.mjs`，`id` 只有 `cn` / `ai`，
 * 手机同步协议里传的也是这两个值。历史账号没有 `variant` 字段，一律按国内版解释（零迁移）。
 */
enum class WorkBuddyVariant(
    /** 协议与本地存储里的档位标识 */
    val id: String,
    /** 展示用中文名 */
    val label: String,
    /** 官方客户端显示名（提示文案里说"重新打开 xxx"就用它） */
    val appLabel: String,
    /** 官网域：积分 / 成长中心接口 + `Origin` / `Referer` */
    val apiEndpoint: String,
    /** 刷新与扫码接口所在的域（国内版是 copilot，国际版与官网同域） */
    val chatBase: String,
    /** 申请扫码 state 时的 platform 参数 */
    val oauthPlatform: String,
    /** 出站 UA 里的平台名（官方按平台名做指纹校验） */
    val userAgentPlatform: String,
    /** 是否有签到接口（目前只有国内版） */
    val supportsCheckin: Boolean,
    /** 是否有成长中心（目前只有国内版） */
    val supportsGrowth: Boolean,
) {
    CN(
        id = "cn",
        label = "国内版",
        appLabel = "WorkBuddy",
        apiEndpoint = "https://www.codebuddy.cn",
        // 国内版刷新 / 扫码走 copilot 域；官网域不放行 /v2/plugin
        chatBase = "https://copilot.tencent.com",
        // 扫码 platform 固定 CLI：官方服务端只认这个值（见 WorkBuddyLogin 的契约测试）
        oauthPlatform = "CLI",
        userAgentPlatform = "WorkBuddy",
        supportsCheckin = true,
        supportsGrowth = true,
    ),
    AI(
        id = "ai",
        label = "国际版",
        appLabel = "WorkBuddy AI",
        apiEndpoint = "https://www.workbuddy.ai",
        // 国际版刷新 / 扫码与官网同域
        chatBase = "https://www.workbuddy.ai",
        oauthPlatform = "workbuddy-ai",
        userAgentPlatform = "WorkBuddy AI",
        // 国际版没有签到 / 成长中心接口，硬打只会拿到 404 或跨档拒绝
        supportsCheckin = false,
        supportsGrowth = false,
    );

    companion object {
        /**
         * 归一化档位标识。
         *
         * 兼容电脑端可能出现的写法（`ai` / `workbuddy-ai` / `intl`）；**无法识别一律按国内版**，
         * 与历史数据"没有 variant 字段"的解释保持一致，避免老账号被误判成国际版后打到错域名。
         */
        fun of(raw: String?): WorkBuddyVariant = when (raw?.trim()?.lowercase()) {
            "ai", "workbuddy-ai", "intl" -> AI
            else -> CN
        }

        /** 账号所属档位；历史账号（字段为空）按国内版 */
        fun ofAccount(account: AccountRepository.WorkBuddyAccount?): WorkBuddyVariant = of(account?.variant)

        /** 固定展示顺序：国内版在前 */
        fun ordered(): List<WorkBuddyVariant> = listOf(CN, AI)

        /** 把账号列表按档位分组（顺序同 [ordered]，空档位不出现） */
        fun group(accounts: List<AccountRepository.WorkBuddyAccount>): List<Pair<WorkBuddyVariant, List<AccountRepository.WorkBuddyAccount>>> {
            val byVariant = accounts.groupBy { ofAccount(it) }
            return ordered().mapNotNull { v ->
                val list = byVariant[v]
                if (list.isNullOrEmpty()) null else v to list
            }
        }
    }
}
