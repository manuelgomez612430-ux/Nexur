# --- REGLAS BASE ---
-optimizationpasses 5
-allowaccessmodification
-keepattributes Signature,Annotation*,EnclosingMethod,InnerClasses
-dontwarn javax.annotation.**

# --- ENTIDADES Y MODELOS (CRÍTICO) ---
# Room y Firestore instancian estas clases por reflexión, por lo que deben sobrevivir.
# Este es el ÚNICO -keep masivo que hace falta: el resto de librerías
# (Room, Gson, Retrofit, OkHttp, Glide, Ktor, Supabase, Firebase) ya incluyen
# sus propias "consumer rules", y mantenerlas todas anulaba R8 por completo
# (el dex pasaba de ~5 MB a ~15 MB).
-keep class com.gomezmart.app.data.** { *; }
-keep class com.gomezmart.app.data.**$* { *; }

# --- FIREBASE ---
# Firebase Auth / Firestore / Messaging ya traen consumer rules.
# Solo se conserva lo que la app usa por reflexión (modelos de chat, etc.).
-keep class com.gomezmart.app.network.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# --- GEMINI AI ---
-keep class com.google.ai.client.generativeai.** { *; }
-dontwarn com.google.ai.client.generativeai.**

# --- ROOM ---
-dontwarn androidx.room.**

# --- RETROFIT / GSON ---
# Gson usa reflexión para el mapeo de modelos: se conservan los DTOs del paquete data.
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn com.google.gson.**

# --- GLIDE ---
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public class * extends com.bumptech.glide.module.LibraryGlideModule
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl { *; }
-dontwarn com.bumptech.glide.**

# --- MPANDROIDCHART ---
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# --- VIEW BINDING ---
# Los ViewBinding se generan como clases finales con setters; R8 puede estirarlos
# sin problema, pero se conservan por seguridad al referenciarlos por reflexión
# en algunos adapters dinámicos.
-keep class com.gomezmart.app.databinding.** { *; }

# --- KTOR (REGLAS GENERADAS POR R8) ---
-dontwarn io.ktor.client.plugins.HttpTimeout$HttpTimeoutCapabilityConfiguration
-dontwarn io.ktor.client.plugins.HttpTimeout$Plugin
-dontwarn io.ktor.client.plugins.HttpTimeout
-dontwarn io.ktor.client.plugins.contentnegotiation.ContentNegotiation$Config
-dontwarn io.ktor.client.plugins.contentnegotiation.ContentNegotiation$Plugin
-dontwarn io.ktor.client.plugins.contentnegotiation.ContentNegotiation

# --- SUPABASE ---
-dontwarn io.github.jan.**
-dontwarn io.ktor.**

# --- Zxing (generador de códigos QR) ---
-dontwarn com.google.zxing.**
