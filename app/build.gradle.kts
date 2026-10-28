plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.buildwclaude.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.buildwclaude.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        // A fixed key checked into the repo, so every CI build installs over the previous one.
        // CI otherwise generates a fresh debug key per run and Android refuses the update.
        create("shared") {
            storeFile = file("companion-debug.p12")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "companion"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
        disable += setOf(
            "MissingTranslation",
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable",
            "SetTextI18n",
            "HardcodedText",
            // USE_EXACT_ALARM is a Play Store policy question (alarm/reminder apps); this app is sideloaded
            "ExactAlarm",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
