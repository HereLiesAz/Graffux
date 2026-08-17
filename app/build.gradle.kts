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
val hasGoogleServices = file("google-services.json").exists()
if (hasGoogleServices) {
    apply(plugin = "com.google.gms.google-services")
}

// Load local properties
val localProperties = Properties().apply {
    val localPropertiesFile = project.rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

val verMajor = versionProps.getProperty("versionMajor", "1")
val verMinor = versionProps.getProperty("versionMinor", "0")

val lastMinor = versionProps.getProperty("versionMinorLast", verMinor)
val isMinorBumped = verMinor != lastMinor

var currentVersionCode = versionProps.getProperty("versionBuild", "1").toInt() + 1
var currentPatch = if (isMinorBumped) 0 else versionProps.getProperty("versionPatch", "0").toInt() + 1

// Printed at configuration time (every invocation, regardless of which tasks run), so CI can grep
// it straight out of the build log for the exact versionCode THIS invocation is building — see
// release-aab.yml's "Build signed AAB" step. Deliberately not re-derived from version.properties
// after the build: `currentVersionCode` here is computed exactly once per invocation and is
// unambiguously what AGP assigns as the variant's versionCode (see `versionCode =
// currentVersionCode` below). The doFirst hook below mutates the same in-memory `versionProps`
// object on every task it actually runs on — if that ever turns out to fire more than once in a
// single invocation (e.g. an intermediate task also named assemble*/bundle* actually executes,
// not just gets registered against by `tasks.matching`), the FILE's versionBuild after the build
// would drift from this printed value; this println is intentionally immune to that regardless of
// whether it happens. NOTE: relies on configuration actually running every invocation — this
// project doesn't enable Gradle's configuration cache today, but a cache hit would skip this
// println (and CI's grep for it) without rerunning it.
println("GRAFFUX_VERSION_CODE=$currentVersionCode")

tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("bundle") }.configureEach {
    doFirst {
        var execVersionCode = versionProps.getProperty("versionBuild", "1").toInt()
        var execPatch = if (isMinorBumped) 0 else versionProps.getProperty("versionPatch", "0").toInt()
        
        execVersionCode++
        if (!isMinorBumped) execPatch++
        
        versionProps.setProperty("versionBuild", execVersionCode.toString())
        versionProps.setProperty("versionPatch", execPatch.toString())
        versionProps.setProperty("versionMinorLast", verMinor)
        versionPropsFile.outputStream().use {
            versionProps.store(it, "Auto-incremented on compile")
        }
    }
}

val currentVersionName = "$verMajor.$verMinor.$currentPatch"

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
            }
        }

        val envKeystoreFile = System.getenv("KEYSTORE_FILE")
        val envKeystorePassword = System.getenv("KEYSTORE_PASSWORD")
        val envKeyAlias = System.getenv("KEY_ALIAS")
        val envKeyPassword = System.getenv("KEY_PASSWORD")
        val releaseKeystoreFile = envKeystoreFile?.let { rootProject.file(it) } ?: file("keystore.jks")
        val releaseKeystore = releaseKeystoreFile
            .takeIf {
                it.exists() &&
                        !envKeystorePassword.isNullOrEmpty() &&
                        !envKeyAlias.isNullOrEmpty() &&
                        !envKeyPassword.isNullOrEmpty()
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

        // Fail loudly instead of silently shipping an unsigned release build. Without this, a
        // misconfigured signing secret (empty KEY_PASSWORD/KEY_ALIAS, or a keystore that failed
        // to generate) makes `signingConfigs.findByName("release")` null, `release.signingConfig`
        // null, and `bundleRelease`/`assembleRelease` still "succeed" — producing an unsigned AAB
        // that release-aab.yml's `if-no-files-found: error` check is satisfied by (a file exists)
        // and that only fails, if it fails at all, much later at the Play upload with a confusing
        // Play-side error. Scoped to CI specifically via KEYSTORE_FILE — that env var is only ever
        // set by the release workflows (see release-aab.yml / release-apk.yml / merged-build.yml),
        // so a local `./gradlew bundleRelease` with no signing secrets configured still produces
        // an unsigned build for local testing, same as before.
        if (!envKeystoreFile.isNullOrEmpty()) {
            gradle.taskGraph.whenReady {
                val buildingSignedRelease = allTasks.any {
                    it.project == project &&
                            (it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease"))
                }
                if (buildingSignedRelease && releaseKeystore == null) {
                    val reason = when {
                        !releaseKeystoreFile.exists() -> "the keystore file '$releaseKeystoreFile' does not exist"
                        envKeystorePassword.isNullOrEmpty() -> "KEYSTORE_PASSWORD is empty or unset"
                        envKeyAlias.isNullOrEmpty() -> "KEY_ALIAS is empty or unset"
                        else -> "KEY_PASSWORD is empty or unset"
                    }
                    throw GradleException(
                        "Refusing to build an unsigned release variant: KEYSTORE_FILE is set (this " +
                                "looks like a CI release build) but $reason. Fix the signing secrets " +
                                "rather than letting this build 'succeed' unsigned."
                    )
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
            val version = output.versionName.get()
            val code = output.versionCode.get()
            val apkName = "Graffux-${variant.name}-$version.$code.apk"
            output.outputFileName.set(apkName)
        }
    }
}



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
    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    implementation("com.google.firebase:firebase-analytics")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
