package io.github.proify.lyricon.amprovider.xposed

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.common.RootConstants
import java.lang.ref.WeakReference

/** Small in-process status pill; no SYSTEM_ALERT_WINDOW permission or Apple layout IDs needed. */
internal object AppleAiProgressOverlay {
    private data class State(val stage: String, val batch: Int, val total: Int, val fragments: Int)

    private val main = Handler(Looper.getMainLooper())
    private var installed = false
    private var preferences: SharedPreferences? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var foreground = WeakReference<Activity>(null)
    private var label: TextView? = null
    private var state: State? = null
    private val expiry = Runnable {
        state = null
        removeLabel()
    }

    fun install(application: Application, prefs: SharedPreferences?) {
        if (installed) return
        installed = true
        preferences = prefs
        if (prefs != null) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == RootConstants.KEY_HOOK_AI_TRANS_SHOW_PROGRESS) {
                    main.post {
                        if (!enabled()) {
                            state = null
                            removeLabel()
                        }
                    }
                }
            }
            preferenceListener = listener // RemotePreferences only weakly retains listeners.
            runCatching { prefs.registerOnSharedPreferenceChangeListener(listener) }
                .onFailure { ProviderLogger.info("AI progress preference listener unavailable") }
        }
        runCatching {
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) {
                    foreground = WeakReference(activity)
                    runCatching { render() }
                }
                override fun onActivityPaused(activity: Activity) {
                    if (foreground.get() === activity) {
                        foreground.clear()
                        runCatching { removeLabel() }
                    }
                }
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) {
                    if (foreground.get() === activity) {
                        foreground.clear()
                        runCatching { removeLabel() }
                    }
                }
            })
        }.onFailure { ProviderLogger.info("AI progress overlay unavailable") }
    }

    fun update(stage: String, batch: Int = 0, total: Int = 0, fragments: Int = 0) {
        main.post {
            main.removeCallbacks(expiry)
            if (!enabled() || stage == "cancelled") {
                state = null
                removeLabel()
                return@post
            }
            state = State(stage, batch, total, fragments)
            runCatching { render() }
            // Fail-safe TTL for lost Binder callbacks; terminal feedback is intentionally brief.
            main.postDelayed(expiry, if (AppleAiProgressPresentationPolicy.terminal(stage)) 4_000L else 240_000L)
        }
    }

    private fun enabled(): Boolean = runCatching {
        preferences?.getBoolean(
            RootConstants.KEY_HOOK_AI_TRANS_SHOW_PROGRESS,
            RootConstants.DEFAULT_HOOK_AI_TRANS_SHOW_PROGRESS,
        ) ?: false
    }.getOrDefault(false)

    private fun render() {
        if (!enabled()) {
            removeLabel()
            return
        }
        val current = state ?: return
        val message = AppleAiProgressPresentationPolicy.describe(
            current.stage, current.batch, current.total, current.fragments,
        ) ?: return
        val activity = foreground.get() ?: return
        val decor = activity.window?.decorView as? FrameLayout ?: return
        if (label?.parent !== decor) {
            removeLabel()
            val density = activity.resources.displayMetrics.density
            fun dp(value: Float): Int = (value * density + 0.5f).toInt()
            val view = TextView(activity).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
                background = GradientDrawable().apply {
                    setColor(Color.argb(226, 30, 30, 34))
                    cornerRadius = dp(24f).toFloat()
                }
                elevation = dp(8f).toFloat()
                isClickable = false
                isFocusable = false
            }
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(96f) }
            decor.addView(view, params)
            label = view
        }
        label?.text = message
    }

    private fun removeLabel() {
        label?.let { view -> runCatching { (view.parent as? ViewGroup)?.removeView(view) } }
        label = null
    }
}
