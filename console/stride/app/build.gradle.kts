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
        minSdk = 24
        targetSdk = 28
        // Bumped per release, because the About screen is the only place
        // anyone can see which build is on a console, and "0.1-spike · build 1"
        // was still being shown four months and one published repo later.
        versionCode = 4
        versionName = "0.8.0"

        buildConfigField("String", "MQTT_BROKER", secret("mqtt.broker", ""))
        buildConfigField("String", "MQTT_USER", secret("mqtt.user"))
        buildConfigField("String", "MQTT_PASS", secret("mqtt.pass"))

        // Where the console reads its routes from, for the same reason and
        // with the same caveat as the three above: a seed for first run, not
        // the source — see Settings.seedRoutesFromBuildConfig.
        //
        // `ha.token` deserves more care than the broker password, not less. A
        // long-lived access token is the whole Home Assistant API, not one
        // topic on one broker, and an APK carrying one hands that to whoever
        // has the APK. It is here because the alternative is typing 180
        // characters on a treadmill's on-screen keyboard, and the release
        // workflow refuses to build with either key present.
        buildConfigField("String", "HA_URL", secret("ha.url"))
        buildConfigField("String", "HA_TOKEN", secret("ha.token"))
    }
    /*
     * A release build is what belongs on a treadmill.
     *
     * A debug build carries `android:debuggable="true"`, which lets anything
     * else on the console attach to this process and read its store — the
     * broker password included — and it is what turns on the WebView DevTools
     * socket in MainActivity. Neither is a risk worth taking for a machine
     * that sits in a hallway for years.
     *
     * The signing key is yours and is never in this repo. Generate one:
     *
     *     keytool -genkey -v -keystore ~/stride-release.jks -alias stride \
     *             -keyalg RSA -keysize 4096 -validity 10000
     *
     * then name it in local.properties, alongside the broker credentials:
     *
     *     stride.keystore=/Users/you/stride-release.jks
     *     stride.keystore.password=...
     *     stride.key.alias=stride
     *     stride.key.password=...
     *
     * Without those the release variant gets no signing config at all, and
     * Gradle produces an unsigned APK that adb refuses to install. That is the
     * correct failure: it names what is missing, where a build that quietly
     * fell back to the debug key would ship the thing we are avoiding.
     */
    val keystorePath = localProps.getProperty("stride.keystore", "")
    signingConfigs {
        if (keystorePath.isNotEmpty() && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = localProps.getProperty("stride.keystore.password", "")
                keyAlias = localProps.getProperty("stride.key.alias", "stride")
                keyPassword = localProps.getProperty("stride.key.password", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    lint {
        /*
         * One check, off, with a reason.
         *
         * ExpiredTargetSdkVersion is a Google Play policy: apps published there
         * must target API 33 or higher. This one is installed with adb onto a
         * treadmill console running Android 9 and can never be a Play app, so
         * the policy does not apply — and targetSdk 28 is load-bearing rather
         * than neglect. BootReceiver starts an activity from a background
         * broadcast, which API 29 closed; the Bluetooth permissions in the
         * manifest are the pre-31 set. Raising it would break the console.
         *
         * Everything else lint considers fatal still fails the release build,
         * which is the point of leaving the rest on.
         */
        disable += "ExpiredTargetSdkVersion"
    }
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

    // JVM unit tests only — never packaged. There is exactly one thing here
    // worth testing off the device: Gpx, which is a port of the conversion
    // half of homeassistant/stride_gpx.py and has to agree with it about what
    // gradient the deck drives. GpxTest checks that against three real routes
    // and the script's own output. Everything else in this app needs a board,
    // a belt or a WebView, and is checked by tools/ui-test.sh or by walking.
    testImplementation("junit:junit:4.13.2")

    // A *real* org.json for the JVM tests.
    //
    // The android.jar on the unit-test classpath is the "mockable" one, whose
    // every method throws "not mocked" — so anything touching JSONObject is
    // untestable off the device without this. It is testImplementation only:
    // the app keeps using the platform's own copy, which is the one that will
    // actually run, and nothing extra is packaged.
    //
    // Gpx.json() builds its payload by hand and so needs none of this, but for
    // a different reason worth not confusing with this one — it needs control
    // of the decimal scale of every number.
    testImplementation("org.json:json:20240303")
}
