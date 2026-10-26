package com.functy.fewards.core.lansync

/**
 * 凭据有效性判定（纯函数，不联网、不读盘）。
 *
 * ## 为什么单独一层
 *
 * 「这个账号还能不能用」在两处都要判断，且口径必须一致：
 *
 * 1. **手机端要不要去电脑端要凭据**：只有失效的账号才需要，有效的账号不该被覆盖；
 * 2. **电脑端返回的凭据能不能用**：电脑端也可能早就过期了，写回去只会把"已失效"
 *    粉饰成"看起来有效"。
 *
 * ## 时间单位
 *
 * `expiresAt` 是**秒**（扫码时 `currentTimeMillis()/1000 + expiresIn`），
 * `nowMs` 是**毫秒**。差 1000 倍写错不会崩，只会让每个账号都被判成过期。
 *
 * ## "0" 的语义
 *
 * `expiresAt <= 0` 表示**不知道**（老数据 / 手动粘贴的裸 token），不是已过期。
 * 把它当过期的后果是把用户还能用的账号判死，这比漏判严重得多。
 */
object CredentialHealth {

    /**
     * 凭据是否已失效。
     *
     * 判死的条件只有两个：`token` 是空的，或者过期时间明确早于现在。
     * 只要拿不到过期时间就一律当作"还能用"。
     */
    fun isDead(accessToken: String, expiresAtSec: Long, nowMs: Long): Boolean {
        if (accessToken.isBlank()) return true
        if (expiresAtSec <= 0L) return false
        return expiresAtSec * 1000L <= nowMs
    }

    /**
     * 是否可以直接使用（[isDead] 的反面）。
     *
     * 单独开一个名字，是为了让调用处读起来是"这个远端凭据能拿来写回吗"，
     * 而不是"`!isDead` 是什么意思"。
     */
    fun isUsable(accessToken: String, expiresAtSec: Long, nowMs: Long): Boolean =
        !isDead(accessToken, expiresAtSec, nowMs)

    /**
     * refresh_token 是否还有续期能力。
     *
     * 没法和 access 一样只看时间：`refreshExpiresAt` 只有电脑端回传才有，
     * 手机端自己扫码拿到的记录里通常是 0。所以这里的口径是
     * **"有值且未过期"才算可用**，0（未知）返回 false，由调用方决定是否降级判断。
     */
    fun isRefreshExpired(refreshToken: String, refreshExpiresAtSec: Long, nowMs: Long): Boolean {
        if (refreshToken.isBlank()) return true
        if (refreshExpiresAtSec <= 0L) return false
        return refreshExpiresAtSec * 1000L <= nowMs
    }
}
