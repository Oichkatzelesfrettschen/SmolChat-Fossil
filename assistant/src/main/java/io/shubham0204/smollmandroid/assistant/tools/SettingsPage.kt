package io.shubham0204.smollmandroid.assistant.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Hard-coded allowlist of settings screens. The keys match the `page` enum in both GBNF grammars
 * and in tools_full.json; each action exists at API 25.
 */
enum class SettingsPage(val key: String, val action: String) {
    WIFI("wifi", Settings.ACTION_WIFI_SETTINGS),
    BLUETOOTH("bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS),
    DISPLAY("display", Settings.ACTION_DISPLAY_SETTINGS),
    SOUND("sound", Settings.ACTION_SOUND_SETTINGS),
    BATTERY("battery", Settings.ACTION_BATTERY_SAVER_SETTINGS),
    LOCATION("location", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
    AIRPLANE("airplane", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
    DATE("date", Settings.ACTION_DATE_SETTINGS),
    STORAGE("storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
    APP_INFO("app_info", Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
    ;

    fun open(context: Context): String {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (this == APP_INFO) intent.data = Uri.fromParts("package", context.packageName, null)
        return try {
            context.startActivity(intent)
            "opened settings page $key"
        } catch (e: ActivityNotFoundException) {
            "settings page $key is not available on this device"
        }
    }

    companion object {
        fun fromKey(key: String): SettingsPage? = entries.firstOrNull { it.key == key }
    }
}
