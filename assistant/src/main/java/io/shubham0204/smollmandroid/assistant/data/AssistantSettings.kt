package io.shubham0204.smollmandroid.assistant.data

import android.content.Context

class AssistantSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Run read_file without a confirmation tap. Network and device-changing tools always ask. */
    var autoApproveReadOnly: Boolean
        get() = prefs.getBoolean("auto_approve_read_only", false)
        set(v) = prefs.edit().putBoolean("auto_approve_read_only", v).apply()

    var threads: Int
        get() = prefs.getInt("threads", minOf(4, Runtime.getRuntime().availableProcessors()))
        set(v) = prefs.edit().putInt("threads", v.coerceIn(1, 8)).apply()

    /** 0 selects by model size: 2048 below 1B parameters, 1024 above (KV cache on a 2 GB phone). */
    var contextSize: Int
        get() = prefs.getInt("n_ctx", 0)
        set(v) = prefs.edit().putInt("n_ctx", v).apply()

    var lastModel: String?
        get() = prefs.getString("last_model", null)
        set(v) = prefs.edit().putString("last_model", v).apply()
}
