import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.naxor.app"
    compileSdk = 35

    val localProperties = Properties()
    val localPropertiesFile = project.rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.inputStream())
    }
    val geminiKey = localProperties.getProperty("GEMINI_API_KEY") ?: ""

    defaultConfig {
        applicationId = "com.naxor.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 5 // Sube esto cada vez que envíes una actualización
        versionName = "1.3" // Nombre visual de la versión

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
    }

    signingConfigs {
        val keystorePath = project.findProperty("MYAPP_RELEASE_STORE_FILE")?.toString() ?: "naxor.jks"
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
            }

            val keystorePath = project.findProperty("MYAPP_RELEASE_STORE_FILE")?.toString() ?: "naxor.jks"
            if (file(keystorePath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            val keystorePath = project.findProperty("MYAPP_RELEASE_STORE_FILE")?.toString() ?: "naxor.jks"
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

    applicationVariants.all {
        val variantName = name
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            output.outputFileName = "Naxor_v${defaultConfig.versionName}_${variantName}.apk"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    
    // GMS Code Scanner (Ligero, usa Google Play Services)
    implementation(libs.google.scanner)
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    
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
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.firebase:firebase-appcheck-debug")

    // Network (Consultas RUC/DNI)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
