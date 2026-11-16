// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// Le projet est dans un dossier synchronisé par iCloud, qui duplique des fichiers pendant
// la compilation (erreur « Type ... is defined multiple times »). On compile donc hors de Documents.
allprojects {
    layout.buildDirectory.set(
        file("${System.getProperty("user.home")}/.badgeuse-build/${rootProject.name}/${project.name}")
    )
}
