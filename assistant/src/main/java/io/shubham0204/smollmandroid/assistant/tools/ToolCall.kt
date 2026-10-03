package io.shubham0204.smollmandroid.assistant.tools

/**
 * The closed set of actions the app will perform on a model's behalf. Every call is shown to the
 * user before it runs; nothing outside this hierarchy is executable.
 */
sealed class ToolCall {
    abstract val name: String

    /** True when the call neither leaves the device nor changes device state. */
    abstract val readOnlyLocal: Boolean

    /** One-line statement of exactly what running the call does, for the confirmation dialog. */
    abstract fun describe(): String

    /** [document] null means the user picks one of the granted documents in the dialog. */
    data class ReadFile(val document: String?) : ToolCall() {
        override val name = "read_file"
        override val readOnlyLocal = true

        override fun describe() =
            "Read the text of \"${document ?: "(choose a document)"}\" (read-only, first " +
                "${DocumentStore.MAX_READ_BYTES / 1024} KiB) and pass it to the model as data."
    }

    data class WebSearch(val query: String) : ToolCall() {
        override val name = "web_search"
        override val readOnlyLocal = false

        override fun describe() =
            "Send the search query \"$query\" over the network, through the separate web-search " +
                "app, to its configured endpoint, and pass the result text to the model as data."
    }

    data class OpenSettings(val page: SettingsPage) : ToolCall() {
        override val name = "open_settings"
        override val readOnlyLocal = false

        override fun describe() = "Open the system settings page \"${page.key}\" (${page.action})."
    }
}
