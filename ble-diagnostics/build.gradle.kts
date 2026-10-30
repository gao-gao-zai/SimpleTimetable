plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.ggz.diagnostics"
    compileSdk = 35
    defaultConfig { applicationId = "com.ggz.diagnostics"; minSdk = 26; targetSdk = 35; versionCode = 3; versionName = "1.0.3" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2") }
