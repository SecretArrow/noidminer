# Keep the JNI entry points and native glue.
-keepclasseswithmembernames class org.secretarrow.noidminer.pow.NativePow {
    native <methods>;
}
-keep class org.secretarrow.noidminer.pow.** { *; }

# Tink (via androidx.security-crypto) references annotation-only classes
# that are not bundled; R8 only needs to ignore them.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.joda.time.**
-dontwarn com.google.api.client.**

# kotlinx.coroutines and Compose ship consumer rules; nothing else needed.
