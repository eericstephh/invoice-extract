# =============================================================================
# InvoiceExtract — R8 / ProGuard rules (Phase 18)
#
# Minification is enabled on the release build. Every rule here exists because R8
# would otherwise strip or rename something the app reaches for reflectively at
# runtime. Untouched library code is still free to be shrunk.
# =============================================================================

# --- Compose / Kotlin --------------------------------------------------------
# Keep the compiler-generated groups and lambdas Compose relies on for recomposition.
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# --- Kotlinx Serialization ---------------------------------------------------
# The plugin generates a `Companion.serializer()` on every @Serializable type and
# resolves it by reflection at runtime. Rename the class or the member and
# decodeFromString throws MissingFieldException on a release build only.
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,allowobfuscation,allowshrinking class * {
    @kotlinx.serialization.Serializable class *;
}

# --- Room DB -----------------------------------------------------------------
# The database class is instantiated reflectively by Room's generated code, and the
# DAO implementations are created from the abstract interface at runtime.
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# --- Koin 4.0 ----------------------------------------------------------------
# Koin resolves modules and definitions reflectively; shrinking them breaks the
# graph at startup with a KoinDefinitionException.
-keep class org.koin.** { *; }
-dontwarn org.koin.**
-keep class * extends io.insertkoin.core.module.Module

# --- OkHttp & Coroutines -----------------------------------------------------
# OkHttp's platform detection touches classes that do not exist on Android; the
# warnings are expected. PublicSuffixDatabase is loaded by name from a resource.
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# --- Persian & Domain Models -------------------------------------------------
# These cross the OCR -> AI -> DB pipeline and are serialized by name. Renaming a
# field here silently breaks a stored invoice, not just a network call.
-keep class com.invoiceextract.domain.model.** { *; }
-keep class com.invoiceextract.domain.validation.** { *; }
-keep class com.invoiceextract.domain.quota.** { *; }
-keep class com.invoiceextract.app.data.remote.dto.** { *; }
-keep class com.invoiceextract.app.data.local.db.entity.** { *; }

# --- Enums -------------------------------------------------------------------
# CurrencyType and IssueSeverity are persisted by name; values()/valueOf() are
# reached reflectively.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
