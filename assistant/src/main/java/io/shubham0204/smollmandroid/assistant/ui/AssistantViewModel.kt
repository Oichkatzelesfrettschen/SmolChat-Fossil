package io.shubham0204.smollmandroid.assistant.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.shubham0204.smollmandroid.assistant.data.AssistantSettings
import io.shubham0204.smollmandroid.assistant.data.ModelStore
import io.shubham0204.smollmandroid.assistant.inference.InferenceClient
import io.shubham0204.smollmandroid.assistant.prompt.Prompts
import io.shubham0204.smollmandroid.assistant.tools.DocumentStore
import io.shubham0204.smollmandroid.assistant.tools.ToolCall
import io.shubham0204.smollmandroid.assistant.tools.WebSearchClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One assistant turn:
 * 1. planner pass (grammar-constrained) over the user's message only;
 * 2. each proposed tool call is shown to the user and runs only after a tap
 *    (read_file may be auto-approved in settings);
 * 3. answer pass without tools; tool output enters it as <data> blocks.
 */
class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    enum class Role { USER, ASSISTANT, TOOL, STATUS }

    data class Message(val role: Role, val text: String)

    /** A tool call awaiting the user's decision; completing [decision] with null skips it. */
    data class PendingTool(
        val call: ToolCall,
        val detail: String,
        val documents: List<String>,
        val decision: CompletableDeferred<ToolCall?>,
    )

    private val ctx = app.applicationContext
    val settings = AssistantSettings(ctx)
    private val models = ModelStore(ctx)
    private val docs = DocumentStore(ctx)
    private val web = WebSearchClient(ctx)
    private val prompts = Prompts(ctx.assets)
    private val inference = InferenceClient(ctx)

    val messages = mutableStateListOf<Message>()
    var status by mutableStateOf("No model loaded")
        private set
    var busy by mutableStateOf(false)
        private set
    var pending by mutableStateOf<PendingTool?>(null)
        private set
    var modelFiles by mutableStateOf(models.list())
        private set
    var documents by mutableStateOf(docs.list())
        private set
    var modelInfo by mutableStateOf<InferenceClient.ModelInfo?>(null)
        private set
    var autoApprove by mutableStateOf(settings.autoApproveReadOnly)
        private set

    private var tier = Prompts.Tier.INTENT
    private val history = ArrayDeque<Pair<String, String>>()
    private var turn: Job? = null

    init {
        inference.bind {
            viewModelScope.launch(Dispatchers.Main) {
                modelInfo = null
                busy = false
                status = "Inference process died (out of memory or crashed); load the model again"
            }
        }
    }

    override fun onCleared() {
        inference.unbind()
    }

    fun updateAutoApprove(v: Boolean) {
        settings.autoApproveReadOnly = v
        autoApprove = v
    }

    fun importModel(uri: Uri) = background("Importing model") {
        val f = models.import(uri) { n -> status = "Importing model: ${n / (1 shl 20)} MiB" }
        modelFiles = models.list()
        status = "Imported ${f.name} (${f.length() / (1 shl 20)} MiB)"
    }

    fun deleteModel(f: File) {
        models.delete(f)
        modelFiles = models.list()
    }

    fun addDocument(uri: Uri) = background("Adding document") {
        val d = docs.add(uri)
        documents = docs.list()
        status = "Granted read access to ${d.name}"
    }

    fun removeDocument(d: DocumentStore.Doc) {
        docs.remove(d)
        documents = docs.list()
    }

    fun loadModel(f: File) = background("Loading ${f.name}") {
        val nCtx = settings.contextSize.takeIf { it > 0 } ?: if (f.length() > LARGE_MODEL_BYTES) 1024 else 2048
        val info = inference.load(f, nCtx, settings.threads)
        tier = if (info.nParams >= 1_000_000_000L) Prompts.Tier.FULL else Prompts.Tier.INTENT
        modelInfo = info
        settings.lastModel = f.name
        history.clear()
        status = "${info.description} | ctx ${info.nCtx} | ${settings.threads} threads | planner ${tier.name.lowercase()} | " +
            "inference uid ${info.serviceUid} isolated=${info.serviceIsolated}"
    }

    fun decide(call: ToolCall?) {
        pending?.decision?.complete(call)
        pending = null
    }

    fun stop() {
        viewModelScope.launch { inference.cancel() }
        pending?.decision?.complete(null)
        pending = null
    }

    fun send(text: String) {
        val info = modelInfo ?: return
        if (busy || text.isBlank()) return
        busy = true
        messages += Message(Role.USER, text)
        turn = viewModelScope.launch {
            try {
                runTurn(text.trim(), info)
            } catch (e: Exception) {
                messages += Message(Role.STATUS, "Error: ${e.message}")
            } finally {
                busy = false
                pending = null
            }
        }
    }

    private suspend fun runTurn(text: String, info: InferenceClient.ModelInfo) {
        val docNames = documents.map { it.name }

        status = "Planning (${tier.name.lowercase()})"
        val plan = collect(
            inference.generate(
                prompts.plannerPrompt(tier, text, docNames),
                prompts.plannerGrammar(tier, docNames),
                0f,
                if (tier == Prompts.Tier.FULL) 192 else 32,
            ),
        ) {}
        val calls = prompts.parsePlan(tier, plan, text, docNames)
        messages += Message(Role.TOOL, "planner: ${plan.trim()}")

        // Context budget for the answer pass: system prompt, history, question,
        // tool data, and the answer must fit n_ctx. History goes first, oldest
        // exchange first, until the data gets at least MIN_DATA_TOKENS.
        val answerTokens = if (info.nCtx <= 1024) 256 else 384
        val fixed = answerTokens + PROMPT_OVERHEAD_TOKENS + estimateTokens(text)
        val minData = if (calls.isEmpty()) 0 else MIN_DATA_TOKENS
        while (history.isNotEmpty() && info.nCtx - fixed - historyTokens() < minData) {
            history.removeFirst()
            history.removeFirst()
        }
        val dataTokens = (info.nCtx - fixed - historyTokens()).coerceAtLeast(0)
        val perCall = dataTokens * CHARS_PER_TOKEN / maxOf(1, calls.size)
        val results = mutableListOf<Pair<String, String>>()
        for (proposed in calls) {
            val call = confirm(proposed, docNames) ?: run {
                messages += Message(Role.TOOL, "skipped ${proposed.name}")
                null
            } ?: continue
            status = "Running ${call.name}"
            val out = try {
                execute(call, perCall)
            } catch (e: Exception) {
                "tool error: ${e.message}"
            }
            messages += Message(Role.TOOL, "${call.name}: " + out.take(400) + if (out.length > 400) " ..." else "")
            results += label(call) to out.take(perCall)
        }

        status = "Answering"
        val prompt = prompts.answerPrompt(history.toList(), text, results)
        val idx = messages.size
        messages += Message(Role.ASSISTANT, "")
        var done: InferenceClient.Event.Done? = null
        val answer = collect(inference.generate(prompt, null, 0.3f, answerTokens)) { d -> done = d }
        messages[idx] = Message(Role.ASSISTANT, answer.ifEmpty { "(no answer)" })
        history.addLast("user" to text)
        history.addLast("assistant" to answer)
        while (history.size > 4) history.removeFirst()
        status = done?.let { "Answered: ${it.tokens} tokens in ${it.elapsedMs} ms" } ?: "Answered"
        // stream progress is shown by rewriting messages[idx] inside collect
    }

    private fun historyTokens() = history.sumOf { estimateTokens(it.second) + 8 }

    private fun estimateTokens(s: String) = s.length / CHARS_PER_TOKEN + 1

    private suspend fun collect(
        flow: kotlinx.coroutines.flow.Flow<InferenceClient.Event>,
        onDone: (InferenceClient.Event.Done) -> Unit,
    ): String {
        val sb = StringBuilder()
        val streamIdx = messages.lastIndex
        val streaming = messages.getOrNull(streamIdx)?.let { it.role == Role.ASSISTANT && it.text.isEmpty() } == true
        flow.collect { e ->
            when (e) {
                is InferenceClient.Event.Piece -> {
                    sb.append(e.text)
                    if (streaming) messages[streamIdx] = Message(Role.ASSISTANT, sb.toString())
                }
                is InferenceClient.Event.Done -> onDone(e)
            }
        }
        return sb.toString()
    }

    private suspend fun confirm(call: ToolCall, docNames: List<String>): ToolCall? {
        if (call is ToolCall.ReadFile && call.document != null && autoApprove) return call
        val detail = when (call) {
            is ToolCall.WebSearch -> "Endpoint: " + runCatching { web.endpoint() }.getOrElse { "unavailable (${it.message})" }
            is ToolCall.ReadFile -> if (docNames.isEmpty()) "No documents are granted." else "Granted documents: ${docNames.joinToString()}"
            is ToolCall.OpenSettings -> "Opens a system screen; nothing changes until you act there."
        }
        val p = PendingTool(call, detail, docNames, CompletableDeferred())
        pending = p
        return p.decision.await()
    }

    private suspend fun execute(call: ToolCall, maxChars: Int): String =
        when (call) {
            is ToolCall.ReadFile -> withContext(Dispatchers.IO) {
                docs.read(call.document ?: error("no document selected"), maxChars)
            }
            is ToolCall.WebSearch -> web.search(call.query).ifEmpty { "no results" }
            is ToolCall.OpenSettings -> call.page.open(ctx)
        }

    private fun label(call: ToolCall) = when (call) {
        is ToolCall.ReadFile -> "read_file: ${call.document}"
        is ToolCall.WebSearch -> "web_search: ${call.query}"
        is ToolCall.OpenSettings -> "open_settings: ${call.page.key}"
    }

    private fun background(what: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = what
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                status = "$what failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    companion object {
        private const val LARGE_MODEL_BYTES = 700L shl 20
        // answer_system.txt plus ChatML framing and <data> labels
        private const val PROMPT_OVERHEAD_TOKENS = 160
        private const val MIN_DATA_TOKENS = 250

        // DuckDuckGo result text tokenizes at 2.55 chars/token with the SmolLM2
        // vocabulary (URLs, punctuation); 2 keeps the estimate on the safe side
        private const val CHARS_PER_TOKEN = 2
    }
}
