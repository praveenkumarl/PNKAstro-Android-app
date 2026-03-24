import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pnkastro.pas"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pnkastro.pas"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            // Use HTTPS for local/dev endpoints to enforce HTTPS usage during testing as requested
            buildConfigField("String", "AUTH_URL", "\"https://127.0.0.1/pkastro_test/athenticate_mobile.php\"")
            buildConfigField("String", "SITE_URL", "\"https://127.0.0.1/pkastro_test/index.php\"")
            buildConfigField("String", "TRIAL_URL", "\"https://127.0.0.1/pkastro_test/new_registration_mobile_trial.php\"")
            buildConfigField("String", "ENV_NAME", "\"DEV\"")
            // Add flavor-specific app name shown on launcher
            resValue("string", "app_name", "\"PNKAstro (DEV)\"")
        }
        create("preprod") {
            dimension = "environment"
            applicationIdSuffix = ".preprod"
            versionNameSuffix = "-preprod"
            buildConfigField("String", "AUTH_URL", "\"https://pkastro.com/pnkastro_preprod/athenticate_mobile.php\"")
            buildConfigField("String", "SITE_URL", "\"https://pkastro.com/pnkastro_preprod/index.php\"")
            buildConfigField("String", "TRIAL_URL", "\"https://pkastro.com/pnkastro_preprod/new_registration_mobile_trial.php\"")
            buildConfigField("String", "ENV_NAME", "\"PREPROD\"")
            // Add flavor-specific app name shown on launcher
            resValue("string", "app_name", "\"PNKAstro (PREPROD)\"")
        }
        create("prod") {
            dimension = "environment"
            // Keep original applicationId for prod
            buildConfigField("String", "AUTH_URL", "\"https://pkastro.com/pnkastro_prod/athenticate_mobile.php\"")
            buildConfigField("String", "SITE_URL", "\"https://pkastro.com/pnkastro_prod/index.php\"")
            buildConfigField("String", "TRIAL_URL", "\"https://pkastro.com/pnkastro_prod/new_registration_mobile_trial.php\"")
            buildConfigField("String", "ENV_NAME", "\"PROD\"")
            // Prod uses the normal app name
            resValue("string", "app_name", "\"PNKAstro\"")
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

    // NOTE: removed earlier androidComponents variant output logic (AGP API mismatch in Kotlin script).
}

// After APKs/bundles are produced, copy/rename them to a canonical format under build/outputs/dist.
// We copy (don't delete) to avoid issues with file locks during parallel processes and keep original artifacts intact.
tasks.register("collectAndRenameArtifacts") {
    doLast {
        val outputsRoot = layout.buildDirectory.dir("outputs").get().asFile
        val apkRoot = File(outputsRoot, "apk")
        val bundleRoot = File(outputsRoot, "bundle")
        val distDir = layout.buildDirectory.dir("outputs/dist").get().asFile
        if (!distDir.exists()) distDir.mkdirs()

        val brand = "PNKAstro"
        val collected = mutableListOf<File>()

        if (apkRoot.exists()) collected += apkRoot.walkTopDown().filter { it.isFile && it.extension.equals("apk", true) }.toList()
        if (bundleRoot.exists()) collected += bundleRoot.walkTopDown().filter { it.isFile && (it.extension.equals("aab", true) || it.extension.equals("apk", true)) }.toList()

        if (collected.isEmpty()) {
            println("collectAndRenameArtifacts: No APK/AAB files found under ${outputsRoot.absolutePath}")
            return@doLast
        }

        collected.forEach { file ->
            try {
                val parent = file.parentFile
                val grandParent = parent?.parentFile
                var flavor = "generic"
                var buildType = "unknown"

                if (parent != null && (parent.name.equals("debug", true) || parent.name.equals("release", true)
                            || parent.name.endsWith("Debug", true) || parent.name.endsWith("Release", true))) {
                    buildType = parent.name
                    flavor = grandParent?.name ?: "generic"
                } else {
                    val tokens = file.nameWithoutExtension.split('-').filter { it.isNotBlank() }
                    if (tokens.size >= 3 && tokens[0].equals("app", true)) {
                        flavor = tokens[1]
                        buildType = tokens[2]
                    } else if (tokens.size == 2 && tokens[0].equals("app", true)) {
                        buildType = tokens[1]
                    } else {
                        buildType = parent?.name ?: "unknown"
                        flavor = grandParent?.name ?: "generic"
                    }
                }

                val versionName = android.defaultConfig.versionName ?: "1.0"
                val ext = file.extension
                val newName = "${brand}-${flavor}-${buildType}-v${versionName}.${ext}"
                val target = File(distDir, newName)

                // Try moving (rename) first with retries - this will remove the original when successful
                var moved = false
                val maxAttempts = 5
                var attempt = 0
                while (!moved && attempt < maxAttempts) {
                    attempt++
                    try {
                        Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        println("collectAndRenameArtifacts: Moved ${file.relativeTo(project.rootDir)} -> ${target.relativeTo(project.rootDir)} (attempt $attempt)")
                        moved = true
                    } catch (e: Exception) {
                        println("collectAndRenameArtifacts: Move attempt $attempt failed for ${file.name}: ${e.message}")
                        try {
                            Thread.sleep(300L)
                        } catch (ie: InterruptedException) {
                            // ignore
                        }
                    }
                }

                if (!moved) {
                    // Fallback: copy then try to delete original
                    try {
                        file.copyTo(target, overwrite = true)
                        println("collectAndRenameArtifacts: Copied ${file.relativeTo(project.rootDir)} -> ${target.relativeTo(project.rootDir)}")
                        try {
                            if (file.delete()) {
                                println("collectAndRenameArtifacts: Deleted original ${file.relativeTo(project.rootDir)}")
                            } else {
                                println("collectAndRenameArtifacts: Could not delete original ${file.relativeTo(project.rootDir)} (may be locked)")
                            }
                        } catch (de: Exception) {
                            println("collectAndRenameArtifacts: Failed to delete original ${file.relativeTo(project.rootDir)}: ${de.message}")
                        }
                    } catch (ce: Exception) {
                        println("collectAndRenameArtifacts: Copy fallback failed for ${file.absolutePath}: ${ce.message}")
                    }
                }

            } catch (e: Exception) {
                println("collectAndRenameArtifacts: Error processing ${file.absolutePath}: ${e.message}")
            }
        }
    }
}

// Ensure the collect task runs after assemble, bundle, or package style tasks so it catches signed APKs/AABs
tasks.matching { task ->
    val name = task.name
    name.startsWith("assemble", true) || name.startsWith("bundle", true) || name.startsWith("package", true)
}.configureEach {
    finalizedBy(tasks.named("collectAndRenameArtifacts"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.google.play.services.location)
    implementation(libs.browser)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)


}