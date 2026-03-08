plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.pas"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.pas"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "AUTH_URL", "\"http://127.0.0.1/pkastro_test/athenticate_mobile.php\"")
            buildConfigField("String", "SITE_URL", "\"http://127.0.0.1/pkastro_test/index.php\"")
        }
        create("preprod") {
            dimension = "environment"
            buildConfigField("String", "AUTH_URL", "\"http://pkastro.com/preprod/athenticate_mobile.php\"")
            buildConfigField("String", "SITE_URL", "\"http://pkastro.com/preprod/index.php\"")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "AUTH_URL", "\"http://pkastro.com/athenticate_mobile.php\"")
            buildConfigField("String", "SITE_URL", "\"http://pkastro.com/index.php\"")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("D:\\praveen\\PAS\\keystore.jks") // Replace with your keystore file
            storePassword = "324266" // Replace with your keystore password
            keyAlias = "PAS" // Replace with your key alias
            keyPassword = "324266" // Replace with your key password
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.browser:browser:1.6.0")
    implementation("com.google.android.gms:play-services-location:21.0.1")
    implementation(libs.androidx.core.splashscreen)
    implementation("androidx.appcompat:appcompat:1.6.1") // Add AppCompat library
    implementation("androidx.webkit:webkit:1.8.0")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit) // Add WebKit library for WebView support
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)


}