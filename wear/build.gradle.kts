import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

/** See the equivalent in the mobile module — absent for ordinary debug builds. */
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "inga.bpmetrics"
    compileSdk = 36

    defaultConfig {
        applicationId = "inga.bpmetrics"
        // Deliberately higher than the phone's 31, and they are allowed to differ because Play
        // takes them as two artifacts in one listing.
        //
        // The watch compiles clean at 31, but the foreground service type this app runs on —
        // `health` — is API 34, and lint cannot see that because the type is an inlined int
        // constant. Lowering this without giving that a fallback would produce a watch build that
        // installs on Wear OS 3 and then fails to hold a recording open, which is the exact class
        // of bug we have already spent a session chasing. Lower it once it has been given a
        // fallback and tested on a real older watch.
        minSdk = 34
        targetSdk = 36
        versionCode = appVersion.getProperty("versionBase").toInt() * 10 + 1
        versionName = appVersion.getProperty("versionName")
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // The watch must be signed with the same key as the phone, or Play will not pair them
            // as one app.
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // See the mobile module — symbols for whatever native code the bundle carries, so a
            // native crash is readable in the Play Console rather than a list of addresses.
            ndk {
                debugSymbolLevel = "FULL"
            }
        }

        /**
         * The watch half of the sideloadable beta.
         *
         * Must carry the same suffix as the phone module: the Data Layer routes between apps by
         * package name, so a beta phone app and a production watch app cannot see each other. They
         * pair as their own app, which is the intent — a beta pair and a Play pair coexisting on
         * the same devices without interfering.
         */
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta.${System.getenv("GITHUB_RUN_NUMBER") ?: "local"}"
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.play.services.wearable)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.wear.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.runtime)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.health.services)
    implementation(libs.guava)
    implementation(libs.concurrent.futures)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.gson)
    implementation(libs.androidx.wear.ongoing)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}