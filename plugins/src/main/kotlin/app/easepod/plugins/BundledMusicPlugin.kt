package app.easepod.plugins

internal fun BundledMusicPlugin.registration(packageName: String, signature: String, previous: PluginRecord?): PluginRecord {
    val existing = previous?.takeIf { it.packageName == packageName && it.serviceName == serviceClass }
    return PluginRecord(id, packageName, serviceClass, signature, enabled = existing?.enabled ?: true,
        sourceId = existing?.sourceId?.takeIf(String::isNotBlank) ?: "builtin.$id", quarantined = existing?.quarantined ?: false)
}
