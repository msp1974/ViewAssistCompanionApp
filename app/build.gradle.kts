plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = "com.msp1974.vacompanion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.msp1974.vacompanion"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.3.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        setProperty("archivesBaseName", "vaca-$versionName")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.core.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation ("org.apache.commons:commons-math3:3.6.1")
    implementation ("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation ("javax.jmdns:jmdns:3.4.1")
    implementation ("androidx.webkit:webkit:1.14.0")
    implementation ("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation ("androidx.preference:preference-ktx:1.2.0")
    implementation ("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation ("com.squareup.okhttp3:okhttp:3.10.0")
}