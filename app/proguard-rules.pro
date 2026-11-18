# Keep PURR Engine Kotlinx Serialization models
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.projectmika.purrtts.core.purr.** { *; }

# Keep MediaPipe / LiteRT JNI symbols
-keep class com.google.mediapipe.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.lite.** { *; }

# Keep sherpa-onnx JNI classes when loaded via AAR or reflection
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

# Keep TextToSpeechService entrypoints
-keep class com.projectmika.purrtts.service.PurrTtsService { *; }
