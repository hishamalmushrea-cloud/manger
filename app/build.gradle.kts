import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile
import java.util.zip.ZipException

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
  alias(libs.plugins.hilt)
}

android {
  namespace = "com.example"
  // Using API 36.0 (stable) so the APK builds in any CI/local environment
  // that has the standard "platforms;android-36" SDK package installed.
  // You can switch back to `release(36) { minorApiLevel = 1 }` once the
  // Android 16.1 (API 36.1) SDK platform is installed on your machine.
  compileSdk { version = release(36) }

  defaultConfig {
    applicationId = "com.aistudio.heymanager.xyzk"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Local dev: the repo has no debug.keystore (CI generates it). Fall back
      // to AGP's default ~/.android/debug.keystore (auto-created) so a plain
      // Android Studio "Run ▶" works out of the box with zero setup.
      if (rootProject.file("debug.keystore").exists()) {
        signingConfig = signingConfigs.getByName("debugConfig")
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))

  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)

  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  implementation(libs.androidx.room.ktx)
  implementation(libs.hilt.android)
  "ksp"(libs.hilt.compiler)
  implementation(libs.androidx.hilt.navigation.compose)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.hilt.work)
  "ksp"(libs.androidx.hilt.compiler)
  implementation(libs.timber)
  implementation(libs.vosk)
  implementation(libs.accompanist.permissions)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.datastore.preferences)

  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Firebase Auth with Google Sign-In requires all of the following to be uncommented together.
  // If you are using Firebase Auth with other providers (e.g. Email/Password), you may only need
  // firebase-auth.
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}

// ---------------------------------------------------------------------------
// Vosk offline wake-word model bundling.
// Downloads the small Vosk model ONCE at build time and packs it into
// app/src/main/assets so the app runs 100% offline (no internet, no API keys).
// The marker file ".downloaded" prevents re-downloading on every build.
// ---------------------------------------------------------------------------

val voskModelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
val voskModelAssets = File(projectDir, "src/main/assets/vosk-model-en-base")
val voskModelZip = layout.buildDirectory.file("downloads/vosk-model.zip").get().asFile
val voskModelMarker = File(voskModelAssets, ".downloaded")

// A truncated download (dropped connection) lacks the zip END header — ZipFile
// open then fails with "zip END header not found". Detect that state so the
// build re-downloads instead of failing on the corrupt file forever.
fun isCompleteZip(file: File): Boolean {
    if (!file.exists() || file.length() < 1024L) return false
    return try {
        ZipFile(file).use { it.entries().asSequence().any() }
    } catch (e: ZipException) {
        false
    }
}

tasks.register("downloadVoskModel") {
    // This task performs raw network I/O with script-captured state, which the
    // configuration cache cannot serialize — mark it incompatible instead.
    notCompatibleWithConfigurationCache("Downloads Vosk model over the network before asset packaging")
    group = "download"
    description = "Downloads the offline Vosk wake-word model"
    outputs.file(voskModelZip)
    // Skip only when extraction already finished, or the existing zip is
    // verifiably complete. An interrupted earlier attempt leaves a truncated
    // zip behind which must be re-downloaded.
    onlyIf { !voskModelMarker.exists() && !isCompleteZip(voskModelZip) }
    doLast {
        voskModelZip.parentFile?.mkdirs()
        val staged = File(voskModelZip.parentFile, voskModelZip.name + ".part")
        staged.delete()
        println("Downloading Vosk wake-word model (~40MB) from $voskModelUrl ...")
        val connection = URL(voskModelUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            throw org.gradle.api.GradleException("Model download failed with HTTP $code")
        }
        connection.inputStream.use { input ->
            staged.outputStream().buffered().use { out -> input.copyTo(out) }
        }
        if (!isCompleteZip(staged)) {
            staged.delete()
            throw org.gradle.api.GradleException(
                "Vosk model download was incomplete (connection dropped). Re-run the build to retry."
            )
        }
        if (voskModelZip.exists()) voskModelZip.delete()
        if (!staged.renameTo(voskModelZip)) {
            staged.copyTo(voskModelZip, overwrite = true)
            staged.delete()
        }
        println("Vosk model downloaded: ${voskModelZip.length() / 1_000_000} MB")
    }
}

tasks.register("extractVoskModel") {
    // Raw ZipFile extraction with script-captured state — see downloadVoskModel.
    notCompatibleWithConfigurationCache("Extracts Vosk model zip into app assets before packaging")
    group = "download"
    description = "Unpacks the Vosk model into app assets"
    dependsOn("downloadVoskModel")
    onlyIf { !voskModelMarker.exists() }
    doLast {
        val zip = ZipFile(voskModelZip)
        zip.use { z ->
            z.entries().asSequence()
                .filter { !it.isDirectory }
                .forEach { entry ->
                    // The zip contains a single top-level folder; flatten it away.
                    val relativePath = entry.name.substringAfter("/", "")
                    if (relativePath.isNotBlank()) {
                        val target = File(voskModelAssets, relativePath)
                        target.parentFile?.mkdirs()
                        z.getInputStream(entry).use { input ->
                            target.outputStream().buffered().use { out -> input.copyTo(out) }
                        }
                    }
                }
        }
        voskModelMarker.writeText("1")
        println("Vosk model extracted into $voskModelAssets")
    }
}

// --- Arabic command-recognition model (fully offline speech pipeline) -----------
// Commands used to go to android.speech.SpeechRecognizer (Google, needs
// internet). We now bundle an Arabic Vosk model so the ENTIRE voice pipeline —
// wake word AND command capture — works with no internet and no API keys.
val voskArabicModelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ar-tn-0.1-linto.zip"
val voskArabicModelAssets = File(projectDir, "src/main/assets/vosk-model-ar-small")
val voskArabicModelZip = layout.buildDirectory.file("downloads/vosk-model-ar.zip").get().asFile
val voskArabicModelMarker = File(voskArabicModelAssets, ".downloaded")

tasks.register("downloadVoskArabicModel") {
    notCompatibleWithConfigurationCache("Downloads the Arabic Vosk model over the network before asset packaging")
    group = "download"
    description = "Downloads the offline Arabic Vosk command-recognition model (~158MB)"
    outputs.file(voskArabicModelZip)
    onlyIf { !voskArabicModelMarker.exists() && !isCompleteZip(voskArabicModelZip) }
    doLast {
        voskArabicModelZip.parentFile?.mkdirs()
        val staged = File(voskArabicModelZip.parentFile, voskArabicModelZip.name + ".part")
        staged.delete()
        println("Downloading Vosk Arabic command model (~158MB) from $voskArabicModelUrl ...")
        val connection = URL(voskArabicModelUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            throw org.gradle.api.GradleException("Arabic model download failed with HTTP $code")
        }
        connection.inputStream.use { input ->
            staged.outputStream().buffered().use { out -> input.copyTo(out) }
        }
        if (!isCompleteZip(staged)) {
            staged.delete()
            throw org.gradle.api.GradleException(
                "Arabic model download was incomplete (connection dropped). Re-run the build to retry."
            )
        }
        if (voskArabicModelZip.exists()) voskArabicModelZip.delete()
        if (!staged.renameTo(voskArabicModelZip)) {
            staged.copyTo(voskArabicModelZip, overwrite = true)
            staged.delete()
        }
        println("Vosk Arabic model downloaded: ${voskArabicModelZip.length() / 1_000_000} MB")
    }
}

tasks.register("extractVoskArabicModel") {
    notCompatibleWithConfigurationCache("Extracts the Arabic Vosk model zip into app assets before packaging")
    group = "download"
    description = "Unpacks the Arabic Vosk model into app assets"
    dependsOn("downloadVoskArabicModel")
    onlyIf { !voskArabicModelMarker.exists() }
    doLast {
        val zip = ZipFile(voskArabicModelZip)
        zip.use { z ->
            z.entries().asSequence()
                .filter { !it.isDirectory }
                .forEach { entry ->
                    // Same layout as the English model: single top-level folder, flatten it away.
                    val relativePath = entry.name.substringAfter("/", "")
                    if (relativePath.isNotBlank()) {
                        val target = File(voskArabicModelAssets, relativePath)
                        target.parentFile?.mkdirs()
                        z.getInputStream(entry).use { input ->
                            target.outputStream().buffered().use { out -> input.copyTo(out) }
                        }
                    }
                }
        }
        voskArabicModelMarker.writeText("1")
        println("Vosk Arabic model extracted into $voskArabicModelAssets")
    }
}

// Hook both models into all build types without hard task-name lookups.
tasks.matching { it.name == "preDebugBuild" || it.name == "preReleaseBuild" }
    .configureEach {
        dependsOn("extractVoskModel")
        dependsOn("extractVoskArabicModel")
    }

// --- Arabic-Indic digit auto-fix for generated sources -------------------------
// On machines whose OS display language is Arabic, locale-sensitive code
// generators (Room/KSP via String.format without a pinned locale) can emit
// Eastern Arabic numerals (e.g. "_argIndex = \u0662" instead of "2") into
// generated .kt files. The Kotlin compiler then fails with errors like
// "Expecting an expression" INSIDE generated code only. gradle.properties pins
// the daemon locale (prevention), and this task is the safety net: it rewrites
// any non-ASCII digit (and the Arabic decimal mark U+066B) in build/generated
// back to ASCII right after KSP and before Kotlin compilation.
fun asciiDigitFor(c: Char): Char? = when (c) {
    in '\u0660'..'\u0669' -> '0' + (c - '\u0660') // Arabic-Indic digits
    in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0') // Extended Arabic-Indic (Persian)
    '\u066B' -> '.' // Arabic decimal separator
    else -> null
}

val sanitizeArabicDigitsTask = tasks.register("sanitizeArabicDigitsInKspOutput") {
    notCompatibleWithConfigurationCache("Rewrites locale-dependent digits in generated sources to ASCII")
    group = "verification"
    description = "Converts Arabic-Indic digits in generated KSP/Room sources to ASCII digits"
    onlyIf { layout.buildDirectory.dir("generated").get().asFile.isDirectory }
    doLast {
        val generatedRoot = layout.buildDirectory.dir("generated").get().asFile
        var filesFixed = 0
        var digitsFixed = 0
        generatedRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val original = f.readText(Charsets.UTF_8)
                var count = 0
                val sb = StringBuilder(original.length)
                for (c in original) {
                    val mapped = asciiDigitFor(c)
                    if (mapped != null) {
                        sb.append(mapped)
                        count++
                    } else {
                        sb.append(c)
                    }
                }
                if (count > 0) {
                    f.writeText(sb.toString(), Charsets.UTF_8)
                    filesFixed++
                    digitsFixed += count
                }
            }
        if (filesFixed > 0) {
            println("sanitizeArabicDigitsInKspOutput: normalized $digitsFixed non-ASCII digits in $filesFixed generated file(s) - OS locale was formatting numbers in Arabic")
        }
    }
}

// Order: strictly after every KSP task, strictly before Kotlin compilation.
sanitizeArabicDigitsTask.configure { mustRunAfter(tasks.matching { it.name.startsWith("ksp") }) }
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }
    .configureEach { dependsOn(sanitizeArabicDigitsTask) }
