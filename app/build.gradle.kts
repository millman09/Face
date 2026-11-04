plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.face"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.face"
        minSdk = 35
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
