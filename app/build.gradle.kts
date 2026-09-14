plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

import java.util.Properties
import java.security.MessageDigest
import java.util.Base64

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

val allAbis = listOf("x86")
val generatedK00eJniRoot = layout.buildDirectory.dir("generated/k00eJniLibs").get().asFile

android {
    namespace = "io.github.jqssun.airplay"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    if (localProps.containsKey("storeFile")) {
        signingConfigs {
            create("release") {
                storeFile = file(localProps.getProperty("storeFile"))
                storePassword = localProps.getProperty("storePassword")
                keyAlias = localProps.getProperty("keyAlias")
                keyPassword = localProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.c3media.dashboard"
        minSdk = 21
        targetSdk = 28
        versionCode = 10823
        versionName = "1.8.23"
    }

    buildTypes {
        debug {
            ndk { abiFilters += allAbis }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            ndk { abiFilters += allAbis }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        prefab = true
    }

    lint {
        disable += setOf("ExpiredTargetSdkVersion", "ChromeOsAbiSupport")
    }

    testOptions.unitTests.all {
        it.testLogging {
            showStandardStreams = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    sourceSets["main"].apply {
        manifest.srcFile("src/lite/AndroidManifest.xml")
        java.setSrcDirs(listOf("src/lite/kotlin"))
        res.setSrcDirs(listOf("src/lite/res"))
        jniLibs.setSrcDirs(listOf(generatedK00eJniRoot))
    }
}

kotlin {
    sourceSets.getByName("main").kotlin.setSrcDirs(listOf("src/lite/kotlin"))
}

val verifiedK00eNativeHashes = mapOf(
    "libairplay_native.so" to "327b381a2719aaa176f70a69d231e3c4671357c4cb0c87be74b99eb183c3e5b5",
    "libc++_shared.so" to "649cf75deda40f5985f316d1cc63cba59466db453e610a14e983e36be0caaa94",
    "liboboe.so" to "5541263e80ba3a4471a1372d08d1dd72fc8378d6a90deb598bf07525656c23eb",
)

val prepareK00eNativeStack = tasks.register("prepareK00eNativeStack") {
    val encodedRoot = file("src/lite/native-prebuilt/x86")
    inputs.dir(encodedRoot)
    outputs.dir(generatedK00eJniRoot)
    doLast {
        val targetRoot = generatedK00eJniRoot.resolve("x86").apply { mkdirs() }
        verifiedK00eNativeHashes.keys.forEach { name ->
            val parts = encodedRoot.listFiles { file ->
                file.name.startsWith("$name.b64.")
            }.orEmpty().sortedBy { it.name }
            check(parts.isNotEmpty()) { "Missing encoded K00E native binary: $name" }
            val encoded = buildString {
                parts.forEach { append(it.readText(Charsets.US_ASCII)) }
            }
            targetRoot.resolve(name).writeBytes(Base64.getDecoder().decode(encoded))
        }
    }
}

val verifyK00eNativeStack = tasks.register("verifyK00eNativeStack") {
    dependsOn(prepareK00eNativeStack)
    doLast {
        verifiedK00eNativeHashes.forEach { (name, expected) ->
            val binary = generatedK00eJniRoot.resolve("x86/$name")
            check(binary.isFile) { "Missing verified K00E native binary: $name" }
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(binary.readBytes())
                .joinToString("") { "%02x".format(it) }
            check(actual == expected) {
                "Unexpected K00E native binary $name: $actual"
            }
        }
    }
}

tasks.named("preBuild").configure { dependsOn(verifyK00eNativeStack) }

tasks.withType<Zip>().configureEach {
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

dependencies {
    testImplementation(libs.junit)
}
