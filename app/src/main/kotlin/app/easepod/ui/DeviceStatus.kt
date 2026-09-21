package app.easepod.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaRouter
import android.os.BatteryManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class BatteryStatus(val percent: Int? = null, val charging: Boolean = false)

internal class DeviceStatus(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val router = this.context.getSystemService(MediaRouter::class.java)
    var battery by mutableStateOf(BatteryStatus()); private set
    var audioOutput by mutableStateOf(""); private set
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { updateBattery(intent) }
    }
    private val routeCallback = object : MediaRouter.SimpleCallback() {
        override fun onRouteSelected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) { updateOutput() }
        override fun onRouteUnselected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) { updateOutput() }
        override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) { updateOutput() }
    }

    init {
        updateBattery(this.context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED))
        router.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, routeCallback, MediaRouter.CALLBACK_FLAG_UNFILTERED_EVENTS)
        updateOutput()
    }

    private fun updateBattery(intent: Intent?) {
        if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        battery = BatteryStatus(
            if (level >= 0 && scale > 0) (level * 100L / scale).toInt().coerceIn(0, 100) else null,
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    private fun updateOutput() {
        audioOutput = router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)?.getName(context)?.toString().orEmpty()
    }

    override fun close() {
        context.unregisterReceiver(batteryReceiver)
        router.removeCallback(routeCallback)
    }
}
