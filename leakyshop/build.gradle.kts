plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.leakyshop.demo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.leakyshop.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    flavorDimensions += "mode"
    productFlavors {
        create("leaky") {
            dimension = "mode"
            buildConfigField("boolean", "COMPLIANT", "false")
            resValue("string", "app_name", "LeakyShop")
        }
        create("fixed") {
            dimension = "mode"
            applicationIdSuffix = ".fixed"
            buildConfigField("boolean", "COMPLIANT", "true")
            resValue("string", "app_name", "LeakyShop Fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
