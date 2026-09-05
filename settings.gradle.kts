pluginManagement {
    buildscript {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools:r8:8.8.34")
        }
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "Sepotify"
include(":app")
include(":spotify")
include(":innertube")

val rootKeystore = File(rootDir, "debug.keystore")
val homeKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
if (rootKeystore.exists()) {
    homeKeystore.parentFile?.mkdirs()
    if (!homeKeystore.exists() || homeKeystore.length() != rootKeystore.length()) {
        rootKeystore.copyTo(homeKeystore, overwrite = true)
    }
}
 