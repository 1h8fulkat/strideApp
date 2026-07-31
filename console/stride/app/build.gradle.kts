import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Broker credentials come from local.properties, which git ignores, rather than
// from constants in the source. A fresh clone therefore builds an app that
// cannot reach the broker until someone supplies them — which is the correct
// failure, and much better than shipping a working password to anyone who
// clones the repo.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(key: String, fallback: String = "") =
    "\"${localProps.getProperty(key, fallback)}\""

android {
    namespace = "dev.stride.hud"
    compileSdk = 34

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "dev.stride.hud"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "0.1-spike"

        buildConfigField("String", "MQTT_BROKER", secret("mqtt.broker", "tcp://192.168.0.10:1883"))
        buildConfigField("String", "MQTT_USER", secret("mqtt.user"))
        buildConfigField("String", "MQTT_PASS", secret("mqtt.pass"))
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Plain Java Paho client — the Android "service" variant needs AndroidX,
    // which this app deliberately avoids.
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
