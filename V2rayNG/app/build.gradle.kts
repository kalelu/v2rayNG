plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.jaredsburrows.license")
}

val releaseSigningEnvironmentVariables = listOf(
    "JAPAN_PROXY_KEYSTORE_PATH",
    "JAPAN_PROXY_KEYSTORE_PASSWORD",
    "JAPAN_PROXY_KEY_ALIAS",
    "JAPAN_PROXY_KEY_PASSWORD"
)
val releaseSigningValues = releaseSigningEnvironmentVariables.associateWith { variable ->
    providers.environmentVariable(variable).orNull?.takeIf { it.isNotBlank() }
}
val missingReleaseSigningVariables = releaseSigningValues
    .filterValues { it == null }
    .keys
val hasReleaseSigningConfiguration = missingReleaseSigningVariables.isEmpty()

android {
    namespace = "com.v2ray.ang"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.kalelu.japanproxy"
        minSdk = 24
        targetSdk = 37
        versionCode = 10000
        versionName = "1.0.0"

        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a")
                isUniversalApk = false
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigningConfiguration) {
            create("release") {
                storeFile = file(releaseSigningValues.getValue("JAPAN_PROXY_KEYSTORE_PATH")!!)
                storeType = "PKCS12"
                storePassword = releaseSigningValues.getValue("JAPAN_PROXY_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("JAPAN_PROXY_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("JAPAN_PROXY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigningConfiguration) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
            buildConfigField("String", "DISTRIBUTION", "\"F-Droid\"")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"Play Store\"")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applicationVariants.all {
        val variant = this
        val isFdroid = variant.productFlavors.any { it.name == "fdroid" }
        if (isFdroid) {
            val versionCodes =
                mapOf(
                    "armeabi-v7a" to 2, "arm64-v8a" to 1, "x86" to 4, "x86_64" to 3, "universal" to 0
                )

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = output.getFilter("ABI") ?: "universal"
                    output.outputFileName = "JapanProxy_${variant.versionName}-fdroid_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (100 * variant.versionCode + versionCodes[abi]!!).plus(5000000)
                    } else {
                        return@forEach
                    }
                }
        } else {
            val versionCodes =
                mapOf("armeabi-v7a" to 4, "arm64-v8a" to 4, "x86" to 4, "x86_64" to 4, "universal" to 4)

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = if (output.getFilter("ABI") != null)
                        output.getFilter("ABI")
                    else
                        "universal"

                    output.outputFileName = "JapanProxy_${variant.versionName}_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (1000000 * versionCodes[abi]!!).plus(variant.versionCode)
                    } else {
                        return@forEach
                    }
                }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf(
            "en",
            "zh-rCN",
            "zh-rTW",
            "vi",
            "ru",
            "fa",
            "ar",
            "bn",
            "bqi-rIR"
        )
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

if (!hasReleaseSigningConfiguration) {
    val appProjectPath = project.path
    val releaseArtifactTaskPrefixes = listOf("assemble", "bundle", "install", "package")

    gradle.taskGraph.whenReady {
        val releaseArtifactRequested = allTasks.any { task ->
            task.path.startsWith("$appProjectPath:") &&
                task.name.contains("Release") &&
                releaseArtifactTaskPrefixes.any(task.name::startsWith)
        }

        if (releaseArtifactRequested) {
            throw GradleException(
                "Release signing is not configured. Set all required environment variables: " +
                    missingReleaseSigningVariables.sorted().joinToString(", ")
            )
        }
    }
}

dependencies {
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose Libraries
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // QR Code: CameraX + ZXing
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.compose)
    implementation(libs.core) // zxing core

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)

    // Reorderable list
    implementation(libs.reorderable)

    // Testing Libraries
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
