plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.senza.apkforge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.senza.apkforge"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
        ndk { abiFilters += "arm64-v8a" }   // aapt2 yang dipakai hanya ARM64
    }

    packaging {
        // wajib: supaya libaapt2.so diekstrak ke nativeLibraryDir dan bisa dieksekusi
        jniLibs { useLegacyPackaging = true }
        resources { pickFirsts += setOf("META-INF/**") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.eclipse.jdt:ecj:3.33.0")              // compiler Java
    implementation("com.android.tools:r8:8.1.72")             // berisi D8 (class -> dex)
    implementation("com.android.tools.build:apksig:8.1.4")    // tanda tangan APK
}
