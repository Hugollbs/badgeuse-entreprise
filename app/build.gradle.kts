import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "fr.mairie.badgeuse"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "fr.mairie.badgeuse"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Clé de signature de la mairie : lue dans keystore.properties (à la racine du projet), qui n'est
    // jamais versionné (.gitignore). Sans ce fichier, la version release est produite non signée.
    val fichierSignature = rootProject.file("keystore.properties")
    if (fichierSignature.exists()) {
        val signature = Properties().apply { fichierSignature.inputStream().use { load(it) } }
        signingConfigs {
            create("mairie") {
                storeFile = rootProject.file(signature.getProperty("storeFile"))
                storePassword = signature.getProperty("storePassword")
                keyAlias = signature.getProperty("keyAlias")
                keyPassword = signature.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (fichierSignature.exists()) signingConfig = signingConfigs.getByName("mairie")
            optimization {
                enable = true
                keepRules {
                    // Les règles par défaut d'Android, puis celles du projet (JavaMail).
                    files.add(getDefaultProguardFile("proguard-android-optimize.txt"))
                    files.add(file("proguard-rules.pro"))
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            // android-mail et android-activation livrent chacun leur META-INF/LICENSE.md : on n'en garde qu'un.
            pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
        }
    }
}

ksp {
    // Dossier versionné avec le code : une copie du schéma de chaque version de la base.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    // Envoi SMTP des sauvegardes (ADR-002)
    implementation(libs.sun.android.mail)
    implementation(libs.sun.android.activation)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}