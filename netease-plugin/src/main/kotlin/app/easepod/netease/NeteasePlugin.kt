package app.easepod.netease

import app.easepod.contract.HomeCatalog

object NeteasePlugin {
    const val id = "app.easepod.netease"
    const val name = "网易云音乐"
    const val serviceClass = "app.easepod.netease.NeteaseMusicService"
    val capabilities = setOf("catalog.browse", "catalog.search", "catalog.details", HomeCatalog.CAPABILITY, "playback.resolve",
        "lyrics.read", "account.list", "account.qr", "account.signOut", "library.favorite",
        "playback.quality.standard", "playback.quality.high", "playback.quality.lossless", "playback.quality.hires")
    val networkDomains: Set<String> get() = NeteaseMediaDomains.hosts
}
