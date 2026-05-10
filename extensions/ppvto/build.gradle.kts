plugins {
    id 'com.android.library'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.ppvto.api'
    compileSdk 36

    defaultConfig {
        minSdk 21
        targetSdk 36
    }
}

dependencies {
    implementation 'com.lagradost:cloudstream3:pre-release'
}