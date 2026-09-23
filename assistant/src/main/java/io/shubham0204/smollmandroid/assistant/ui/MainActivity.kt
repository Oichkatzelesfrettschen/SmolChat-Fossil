package io.shubham0204.smollmandroid.assistant.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import io.shubham0204.smollmandroid.assistant.tools.DocumentStore

class MainActivity : ComponentActivity() {
    private val vm: AssistantViewModel by viewModels()

    // Storage Access Framework only: the app requests no storage permission.
    private val pickModel =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importModel) }
    private val pickDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::addDocument) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AssistantScreen(
                vm = vm,
                onImportModel = { pickModel.launch(arrayOf("application/octet-stream", "*/*")) },
                onAddDocument = { pickDocument.launch(DocumentStore.OPEN_MIME_TYPES) },
            )
        }
    }
}
