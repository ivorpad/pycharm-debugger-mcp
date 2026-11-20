plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "local.pycharmdebugger"
version = "0.1.4"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        pycharm("2026.3.0.1")
        bundledPlugin("com.intellij.mcpServer")
    }
}

java {
    toolchain {
        // PyCharm 2026.2 ships Java 25 class files, and the shipped
        // debugger-mcp.jar is major version 69 too.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

intellijPlatform {
    // Nothing here adds a Settings page, so there is nothing to index.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "263.7307.329"
            untilBuild = "263.*"
        }
    }
}
