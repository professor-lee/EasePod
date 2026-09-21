package app.easepod.sampleplugin

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.easepod.contract.PackageIdentity
import app.easepod.contract.TrustedHosts

class HostApprovalActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hostPackage = "app.easepod"
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }
        fun label(value: String, size: Float = 16f) { layout.addView(TextView(this).apply { text = value; textSize = size; setPadding(0, 0, 0, 24) }) }
        label("SoundHelix Sample", 24f)
        label("Host access")
        val fingerprints = runCatching { PackageIdentity.fingerprints(this, hostPackage) }.getOrNull()
        if (fingerprints == null) {
            label("EasePod is not installed.")
        } else {
            label(hostPackage)
            label("SHA-256\n${fingerprints.joinToString("\n")}", 14f)
            layout.addView(Button(this).apply {
                text = "Approve this EasePod signature"
                setOnClickListener {
                    TrustedHosts(this@HostApprovalActivity).approve(hostPackage, fingerprints)
                    setResult(RESULT_OK)
                    finish()
                }
            })
            layout.addView(Button(this).apply {
                text = "Revoke host access"
                setOnClickListener { TrustedHosts(this@HostApprovalActivity).revoke(hostPackage); finish() }
            })
        }
        layout.addView(Button(this).apply { text = "Cancel"; setOnClickListener { finish() } })
        setContentView(layout)
    }
}
