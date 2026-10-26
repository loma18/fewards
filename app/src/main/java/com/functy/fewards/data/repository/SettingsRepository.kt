package com.functy.fewards.data.repository

interface SettingsRepository {
    // 界面
    var themeMode: Int
    var miuixMonet: Boolean
    var keyColor: Int
    var colorStyle: String
    var colorSpec: String
    var enableBlur: Boolean
    var enableFloatingBottomBar: Boolean
    var enableFloatingBottomBarBlur: Boolean
    var navigationRailExpanded: Boolean
    var pageScale: Float
    var predictiveBackAnimation: Int // 0 None 1 MIUIX 2 AOSP 3 Scale 4 Classic

    // 通知与完成总览
    var taskNotification: Boolean
    var overviewAutoDismiss: Boolean
    var overviewHoldSeconds: Float

    // WorkBuddy
    var wbMasterEnabled: Boolean
    /** 每日签到后自动跑成长中心（旅行 / 任务 / 补登 / 兑换 / 盲盒）；默认开启 */
    var wbGrowthEnabled: Boolean
    /** 凭据临期自动刷新；默认开启 */
    var wbAutoRefresh: Boolean
    /** 电脑端同步地址，形如 `192.168.1.5:57900`；空 = 未配置 */
    var wbSyncEndpoint: String
    /** 与电脑端一致的配对码 */
    var wbSyncPairCode: String
    /** true = 以手机为准覆盖电脑端（默认 false，走"谁新信谁"） */
    var wbSyncForcePush: Boolean
    /** 上次成功同步的时间（毫秒），仅用于界面展示 */
    var wbSyncLastAt: Long
}
