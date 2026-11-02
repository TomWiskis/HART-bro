plugins { id("com.android.library") }
android {
    namespace = "com.hoho.android.usbserial"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 29 }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation("androidx.annotation:annotation:1.9.1"); implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.21") }
