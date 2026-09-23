package io.shubham0204.smollmandroid.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.shubham0204.smollmandroid.assistant.tools.ToolCall

@Composable
fun AssistantScreen(vm: AssistantViewModel, onImportModel: () -> Unit, onAddDocument: () -> Unit) {
    var showSetup by remember { mutableStateOf(true) }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                Text(vm.status, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showSetup = !showSetup }) { Text(if (showSetup) "Hide setup" else "Setup") }
                    if (vm.busy) TextButton(onClick = vm::stop) { Text("Stop") }
                }
                if (showSetup) Setup(vm, onImportModel, onAddDocument)
                Messages(vm, Modifier.weight(1f))
                Composer(vm)
            }
            vm.pending?.let { ConfirmTool(it, vm) }
        }
    }
}

@Composable
private fun Setup(vm: AssistantViewModel, onImportModel: () -> Unit, onAddDocument: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImportModel, enabled = !vm.busy) { Text("Import GGUF") }
            Button(onClick = onAddDocument, enabled = !vm.busy) { Text("Grant document") }
        }
        Text("Models", style = MaterialTheme.typography.titleSmall)
        if (vm.modelFiles.isEmpty()) Text("none: import a GGUF, or push one (see ASSISTANT_APP.md)", fontSize = 12.sp)
        vm.modelFiles.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${f.name} (${f.length() / (1 shl 20)} MiB)", Modifier.weight(1f), fontSize = 12.sp)
                TextButton(onClick = { vm.loadModel(f) }, enabled = !vm.busy) { Text("Load") }
                TextButton(onClick = { vm.deleteModel(f) }, enabled = !vm.busy) { Text("Delete") }
            }
        }
        Text("Documents (read_file)", style = MaterialTheme.typography.titleSmall)
        if (vm.documents.isEmpty()) Text("none granted", fontSize = 12.sp)
        vm.documents.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, Modifier.weight(1f), fontSize = 12.sp)
                TextButton(onClick = { vm.removeDocument(d) }) { Text("Revoke") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-approve read_file (web search and settings always ask)", Modifier.weight(1f), fontSize = 12.sp)
            Switch(checked = vm.autoApprove, onCheckedChange = vm::updateAutoApprove)
        }
    }
}

@Composable
private fun Messages(vm: AssistantViewModel, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text?.length) {
        if (vm.messages.isNotEmpty()) state.scrollToItem(vm.messages.lastIndex)
    }
    LazyColumn(modifier.fillMaxWidth(), state = state) {
        itemsIndexed(vm.messages) { _, m ->
            val (label, bg) = when (m.role) {
                AssistantViewModel.Role.USER -> "you" to MaterialTheme.colorScheme.primaryContainer
                AssistantViewModel.Role.ASSISTANT -> "assistant" to MaterialTheme.colorScheme.surfaceVariant
                AssistantViewModel.Role.TOOL -> "tool" to MaterialTheme.colorScheme.tertiaryContainer
                AssistantViewModel.Role.STATUS -> "status" to MaterialTheme.colorScheme.errorContainer
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).background(bg).padding(6.dp)) {
                Text(label, fontSize = 10.sp)
                Text(
                    m.text,
                    fontSize = 14.sp,
                    fontFamily = if (m.role == AssistantViewModel.Role.TOOL) FontFamily.Monospace else FontFamily.Default,
                )
            }
        }
    }
}

@Composable
private fun Composer(vm: AssistantViewModel) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f), placeholder = { Text("Ask") })
        Button(
            onClick = {
                vm.send(text)
                text = ""
            },
            enabled = !vm.busy && vm.modelInfo != null && text.isNotBlank(),
            modifier = Modifier.padding(start = 6.dp),
        ) { Text("Send") }
    }
}

@Composable
private fun ConfirmTool(p: AssistantViewModel.PendingTool, vm: AssistantViewModel) {
    var chosen by remember(p) { mutableStateOf((p.call as? ToolCall.ReadFile)?.document) }
    val call = (p.call as? ToolCall.ReadFile)?.copy(document = chosen) ?: p.call
    AlertDialog(
        onDismissRequest = { vm.decide(null) },
        title = { Text("Run ${p.call.name}?") },
        text = {
            Column {
                Text(call.describe())
                Text(p.detail, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                if (p.call is ToolCall.ReadFile) {
                    p.documents.forEach { d ->
                        TextButton(onClick = { chosen = d }) { Text((if (d == chosen) "[x] " else "[ ] ") + d) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.decide(call) },
                enabled = call !is ToolCall.ReadFile || call.document != null,
            ) { Text("Run") }
        },
        dismissButton = { TextButton(onClick = { vm.decide(null) }) { Text("Skip") } },
    )
}
