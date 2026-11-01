plugins {
    id("com.android.test")
}

android {
    namespace = "com.labsage.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Target the app under test; Macrobenchmark runs against installed builds.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        // The benchmark build type installs alongside a release/dark-launch
        // build of :app; it must stay debuggable and debug-signed.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks.add("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.benchmark.macro)
    implementation(libs.benchmark.junit4)
    implementation(libs.androidx.uiautomator)
}
