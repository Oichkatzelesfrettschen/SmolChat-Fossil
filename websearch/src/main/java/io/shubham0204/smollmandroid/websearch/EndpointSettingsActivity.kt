package io.shubham0204.smollmandroid.websearch

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Chooses the one endpoint the service may contact. Plain framework views keep the APK small. */
class EndpointSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val current = TextView(this)
        val searx = EditText(this).apply {
            hint = "https://searx.example.org"
            setSingleLine()
        }
        fun refresh() {
            current.text = "Endpoint: " + SearchEndpoint.load(this).display
        }
        val useDdg = Button(this).apply {
            text = "Use DuckDuckGo HTML"
            setOnClickListener {
                SearchEndpoint.saveDuckDuckGo(this@EndpointSettingsActivity)
                refresh()
            }
        }
        val useSearx = Button(this).apply {
            text = "Use SearXNG (JSON)"
            setOnClickListener {
                try {
                    SearchEndpoint.saveSearx(this@EndpointSettingsActivity, searx.text.toString().trim())
                    refresh()
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(this@EndpointSettingsActivity, e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
        val info = TextView(this).apply {
            text = "This package is the only part of the assistant with network access. " +
                "It sends the query shown in the assistant's confirmation dialog to this endpoint " +
                "over HTTPS, follows no redirects, and returns at most " +
                "${FetchLimits.MAX_RESULTS} results / ${FetchLimits.MAX_OUTPUT_CHARS} characters."
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                listOf(current, useDdg, searx, useSearx, info).forEach {
                    addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            },
        )
        refresh()
    }
}
