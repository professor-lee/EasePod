package app.easepod.netease

import app.easepod.contract.HomeCatalog

/** Stable identity constants used by the host UI and tests. The implementation lives in the optional APK. */
object NeteasePlugin {
    const val id = "app.easepod.netease"
    const val name = "网易云音乐"
    const val serviceClass = "app.easepod.netease.NeteaseMusicService"
    val capabilities = setOf("catalog.browse", "catalog.search", "catalog.details", HomeCatalog.CAPABILITY,
        "playback.resolve", "lyrics.read", "account.list", "account.qr", "account.signOut", "library.favorite",
        "playback.quality.standard", "playback.quality.high", "playback.quality.lossless", "playback.quality.hires")
    val networkDomains: Set<String> = buildSet {
        add("music.163.com")
        (1..4).forEach { add("p$it.music.126.net") }
        (1..12).forEach { add("m$it.music.126.net") }
        listOf(701, 702, 801, 802).forEach { add("m$it.music.126.net") }
    }
}
