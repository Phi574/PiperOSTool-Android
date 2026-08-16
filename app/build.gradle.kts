import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.piperostool"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.piper.os.tool"
        minSdk = 24
        targetSdk = 36
        versionCode = 28
        versionName = "3.0.8.beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }


    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(files("libs/reandroid-apkeditor-1.4.9.jar"))
    implementation(files("libs/reandroid-arsclib.jar"))
    implementation(files("libs/reandroid-smali.jar"))
    implementation(files("libs/reandroid-jcommand.jar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth:22.3.1")
    implementation("com.google.firebase:firebase-firestore:24.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    implementation("com.google.crypto.tink:tink-android:1.23.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.hbb20:ccp:2.7.3")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("com.google.firebase:firebase-database")
    implementation(libs.glide)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.android.tools.build:apksig:8.13.2")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("net.lingala.zip4j:zip4j:2.11.6")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
    implementation("com.github.luben:zstd-jni:1.5.7-10@aar")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
