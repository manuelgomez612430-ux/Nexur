# --- REGLAS BASE ---
-optimizationpasses 5
-allowaccessmodification
-keepattributes Signature,Annotation*,EnclosingMethod,InnerClasses
-dontwarn javax.annotation.**

# --- ENTIDADES Y MODELOS (CRÍTICO) ---
# Mantenemos todas las clases en el paquete de datos para que Room y Firestore funcionen
-keep class com.gomezmart.app.data.** { *; }

# --- FIREBASE ---
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# --- GEMINI AI ---
-keep class com.google.ai.client.generativeai.** { *; }

# --- ROOM ---
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# --- RETROFIT / GSON ---
-keep class com.google.gson.** { *; }
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# --- GLIDE ---
-keep class com.bumptech.glide.** { *; }
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public class * extends com.bumptech.glide.module.LibraryGlideModule
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl { *; }

# --- MPANDROIDCHART ---
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# --- VIEW BINDING ---
-keep class com.gomezmart.app.databinding.** { *; }

# --- KTOR (REGLAS GENERADAS POR R8) ---
-dontwarn io.ktor.client.plugins.HttpTimeout$HttpTimeoutCapabilityConfiguration
-dontwarn io.ktor.client.plugins.HttpTimeout$Plugin
-dontwarn io.ktor.client.plugins.HttpTimeout
-dontwarn io.ktor.client.plugins.contentnegotiation.ContentNegotiation$Config
-dontwarn io.ktor.client.plugins.contentnegotiation.ContentNegotiation$Plugin
-dontwarn io.ktor.client.plugins.contentnegotiation.ContentNegotiation

# --- SUPABASE ---
-keep class io.github.jan.** { *; }
-dontwarn io.github.jan.**
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
