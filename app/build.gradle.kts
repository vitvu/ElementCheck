plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.vvtool.elementcheck"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.vvtool.elementcheck"
        minSdk = 23
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            // Signed with the debug key so the release APK installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
