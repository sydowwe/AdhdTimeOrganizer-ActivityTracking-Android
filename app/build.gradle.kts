import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

// The release key lives outside the repo. `keystore.properties` beside settings.gradle.kts (gitignored) names it, or the
// RELEASE_* env vars do; with neither, the release build comes out unsigned and everything else still builds.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun releaseSigning(property: String, env: String): String? =
    keystoreProperties.getProperty(property)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }

/**
 * Which servers a build type talks to (decision 25-1): the API, the web app that goes with it (its own origin), the
 * server's **web** OAuth client id for "Sign in with Google" (blank hides the button), and the Digital Asset Link to that
 * web app's saved sign-ins (the manifest's `asset_statements`). One call sets all four, so they can't drift apart; the
 * code reads them as `BuildConfig.*` through `Constants`. `scripts/prod-release.ps1` refuses a release that names the test
 * server.
 */
fun com.android.build.api.dsl.ApplicationBuildType.environment(
    serverRoot: String,
    webAppRoot: String,
    googleServerClientId: String,
) {
    buildConfigField("String", "SERVER_ROOT", "\"$serverRoot\"")
    buildConfigField("String", "WEB_APP_ROOT", "\"$webAppRoot\"")
    buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"$googleServerClientId\"")
    // A strings resource drops unescaped double quotes: the JSON's are written as \" here.
    resValue("string", "asset_statements", "[{\\\"include\\\": \\\"${webAppRoot}.well-known/assetlinks.json\\\"}]")
}

android {
    namespace = "com.timeorganizer.usagetracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.timeorganizer.usagetracker"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val storePath = releaseSigning("storeFile", "RELEASE_STORE_FILE")
        if (storePath != null && !file(storePath).exists()) {
            logger.warn("Release signing: $storePath does not exist; the release build will be unsigned.")
        }
        if (storePath != null && file(storePath).exists()) {
            create("release") {
                storeFile = file(storePath)
                storePassword = releaseSigning("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigning("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = releaseSigning("keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // The test server; test-release installs this build on the owner's phone.
            environment(
                serverRoot = "https://test.backend.adhdtimeorganizer.com/",
                webAppRoot = "https://test.adhdtimeorganizer.com/",
                googleServerClientId = "579844911566-22rdpksfphokkmjp7ttac03r2qc5feg4.apps.googleusercontent.com",
            )
            // A local server from the emulator (cleartext is allowed to it in debug only): this line instead of the above.
            // environment(serverRoot = "http://10.0.2.2:8081/", webAppRoot = "https://10.0.2.2:3001/", googleServerClientId = "")
        }
        release {
            // Production. The Google button stays hidden until the production server's web client id is filled in.
            environment(
                serverRoot = "https://backend.adhdtimeorganizer.com/",
                webAppRoot = "https://adhdtimeorganizer.com/",
                googleServerClientId = "",
            )
            // Only when the key is configured: a checkout without it still builds, unsigned.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        buildConfig = true
        // environment(...)'s asset_statements string.
        resValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

ksp {
    // The outbox schema is checked in so a later version can write a real migration against it.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
