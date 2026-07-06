import java.io.FileInputStream
import java.util.Properties
import kotlin.toString

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
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

    val versionPropsFile = file("../version.properties")
    var code = 0

    if (versionPropsFile.canRead()) {
        val versionProps = Properties()
        versionProps.load(FileInputStream(versionPropsFile))
        code = versionProps["VERSION_CODE"].toString().toInt() + 1
        versionProps["VERSION_CODE"] = code.toString()
        versionProps.store(versionPropsFile.writer(), null)
    } else {
        throw GradleException("Could not read version.properties!")
    }

    defaultConfig {
        applicationId = "com.msp1974.vacompanion"
        minSdk = 26
        targetSdk = 36
        versionName = "0.12.1"
        versionCode = code
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            ndk {
                abiFilters.add("arm64-v8a")
                abiFilters.add("armeabi-v7a")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            ndk {
                abiFilters.add("arm64-v8a")
                abiFilters.add("armeabi-v7a")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val name = "vaca-${variant.outputs.first().versionName.get()}-${variant.name}.apk"
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(name)
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
    implementation(libs.core.splashscreen)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics.ndk)
    implementation (libs.androidx.material.icons.extended)
    implementation (libs.androidx.preference.ktx)
    implementation (libs.timber)
    implementation (libs.onnxruntime.android)
    implementation (libs.semver)
    implementation (libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.view)
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.litert)
    implementation(libs.protobuf.kotlin)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.ktor.network)
    implementation(libs.ktor.client.core) // Ktor-Core
    implementation(libs.ktor.client.cio) // Ktor-Engine
    implementation(libs.ktor.serialization.kotlinx.json) // Ktor- To work with Serialization
    implementation(libs.ktor.client.content.negotiation) // Serialization
    implementation(libs.androidx.datastore.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.mlkit.face.detection)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
}
