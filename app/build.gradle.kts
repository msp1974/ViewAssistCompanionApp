plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
//    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

tasks.register("printVersionName") {
    group = "custom"
    description = "Output version name for use in env vars"

    doLast {
        println(android.defaultConfig.versionName)
    }
}

android {
    namespace = "com.msp1974.vacompanion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.msp1974.vacompanion"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.10.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("armeabi-v7a")
        }
    }

    buildTypes {
        applicationVariants.all {
            this.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    var apkName = "vaca-" + this.versionName + "-" + this.buildType.name + ".apk"
                    output.outputFileName = apkName
                }
        }
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
            optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
    }
}


dependencies {

    implementation(project(":microfeatures"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.material3)
    implementation(libs.core.splashscreen)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics.ndk)
    implementation (libs.androidx.material.icons.extended)
    implementation (libs.androidx.preference.ktx)
    implementation (libs.timber)
    implementation (libs.onnxruntime.android)
    implementation (libs.semver)
    implementation (libs.okhttp)
    implementation (libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.accompanist.permissions)
    implementation(libs.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.litert)
    implementation(libs.protobuf.kotlin)
    implementation(libs.androidx.lifecycle.service)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation("com.github.wendykierp:JTransforms:3.2")

}
