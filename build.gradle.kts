import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.kotlin.dsl.register

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.1.1")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: LibraryExtension.() -> Unit) {
    extensions.getByName<LibraryExtension>("android").apply {
        (extensions.findByName("java") as? JavaPluginExtension)?.apply {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(17))
            }
        }
        configuration()
    }
}

subprojects {
    if (project.path == ":extensions") return@subprojects

    apply(plugin = "com.android.library")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "Lumusi/DaddyLive-Cloudstream")
        authors = listOf("d4d")
    }

    android {
        compileSdk = 36

        defaultConfig {
            minSdk = 21
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            freeCompilerArgs.addAll(
                listOf(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions",
                    "-Xannotation-default-target=param-property"
                )
            )
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        cloudstream("com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.13")
        implementation("org.jsoup:jsoup:1.22.1")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.5")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.13.5")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        implementation("org.mozilla:rhino:1.9.0")
        implementation("me.xdrop:fuzzywuzzy:1.4.0")
        implementation("com.google.code.gson:gson:2.13.2")
        implementation("app.cash.quickjs:quickjs-android:0.9.2")
        implementation("com.github.vidstige:jadb:1.2.1")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
