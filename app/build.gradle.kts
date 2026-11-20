plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.rfid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.rfid"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // SEUIC vendor SDK. These jars are compile-time STUBS (every method throws "Stub!").
    // compileOnly = compile against them but do NOT package them into the APK, so the real
    // classes on the reader are used at runtime.
    compileOnly(files("libs/uhf.jar"))
    compileOnly(files("libs/scankey.jar"))

    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}