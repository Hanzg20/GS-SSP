# Add project specific ProGuard rules here.

# --- PAX vendor SDK ---
# app/src/main/java/com/pax/** are local compile-time stubs; the real
# NeptuneLite/POSLink AIDL-backed AARs get dropped into libs/ for production
# builds and are invoked via reflection/AIDL binder stubs. Keep the whole
# package shape so R8 doesn't strip anything the real SDK's reflection needs.
-keep class com.pax.** { *; }
-dontwarn com.pax.**

# --- kotlinx.serialization ---
# Keep serializer() companions and @Serializable class shape; without this,
# R8 can strip fields kotlinx.serialization reaches via reflection at runtime,
# breaking JSON decode for AppConfig/Product/TransactionRecord/etc.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.goldsky.carwash.**$$serializer { *; }
-keepclassmembers class com.goldsky.carwash.** {
    *** Companion;
}
-keepclasseswithmembers class com.goldsky.carwash.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Ktor / Supabase-kt ---
# Both use Kotlin reflection and multiplatform service loading.
-dontwarn io.ktor.**
-dontwarn io.github.jan.supabase.**
-keep class io.ktor.** { *; }
-keep class io.github.jan.supabase.** { *; }
-keepclassmembers class * implements io.ktor.client.engine.HttpClientEngineContainer { *; }

# --- WorkManager ---
# Workers are instantiated by class name via reflection; keep their
# (Context, WorkerParameters) constructors.
-keep public class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep public class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- Lottie / ZXing / Coil ---
# These ship their own consumer-proguard-rules; keep as a safety net only.
-dontwarn com.airbnb.lottie.**
-dontwarn com.google.zxing.**
-dontwarn coil.**

# --- General Kotlin coroutines/reflection noise ---
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**

# --- Optional logging backends pulled in transitively (Ktor logging / Kermit) ---
# These are only used if actually present on the classpath at runtime; none of
# them are, so the reference is dead but R8 still needs telling not to error on it.
-dontwarn org.slf4j.**
