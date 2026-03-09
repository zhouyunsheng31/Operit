plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ai.assistance.quickjs"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
            }
        }

        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.kotlinx.serialization)
}
