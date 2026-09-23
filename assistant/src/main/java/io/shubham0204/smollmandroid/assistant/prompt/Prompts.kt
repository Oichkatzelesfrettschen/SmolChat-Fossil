package io.shubham0204.smollmandroid.assistant.prompt

import android.content.res.AssetManager
import io.shubham0204.smollmandroid.assistant.tools.SettingsPage
import io.shubham0204.smollmandroid.assistant.tools.ToolCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * ChatML prompts and GBNF grammars for SmolLM2 (tokenizer.chat_template in the GGUF:
 * `<|im_start|>role\ncontent<|im_end|>\n`). Templates live in assets/prompts and
 * assets/grammars.
 *
 * Two planner tiers:
 * - [Tier.FULL] (SmolLM2-1.7B-Instruct): the model-card function-calling system prompt and a
 *   grammar-constrained `<tool_call>[...]</tool_call>` list.
 * - [Tier.INTENT] (135M/360M): few-shot intent classification over a closed enum.
 *
 * The planner sees only the current user message. Tool output reaches only the answer pass, which
 * runs without tools, so text inside a web page or document cannot produce a tool call.
 */
class Prompts(assets: AssetManager) {
    enum class Tier { FULL, INTENT }

    private fun AssetManager.text(path: String) = open(path).bufferedReader().use { it.readText() }

    private val plannerFull = assets.text("prompts/planner_full.txt").trimEnd('\n')
    private val toolsFull = assets.text("prompts/tools_full.json").trim()
    private val plannerIntent = assets.text("prompts/planner_intent.txt").trimEnd('\n')
    private val intentExamples = assets.text("prompts/planner_intent_examples.txt").trim().lines()
    private val answerSystem = assets.text("prompts/answer_system.txt").trimEnd('\n')
    private val grammarFull = assets.text("grammars/tool_call_full.gbnf")
    val grammarIntent: String = assets.text("grammars/intent_small.gbnf")

    fun plannerPrompt(tier: Tier, userText: String, documents: List<String>): String =
        when (tier) {
            Tier.FULL -> {
                val docList = if (documents.isEmpty()) "none" else documents.joinToString(", ")
                val pages = SettingsPage.entries.joinToString(", ") { "\"${it.key}\"" }
                val tools = toolsFull.replace("%DOC_LIST%", docList).replace("%PAGE_LIST%", pages)
                chatml(plannerFull.replace("%TOOLS%", tools), listOf("user" to sanitize(userText)))
            }
            Tier.INTENT -> {
                val turns = intentExamples.chunked(2).map { (q, a) -> listOf("user" to q, "assistant" to a) }.flatten()
                chatml(plannerIntent, turns + ("user" to sanitize(userText)))
            }
        }

    fun plannerGrammar(tier: Tier, documents: List<String>): String =
        when (tier) {
            Tier.FULL -> {
                val calls = if (documents.isEmpty()) "search | settings" else "search | read | settings"
                val docs = if (documents.isEmpty()) "\"\\\"none\\\"\"" else documents.joinToString(" | ") { "\"\\\"$it\\\"\"" }
                grammarFull.replace("%CALLS%", calls).replace("%DOCS%", docs)
            }
            Tier.INTENT -> grammarIntent
        }

    /** Parses planner output. Unknown tools or arguments are dropped, never executed. */
    fun parsePlan(tier: Tier, output: String, userText: String, documents: List<String>): List<ToolCall> =
        when (tier) {
            Tier.FULL -> parseToolCalls(output, documents)
            Tier.INTENT -> parseIntent(output, userText, documents)
        }

    private fun parseToolCalls(output: String, documents: List<String>): List<ToolCall> {
        val body = Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL).find(output)?.groupValues?.get(1)
            ?: return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val call = arr.optJSONObject(i) ?: return@mapNotNull null
            val args = call.optJSONObject("arguments") ?: JSONObject()
            when (call.optString("name")) {
                "web_search" -> args.optString("query").trim().takeIf { it.isNotEmpty() }?.let { ToolCall.WebSearch(it.take(160)) }
                "read_file" -> args.optString("document").takeIf { it in documents }?.let { ToolCall.ReadFile(it) }
                "open_settings" -> SettingsPage.fromKey(args.optString("page"))?.let { ToolCall.OpenSettings(it) }
                else -> null
            }
        }.distinct()
    }

    private fun parseIntent(output: String, userText: String, documents: List<String>): List<ToolCall> {
        val o = runCatching { JSONObject(output.trim()) }.getOrNull() ?: return emptyList()
        return when (o.optString("intent")) {
            "web_search" -> listOf(ToolCall.WebSearch(userText.trim().take(160)))
            "read_file" -> listOf(ToolCall.ReadFile(documents.singleOrNull()))
            "open_settings" -> listOfNotNull(SettingsPage.fromKey(o.optString("page"))?.let { ToolCall.OpenSettings(it) })
            else -> emptyList()
        }
    }

    /**
     * Answer pass: no tools and no grammar. [toolResults] are (label, untrusted text) pairs placed
     * inside <data> blocks after the question.
     */
    fun answerPrompt(history: List<Pair<String, String>>, userText: String, toolResults: List<Pair<String, String>>): String {
        val user = buildString {
            append(sanitize(userText))
            toolResults.forEach { (label, text) ->
                append("\n\n<data source=\"").append(sanitize(label).replace("\"", "'")).append("\">\n")
                append(sanitize(text).replace("</data>", "</ data>"))
                append("\n</data>")
            }
        }
        return chatml(answerSystem, history.map { (r, t) -> r to sanitize(t) } + ("user" to user))
    }

    companion object {
        /**
         * The native tokenizer parses special tokens in the prompt, so text from the user, a web
         * page, or a document must not carry ChatML control sequences such as <|im_start|>.
         */
        fun sanitize(s: String): String = s.replace(Regex("<\\|[^|<>]{0,40}\\|>"), "")
            .replace("<|", "< |")

        fun chatml(system: String, turns: List<Pair<String, String>>): String = buildString {
            append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
            turns.forEach { (role, text) -> append("<|im_start|>").append(role).append('\n').append(text).append("<|im_end|>\n") }
            append("<|im_start|>assistant\n")
        }
    }
}
