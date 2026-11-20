plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ekincare.onsitehealthcamp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ekincare.onsitehealthcamp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // Release builds always load prod.
        buildConfigField("String", "WEB_URL", "\"https://onsite-providers.ekincare.com\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // Test build: OnsiteApt PR preview (Amplify). Change per PR being tested.
            buildConfigField("String", "WEB_URL", "\"https://pr-13.d1fle6jimql6gu.amplifyapp.com\"")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
}
