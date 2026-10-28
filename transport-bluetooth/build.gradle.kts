plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "ru.hartbro.bluetooth"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":hart-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
