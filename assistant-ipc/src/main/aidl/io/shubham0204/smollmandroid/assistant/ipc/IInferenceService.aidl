package io.shubham0204.smollmandroid.assistant.ipc;

import io.shubham0204.smollmandroid.assistant.ipc.IGenerationCallback;

// Served by the isolatedProcess inference service. The service receives a
// read-only model descriptor and prompt text, and returns only text.
interface IInferenceService {
    // Returns "description\nnParams\nsizeBytes\nnCtx\nuid\nisolated".
    String load(in ParcelFileDescriptor model, int nCtx, int nThreads);

    // grammar: GBNF text with a root rule, or null for free text.
    // Returns at once; results arrive on cb.
    void generate(String prompt, String grammar, float temperature, int maxTokens,
                  IGenerationCallback cb);

    void cancel();

    void unload();
}
