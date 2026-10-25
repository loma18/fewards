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

    // 米游社
    var mhyMasterEnabled: Boolean
    var mhyGameSign: Boolean
    var mhyBbsSign: Boolean
    var mhyRead: Boolean
    var mhyLike: Boolean
    var mhyCancelLike: Boolean
    var mhyShare: Boolean
    var mhyCaptchaPolicy: Int // 0 = 跳过并记录；1 = 打码接口
    var mhyCaptchaApiUrl: String

    // WorkBuddy
    var wbMasterEnabled: Boolean
    /** 电脑端同步地址，形如 `192.168.1.5:57900`；空 = 未配置 */
    var wbSyncEndpoint: String
    /** 与电脑端一致的配对码 */
    var wbSyncPairCode: String
    /** true = 以手机为准覆盖电脑端（默认 false，走"谁新信谁"） */
    var wbSyncForcePush: Boolean
    /** 上次成功同步的时间（毫秒），仅用于界面展示 */
    var wbSyncLastAt: Long

    // Bing
    var bingMasterEnabled: Boolean
    /** 是否同时领取 Bing App 每日连签（dapi type 103，15 分/天）。 */
    var bingAppCheckIn: Boolean
}
