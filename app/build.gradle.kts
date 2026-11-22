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
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The commit this build was built from, so the app can tell whether the "latest-debug"
        // GitHub release is actually newer than what's installed.
        val gitSha = providers.exec {
            commandLine("git", "rev-parse", "HEAD")
        }.standardOutput.asText.get().trim()
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // R8 is off: the minified build installed fine but was never tapped through, and the
            // app closed on every tap. Turn it back on only after testing a minified build.
            isMinifyEnabled = false
            isShrinkResources = false
            // Use the debug key for this personal test build so it can update the existing debug APK.
            signingConfig = signingConfigs.getByName("debug")
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
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
