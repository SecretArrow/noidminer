# Keep the JNI entry points and native glue.
-keepclasseswithmembernames class org.secretarrow.noidminer.pow.NativePow {
    native <methods>;
}
-keep class org.secretarrow.noidminer.pow.** { *; }

# kotlinx.coroutines and Compose ship consumer rules; nothing else needed.
