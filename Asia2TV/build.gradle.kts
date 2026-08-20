plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    compileSdk = 33
    defaultConfig {
        minSdk = 21
    }
}

cloudstream {
    setRepoUrl("https://drivesalem307.github.io/RunnerCloudStreamNew")
}
