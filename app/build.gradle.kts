plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

android {
    namespace = "dev.pearlwallet"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.pearlwallet"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
        // Only the English wordlist ships; drop every other locale from androidx libs.
        resourceConfigurations += listOf("en", "in")
    }

    signingConfigs {
        create("release") {
            // Credentials come from the environment or local.properties, never from source.
            // Committing a keystore password means anyone can sign an APK that Android will
            // accept as a legitimate update to this wallet.
            val props = Properties().apply {
                val f = rootProject.file("local.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            fun cred(key: String, env: String): String? =
                props.getProperty(key) ?: System.getenv(env)

            val storePath = cred("pearl.storeFile", "PEARL_STORE_FILE")
            val storePass = cred("pearl.storePassword", "PEARL_STORE_PASSWORD")
            val keyPass = cred("pearl.keyPassword", "PEARL_KEY_PASSWORD")
            val alias = cred("pearl.keyAlias", "PEARL_KEY_ALIAS") ?: "pearl"

            if (storePath != null && storePass != null) {
                // resolve against the repo root, so the same value works from any module
                storeFile = rootProject.file(storePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass ?: storePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Only wire up signing when a keystore is actually configured, so a clean
            // clone still builds (unsigned) without the operator's private key.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Same shrinking as release so the APK people actually install is small,
            // but keep the debug key so it stays sideloadable.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "META-INF/*.version", "kotlin/**", "DebugProbesKt.bin", "okhttp3/**"
        )
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        // Hardcoded strings in a single-language app are not defects.
        disable += setOf("HardcodedText", "SetTextI18n", "Autofill", "LabelFor",
            "ContentDescription", "UnusedResources", "GradleDependency", "Overdraw")
        abortOnError = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
