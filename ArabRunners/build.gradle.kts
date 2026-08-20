plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    compileSdk = 33
    defaultConfig {
        minSdk = 21
        targetSdk = 33
    }
}

cloudstream {
    setRepoUrl("https://raw.githubusercontent.com/drivesalem307/RunnerCloudStreamNew/builds/")
}
