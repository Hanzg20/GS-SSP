# GS-SSP Industrial Hardening - ProGuard/R8 Rules

# --- Global Optimizations ---
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification

# --- Log Stripping (Production) ---
# Automatically remove all Log.d and Log.v calls to reduce I/O and obfuscate logic flow.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# --- PAX vendor SDK ---
# Keeping only what's strictly necessary for reflection/AIDL
-keep class com.pax.** { *; }
-dontwarn com.pax.**

# --- ID TECH vendor SDK ---
-keep class com.idtechproducts.** { *; }
-dontwarn com.idtechproducts.**

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.goldsky.ssp.model.** { *; }
-keep,includedescriptorclasses class com.goldsky.ssp.payment.** { *; }
-keep class com.goldsky.ssp.**$$serializer { *; }
-keepclassmembers class com.goldsky.ssp.** { *** Companion; }

# --- Ktor / Supabase-kt ---
-dontwarn io.ktor.**
-dontwarn io.github.jan.supabase.**
-keep class io.ktor.** { *; }
-keep class io.github.jan.supabase.** { *; }
-keepclassmembers class * implements io.ktor.client.engine.HttpClientEngineContainer { *; }

# --- WorkManager ---
-keep public class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep public class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- Visual / Graphics (Lottie) ---
-keep class com.airbnb.lottie.** { *; }

# --- Standard Kotlin Noise ---
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**
-dontwarn org.slf4j.**
