plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "local.pycharmdebugger"
version = "0.1.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Build 262.10315.174. PyCharm Community stopped being published
        // separately after 2025.3, so this is the unified distribution.
        pycharm("2026.2.2")
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
            // Keep the pin the shipped 0.1.2 descriptor carries.
            sinceBuild = "262.10315.174"
            untilBuild = "262.10315.174"
        }
    }
}
