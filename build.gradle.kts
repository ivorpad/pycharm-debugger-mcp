plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "local.pycharmdebugger"
version = "0.1.9"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val sdkPath = providers.gradleProperty("pycharmPath").orNull
        if (sdkPath == null) pycharm("2026.3.0.1") else local(sdkPath)
        bundledPlugin("com.intellij.mcpServer")
        bundledModules("intellij.platform.debugger", "intellij.platform.debugger.impl",
            "intellij.platform.debugger.impl.rpc", "intellij.platform.debugger.impl.shared", "intellij.platform.debugger.impl.ui")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

intellijPlatform {
    instrumentCode = false
    // Nothing here adds a Settings page, so there is nothing to index.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = provider { null }
            untilBuild = provider { null }
        }
    }
}

tasks.buildPlugin {
    destinationDirectory = layout.projectDirectory.dir("dist")
}

tasks.processResources {
    from("LICENSE") { into("META-INF") }
}
