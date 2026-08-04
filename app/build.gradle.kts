// FILE: app/build.gradle.kts
//
// Graffux — the standalone multi-layer image editor (sketching & photo editing). It hosts the
// shared :feature:editor and its core modules, which are ALSO consumed by GraffitiXR (the AR mural
// app) so the editor stays a single source of truth. No AR, SLAM session, or co-op here.

// FILE: app/build.gradle.kts
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.java
import org.gradle.kotlin.dsl.kotlin
import org.gradle.kotlin.dsl.setProperty
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlinx.serialization)
}

// Load version properties
val versionPropsFile = project.rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { load(it) }
    }
}

// google-services.json is never committed (see .gitignore) — CI writes it from the GOOGLE_SERVICES
// secret before this build runs (see the "Inject Google Services" step in the workflows), and local
// dev needs its own copy from the Firebase console. The google-services plugin hard-fails
// configuration when the file is missing, so only apply it when the file is actually there —
// otherwise every build without Firebase creds configured (fresh checkouts, fork PRs) would break.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Load version properties
val versionPropsFile = project.rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { load(it) }
    }
}

// Load local properties
val localProperties = Properties().apply {
    val localPropertiesFile = project.rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

// Version resolution. On EVERY compile (any build type, any machine, any Gradle task that will
// actually compile bytecode) both the build number and the patch are incremented:
//   - versionBuild  -> the Android versionCode. Monotonic; NEVER resets.
//   - versionPatch  -> the patch segment of the versionName. Increments each compile, but resets to
//                      0 when versionMinor was bumped since the last build (a new minor starts at .0).
//                      versionMinorLast tracks the minor we last built so that reset is automatic.
// True when the requested tasks will trigger real compilation — not a sync, `tasks`, `clean`,
// a `--dry-run`, or a diagnostic like `buildEnvironment`/`buildHealth`. Build verbs cover every
// entry point that transitively invokes a KotlinCompile / JavaCompile task on this project: the
// full android build lifecycle (assemble/bundle/install/package), explicit compile invocations,
// unit-test / instrumented-test / verification tasks (test/check/lint/verify/connectedTest — all
// depend on compileDebugKotlin / compileReleaseKotlin), and `run` for library modules. Verbs are
// matched as a prefix on the leaf task name and the `build` lifecycle task is matched exactly, so
// diagnostics that merely contain "build" don't trip it.
val startParameter = gradle.startParameter
val buildVerbs = listOf(
    "assemble", "bundle", "install", "package", "compile",
    "test", "check", "lint", "verify", "connected", "run",
)
val isBuilding = !startParameter.isDryRun && startParameter.taskNames.any { taskName ->
    val task = taskName.substringAfterLast(':').lowercase()
    task == "build" || buildVerbs.any { task.startsWith(it) }
}

val verMajor = versionProps.getProperty("versionMajor", "1")
val verMinor = versionProps.getProperty("versionMinor", "0")
// Detect a minor bump BEFORE the build-gated block so the reset also applies to CI/override builds
// (and IDE syncs), where the block is skipped: a new minor always reads as patch 0 even if the file
// still holds the previous minor's patch (it may not have been rewritten by a local build yet).
val lastMinor = versionProps.getProperty("versionMinorLast", verMinor)
val isMinorBumped = verMinor != lastMinor

var currentVersionCode = versionProps.getProperty("versionBuild", "1").toInt()
var currentPatch = if (isMinorBumped) 0 else versionProps.getProperty("versionPatch", "0").toInt()

if (isBuilding) {
    currentVersionCode++ // build never resets
    // A minor bump makes this build the new minor's .0; otherwise advance the patch.
    if (!isMinorBumped) currentPatch++

    versionProps.setProperty("versionBuild", currentVersionCode.toString())
    versionProps.setProperty("versionPatch", currentPatch.toString())
    versionProps.setProperty("versionMinorLast", verMinor)
    versionPropsFile.outputStream().use {
        versionProps.store(it, "Auto-incremented on compile")
    }
}


// compute gitCount (existing approach)
val gitCommitCount: Int = try {
    val proc = Runtime.getRuntime().exec(arrayOf("bash", "-lc", "git rev-list --count HEAD"))
    proc.inputStream.bufferedReader().readText().trim().toInt()
} catch (e: Exception) {
    0
}

// read project property if provided
val versionCodeOffset: Int = (project.findProperty("versionCodeOffset") as? String)?.toIntOrNull() ?: 0

android {
        namespace = "com.hereliesaz.graffux"
        compileSdk = 37

        defaultConfig {
            applicationId = "com.hereliesaz.graffux"
            minSdk = 26
            targetSdk = 37

            versionCode = currentVersionCode
            versionName = currentVersionName

            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            vectorDrawables {
                useSupportLibrary = true
            }

            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }

            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                }
            }
        }

        buildFeatures {
            compose = true
            buildConfig = true
        }

        packaging {
            resources {
                excludes += "/META-INF/{AL2.0,LGPL2.1}"
            }
            jniLibs {
                pickFirsts += "**/libc++_shared.so"
                // <-- This is the NEW line that was missing
            }
        }

        signingConfigs {
            if (releaseKeystore != null) {
                create("release") {
                    storeFile = releaseKeystore
                    storePassword = envKeystorePassword
                    keyAlias = envKeyAlias
                    keyPassword = envKeyPassword
                }
            }
        }

        buildTypes {
            release {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
                signingConfig = signingConfigs.findByName("release")
            }
            debug {
                signingConfig = signingConfigs.findByName("release") ?: signingConfig
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }

        bundle {
            abi { enableSplit = true }
            density { enableSplit = true }
            language { enableSplit = true }
        }
    }


tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val version = variant.outputs.first().versionName.get()
            val code = variant.outputs.first().versionCode.get()
            val apkName = "GraffitiXR-${variant.name}-$version.$code.apk"
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(apkName)
        }
    }
}
val currentVersionName = "$verMajor.$verMinor.$currentPatch"


dependencies {
    // The shared editor + its foundation (the single source of truth this app hosts).
    implementation(project(":feature:editor"))
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:design"))
    implementation(project(":core:nativebridge"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)

    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.common.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // The AzNavRail host (AzHostActivityLayout + the rail DSL) that MainActivity wraps the editor in.
    // Reaches us transitively via :core:common's api(az-nav-rail), but :app now calls the DSL directly.
    implementation(libs.az.nav.rail)
    implementation(libs.navigation.compose)

    implementation(libs.timber)
    implementation(libs.coil.compose)

    // Import the Firebase BoM so all Firebase library versions stay compatible; don't pin
    // individual Firebase dependency versions when using it.
    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-analytics")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
