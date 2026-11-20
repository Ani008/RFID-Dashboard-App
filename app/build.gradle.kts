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
    // SEUIC vendor SDK (same two jars the vendor demo uses). The UHF classes are also
    // provided by the reader's firmware via <uses-library> in the manifest.
    implementation(files("libs/uhf.jar"))
    implementation(files("libs/scankey.jar"))

    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}