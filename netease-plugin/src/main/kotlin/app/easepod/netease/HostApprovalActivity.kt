package app.easepod.netease

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.easepod.contract.PackageIdentity
import app.easepod.contract.TrustedHosts

/** Explicit host approval screen required by the external plugin trust contract. */
class HostApprovalActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hostPackage = "app.easepod"
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }
        fun label(value: String, size: Float = 16f) {
            layout.addView(TextView(this).apply {
                text = value
                textSize = size
                setPadding(0, 0, 0, 24)
            })
        }
        label("网易云音乐", 24f)
        label("EasePod 主机访问授权")
        val fingerprints = runCatching { PackageIdentity.fingerprints(this, hostPackage) }.getOrNull()
        if (fingerprints == null) {
            label("未检测到 EasePod，请先安装主应用。")
        } else {
            label(hostPackage)
            label("SHA-256\n${fingerprints.joinToString("\n")}", 14f)
            layout.addView(Button(this).apply {
                text = "批准此 EasePod 签名"
                setOnClickListener {
                    TrustedHosts(this@HostApprovalActivity).approve(hostPackage, fingerprints)
                    setResult(RESULT_OK)
                    finish()
                }
            })
            layout.addView(Button(this).apply {
                text = "撤销主机访问"
                setOnClickListener { TrustedHosts(this@HostApprovalActivity).revoke(hostPackage); finish() }
            })
        }
        layout.addView(Button(this).apply { text = "取消"; setOnClickListener { finish() } })
        setContentView(layout)
    }
}
