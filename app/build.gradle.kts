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
        versionCode = 2 // Sube esto cada vez que envíes una actualización
        versionName = "1.1" // Nombre visual de la versión

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
    }

    signingConfigs {
        create("release") {
            // Estos datos se leerán de un archivo seguro que configuraremos luego
            storeFile = file(project.findProperty("MYAPP_RELEASE_STORE_FILE") ?: "asistente_comercial.jks")
            storePassword = project.findProperty("MYAPP_RELEASE_STORE_PASSWORD")?.toString()
            keyAlias = project.findProperty("MYAPP_RELEASE_KEY_ALIAS")?.toString()
            keyPassword = project.findProperty("MYAPP_RELEASE_KEY_PASSWORD")?.toString()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release") // Aplicar la firma a la versión final
        }
        debug {
            signingConfig = signingConfigs.getByName("release") // Usar la misma firma en debug para poder probar actualizaciones
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        jvmToolchain(11)
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.google.scanner)
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
    implementation(libs.google.scanner)
    implementation(libs.google.textrecognition)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Imágenes (Optimización)
    implementation(libs.glide)
    annotationProcessor(libs.glide.compiler)

    // Room
    
    // Gráficos
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Generador de códigos
    implementation("com.google.zxing:core:3.5.3")

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    annotationProcessor(libs.room.compiler)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-analytics")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
