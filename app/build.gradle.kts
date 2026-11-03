plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from the environment. CI sets these from repository secrets.
val keystore = System.getenv("DETENT_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
val sourceSha = providers.gradleProperty("detentCommit").orElse("local").get()
val channel = providers.gradleProperty("detentChannel").orElse("local").get()
val buildRun = providers.gradleProperty("detentRun").orElse("local").get()
require(sourceSha == "local" || sourceSha.matches(Regex("[0-9a-f]{40}")))
require(channel in setOf("local", "candidate", "preview", "nightly", "stable"))
require(buildRun == "local" || buildRun.matches(Regex("[0-9]+")))
abstract class WriteBuildIdentity : DefaultTask() {
    @get:org.gradle.api.tasks.Input abstract val revision: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.Input abstract val buildChannel: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.Input abstract val runId: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.OutputDirectory abstract val outputDirectory: org.gradle.api.file.DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun writeIdentity() {
        val dir = outputDirectory.get().asFile.apply { mkdirs() }
        dir.resolve("detent-build.json").writeText(
            """{"source_sha":"${revision.get()}","channel":"${buildChannel.get()}","run_id":"${runId.get()}"}""",
        )
    }
}
val writeBuildIdentity = tasks.register<WriteBuildIdentity>("writeBuildIdentity") {
    revision.set(sourceSha)
    buildChannel.set(channel)
    runId.set(buildRun)
    outputDirectory.set(layout.buildDirectory.dir("generated/detentIdentity"))
}

android {
    namespace = "app.detent.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = providers.gradleProperty("detentId").orNull ?: "app.detent.launcher"
        // Predefined vibration effects and the home role need API 29.
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SOURCE_SHA", "\"$sourceSha\"")
        buildConfigField("String", "CHANNEL", "\"$channel\"")
        // CI passes the tag as -PdetentVersion and the run number as -PdetentCode.
        versionCode = providers.gradleProperty("detentCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("detentVersion").orNull ?: "0.1.0"
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("DETENT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DETENT_KEY_ALIAS")
                keyPassword = System.getenv("DETENT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release keystore, the build uses the local debug key so that `adb install` still works.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Pre-existing Glass.kt RuntimeShader NewApi findings only (guarded by SDK_INT >= 33).
        // Any new lint error still fails CI; shrink this file, never regenerate it to hide new ones.
        baseline = file("lint-baseline.xml")
    }

    androidResources {
        // The wallpapers are already compressed WebP files.
        noCompress += "webp"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(writeBuildIdentity, WriteBuildIdentity::outputDirectory)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
