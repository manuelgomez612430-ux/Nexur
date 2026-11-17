import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.gomezmart.app"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    dynamicFeatures += setOf(":feature_hotel", ":feature_loans", ":feature_products")

    val localProperties = Properties()
    val localPropertiesFile = project.rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.inputStream())
    }
    val geminiKey = localProperties.getProperty("GEMINI_API_KEY") ?: ""

    defaultConfig {
        applicationId = "com.naxor.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 65
        versionName = "1.5.52"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")

        ndk {
            debugSymbolLevel = "SYMBOL_TABLE"
        }
    }

    signingConfigs {
        val keystorePath = project.findProperty("MYAPP_RELEASE_STORE_FILE")?.toString() ?: "gomezmart.jks"
        val keystoreFile = file(keystorePath)
        
        create("release") {
            storeFile = keystoreFile
            storePassword = project.findProperty("MYAPP_RELEASE_STORE_PASSWORD")?.toString()
            keyAlias = project.findProperty("MYAPP_RELEASE_KEY_ALIAS")?.toString()
            keyPassword = project.findProperty("MYAPP_RELEASE_KEY_PASSWORD")?.toString()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            
            ndk {
                abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
                debugSymbolLevel = "SYMBOL_TABLE"
            }

            val keystorePath = project.findProperty("MYAPP_RELEASE_STORE_FILE")?.toString() ?: "gomezmart.jks"
            if (file(keystorePath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            val keystorePath = project.findProperty("MYAPP_RELEASE_STORE_FILE")?.toString() ?: "gomezmart.jks"
            if (file(keystorePath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Empaquetado de recursos: se excluyen los metadatos de Maven duplicados.
    // (Los .mp4/.wav de demo de firebase-ai los gestiona AAPT2 y no se pueden
    //  excluir desde aquí; ocupan 206 KB y son inofensivos.)
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/previous-compilation-data.bin"
            )
        }
    }

    applicationVariants.all {
        val variantName = name
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            output.outputFileName = "GoMezMart_v${defaultConfig.versionName}_${variantName}.apk"
        }
    }
}

// Garantiza que todas las librerías nativas (.so) de terceros incluyan su archivo .so.sym
// dentro del App Bundle (BUNDLE-METADATA/com.android.tools.build.debugsymbols) para evitar
// la advertencia de símbolos nativos en Google Play Console.
tasks.matching { it.name == "extractReleaseNativeSymbolTables" }.configureEach {
    doLast {
        val mergedLibDir = layout.buildDirectory.dir("intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib").get().asFile
        val outSymDir = layout.buildDirectory.dir("intermediates/native_symbol_tables/release/extractReleaseNativeSymbolTables/out").get().asFile
        val sdkDir = android.sdkDirectory
        val objcopy = File(sdkDir, "ndk/${android.ndkVersion}/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-objcopy.exe")
        if (mergedLibDir.exists()) {
            mergedLibDir.listFiles()?.filter { it.isDirectory }?.forEach { abiDir ->
                val targetAbiDir = File(outSymDir, abiDir.name)
                targetAbiDir.mkdirs()
                abiDir.listFiles()?.filter { it.extension == "so" }?.forEach { soFile ->
                    val symFile = File(targetAbiDir, "${soFile.name}.sym")
                    if (!symFile.exists()) {
                        if (objcopy.exists()) {
                            ProcessBuilder(
                                objcopy.absolutePath,
                                "--only-keep-debug",
                                soFile.absolutePath,
                                symFile.absolutePath
                            ).start().waitFor()
                        }
                        if (!symFile.exists()) {
                            soFile.copyTo(symFile, overwrite = true)
                        }
                    }
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    
    // GMS Code Scanner (Ligero, usa Google Play Services)
    implementation(libs.google.scanner)
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    
    // Mapa Gratuito (Sin necesidad de tarjeta)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    
    // CameraX (Necesario solo para funciones específicas de cámara)
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")

    // Imágenes (Optimización)
    implementation(libs.glide)
    annotationProcessor(libs.glide.compiler)

    // Gráficos
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Generador de códigos
    implementation("com.google.zxing:core:3.5.3")

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    annotationProcessor(libs.room.compiler)
    
    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-ai:16.0.0-beta01")
    // NOTA: El SDK legacy "com.google.ai.client.generativeai:generativeai" se eliminó:
    // solo se usaba en HotelMetricsFragment con el modelo ya retirado gemini-1.5-flash.
    // Ahora toda la IA pasa por GeminiHelper + GeminiDirectClient, lo que ahorra
    // ~2 MB de dex (okhttp/gson/protobuf/play-services duplicados).
    // NOTA: Firebase App Check se deshabilita a propósito. El token de App Check
    // devolvía 403 "App attestation failed" y bloqueaba TODAS las consultas a la
    // IA de Gemini (la petición se quedaba colgada hasta expirar).

    // Network (Consultas RUC/DNI)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // Supabase (Almacenamiento de Imágenes)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.storage)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.core)

    // Recorte de Imágenes (Moderno)
    implementation("com.github.CanHub:Android-Image-Cropper:4.5.0")

    // Play Feature Delivery y Updates (Descarga de Módulos y Actualización desde Play Store)
    implementation(libs.play.feature.delivery)
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // Google Play Billing (Suscripciones y Productos In-App)
    implementation(libs.play.billing)

    // Forzar j2objc-annotations como implementation para compatibilidad con AGP Dynamic Features
    implementation("com.google.j2objc:j2objc-annotations:2.8")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
