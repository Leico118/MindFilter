import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mindfilter.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mindfilter.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 41
        versionName = "0.7.2"
    }

    signingConfigs {
        // Clé fixe (décodée par le workflow) : les futures versions s'installeront
        // par-dessus celle-ci sans désinstallation.
        val ks = rootProject.file("keystore/mindfilter-debug.jks")
        if (ks.exists()) {
            create("fixed") {
                storeFile = ks
                storePassword = "mindfilter"
                keyAlias = "mindfilter"
                keyPassword = "mindfilter"
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("fixed")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("fixed")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Modèles d'analyse d'images, téléchargés au moment de la compilation (voir plus bas).
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/visionAssets").get().asFile)
}

/**
 * Télécharge, pendant la compilation dans le cloud, les modèles qui tournent ENTIÈREMENT sur
 * le téléphone (aucune image n'est jamais envoyée) :
 *  - Tesseract : lecture du texte en français et en anglais (modèles « rapides ») ;
 *  - MobileNet : reconnaissance d'objets (araignées, serpents, armes, chars…).
 * Si un téléchargement échoue, la compilation continue : l'option fonctionnera sans ce modèle.
 */
val downloadVisionModels by tasks.registering {
    val out = layout.buildDirectory.dir("generated/visionAssets")
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        fun fetch(url: String, dest: File) {
            if (dest.exists() && dest.length() > 0) return
            dest.parentFile.mkdirs()
            try {
                val c = URI(url).toURL().openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 30_000
                c.readTimeout = 120_000
                c.inputStream.use { input: InputStream -> dest.outputStream().use { o: OutputStream -> input.copyTo(o) } }
                println("Modèle téléchargé : ${dest.name} (${dest.length() / 1024} Ko)")
            } catch (e: Exception) {
                dest.delete()
                println("ATTENTION : téléchargement impossible de $url : ${e.message}")
            }
        }
        fetch("https://github.com/tesseract-ocr/tessdata_fast/raw/main/fra.traineddata", File(dir, "tessdata/fra.traineddata"))
        fetch("https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata", File(dir, "tessdata/eng.traineddata"))
        // Modèle « empreinte d'image » (MediaPipe / MobileNet V3) : sert à l'apprentissage par
        // l'exemple (mode développeur). Grand modèle d'abord, petit en secours.
        val emb = File(dir, "vision/embedder.tflite")
        fetch("https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_large/float32/latest/mobilenet_v3_large.tflite", emb)
        fetch("https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/latest/mobilenet_v3_small.tflite", emb)
        // Détecteur de nudité / pornographie (Yahoo open_nsfw, version Android de devzwy).
        fetch(
            "https://raw.githubusercontent.com/devzwy/open_nsfw_android/a1b49e0cf4d28c2f67bf60a87005a7c220cde487/app/src/main/assets/nsfw.tflite",
            File(dir, "vision/nsfw.tflite"),
        )
        val zip = File(temporaryDir, "mobilenet.zip")
        fetch("https://storage.googleapis.com/download.tensorflow.org/models/tflite/mobilenet_v1_1.0_224_quant_and_labels.zip", zip)
        if (zip.exists()) {
            try {
                ZipFile(zip).use { z: ZipFile ->
                    for (e in z.entries()) {
                        val name = when {
                            e.name.endsWith(".tflite") -> "vision/mobilenet.tflite"
                            e.name.endsWith(".txt") -> "vision/labels.txt"
                            else -> null
                        } ?: continue
                        val f = File(dir, name)
                        f.parentFile.mkdirs()
                        z.getInputStream(e).use { input: InputStream -> f.outputStream().use { o: OutputStream -> input.copyTo(o) } }
                    }
                }
            } catch (e: Exception) {
                println("ATTENTION : archive MobileNet illisible : ${e.message}")
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadVisionModels) }

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Analyse des images (option) : tout tourne sur le téléphone.
    implementation("cz.adaptech.tesseract4android:tesseract4android-openmp:4.9.0")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    testImplementation("junit:junit:4.13.2")
}
