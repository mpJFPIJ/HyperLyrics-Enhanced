package com.juren233.hyperlyricsenhanced.utils.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.bridge.AppleDirectIdentityPolicy
import com.juren233.hyperlyricsenhanced.common.bridge.appleDirectSenderIdentity
import com.juren233.hyperlyricsenhanced.utils.LogManager

/** Rootless NPatch cannot read /data/adb/lspd/log. Accept only a bounded,
 * pre-sanitized UI geometry trace sent by the authenticated Apple Music UID,
 * and write it into the normal app log used by the existing private uploader.
 * Android < 14 has no reliable broadcast-sender UID and is intentionally off. */
class AppleUiTraceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < 34 ||
            intent.action != AppleUiTraceTransportPolicy.ACTION
        ) return
        val sender = appleDirectSenderIdentity(
            context, AppleUiTraceTransportPolicy.APPLE_PACKAGE,
        )
        if (sender.decision != AppleDirectIdentityPolicy.Decision.AUTHENTICATED) return
        val safe = AppleUiTraceTransportPolicy.accept(
            intent.getStringExtra(AppleUiTraceTransportPolicy.EXTRA_SAFE_TRACE),
        ) ?: return
        LogManager.i("AppleUiTrace", "[HLE_UX_TRACE] $safe")
    }
}
