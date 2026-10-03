package io.shubham0204.smollmandroid.assistant.ipc;

// Implemented by the app process; called from the isolated inference process.
oneway interface IGenerationCallback {
    void onPiece(String piece);
    void onDone(int tokens, long elapsedMs);
    void onError(String message);
}
