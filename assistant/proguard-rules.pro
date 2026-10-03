# SandboxedInference.cpp calls PieceSink.onPiece by name through JNI.
-keep interface io.shubham0204.smollm.SandboxedLM$PieceSink { *; }
-keepclasseswithmembernames class * { native <methods>; }
