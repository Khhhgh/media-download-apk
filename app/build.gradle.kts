import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.io.File

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
  alias(libs.plugins.chaquopy)
}

// Host Python interpreter used by Chaquopy to build the on-device Python runtime.
// It must match the app's Python major.minor version (see `chaquopy.defaultConfig.version`).
// Override with -Pchaquopy.buildPython=/path/to/python or the CHAQUOPY_BUILD_PYTHON env var.
val chaquopyBuildPython: String = run {
  val override = (findProperty("chaquopy.buildPython") as String?)
    ?: System.getenv("CHAQUOPY_BUILD_PYTHON")
  if (!override.isNullOrBlank()) {
    override
  } else {
    val candidates = listOf(
      "/usr/bin/python3.12",
      "/usr/local/bin/python3.12",
      "/opt/hostedtoolcache/Python/3.12.0/x64/bin/python3.12",
      "/usr/bin/python3",
    )
    candidates.firstOrNull { File(it).isFile } ?: "python3.12"
  }
}

// The downapp/ engine sources are the single source of truth. They are copied into the APK
// as assets/engines/ so that the first-run setup can install them without any network access.
val downappAssetsDir: java.io.File = layout.buildDirectory.dir("generated/downappAssets").get().asFile
val copyDownappEngines = tasks.register<Copy>("copyDownappEngines") {
  from(rootProject.layout.projectDirectory.dir("downapp"))
  into(downappAssetsDir.resolve("engines"))
  exclude("**/__pycache__/**", "**/*.pyc")
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.mediadownload.app.v12"
    minSdk = 24
    targetSdk = 36
    versionCode = 15
    versionName = "1.0.14"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      // Restrict packaged native ABIs (including the Chaquopy Python runtime) to the
      // architectures the app actually ships. Python 3.12+ only supports 64-bit ABIs.
      abiFilters += listOf("arm64-v8a", "x86_64")
    }
  }

  // Auto-generate debug.keystore if missing on clean CI clones
  val debugKeystoreFile = file("${rootDir}/debug.keystore")
  if (!debugKeystoreFile.exists()) {
    try {
      ProcessBuilder(
        "keytool", "-genkeypair",
        "-alias", "androiddebugkey",
        "-keypass", "android",
        "-keystore", debugKeystoreFile.absolutePath,
        "-storepass", "android",
        "-dname", "CN=Android Debug,O=Android,C=US",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000"
      ).start().waitFor()
    } catch (_: Exception) {}
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val customKeystore = System.getenv("KEYSTORE_PATH")
      if (customKeystore != null && file(customKeystore).exists()) {
        storeFile = file(customKeystore)
        storePassword = System.getenv("STORE_PASSWORD") ?: "android"
        keyAlias = System.getenv("KEY_ALIAS") ?: "androiddebugkey"
        keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
      } else {
        storeFile = file("${rootDir}/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
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
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
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
  sourceSets {
    getByName("main") {
      // Generated location that receives the downapp/ engine tree as assets/engines/.
      assets.srcDir(downappAssetsDir)
    }
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

chaquopy {
  defaultConfig {
    version = "3.12"
    buildPython(chaquopyBuildPython)
    // Platform engines rely exclusively on the Python standard library (urllib, json, re),
    // so no third-party pip packages are installed at build time.
  }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
  dependsOn(copyDownappEngines)
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
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
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.coil.compose)
  // implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.converter.moshi)
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
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  compilerOptions {
    allWarningsAsErrors.set(false)
    freeCompilerArgs.add("-Xannotation-default-target=param-property")
  }
}
