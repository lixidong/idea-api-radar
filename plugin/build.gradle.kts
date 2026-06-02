import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

val pluginGroup: String by project
val pluginVersion: String by project
val pluginSinceBuild: String by project
val platformVersion: String by project
val javaVersion: String by project

group = pluginGroup
version = pluginVersion

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(platformVersion)
        bundledPlugin("com.intellij.java")
        // instrumentationTools() 已在 2.x 后期版本移除，无需显式声明
    }

    // Agent jar 只作为资源（processResources 复制），不作为 classpath 依赖，
    // 避免被打到 plugin/lib/ 下产生重复

    // JSON 解析（轻量，避免引入 Jackson 等大库与 IDE 内嵌冲突）
    implementation("com.google.code.gson:gson:2.11.0")

    // JDK 的 tools.jar / Attach API：IDEA 自带 JBR 已包含 jdk.attach 模块，无需额外依赖
}

intellijPlatform {
    pluginConfiguration {
        name = "API Radar"
        version = pluginVersion
        ideaVersion {
            sinceBuild = pluginSinceBuild
            untilBuild = provider { null }  // 兼容更新版本
        }
        // description / changeNotes / vendor 以 plugin.xml 为准（构建会自动读取 META-INF/plugin.xml）。
        // 这里仅保留 IntelliJ Gradle 必填的最小信息，避免双源不一致。
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(javaVersion)
    targetCompatibility = JavaVersion.toVersion(javaVersion)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(javaVersion)
    }
}

tasks {
    // 把 agent jar 复制到 plugin 资源里，运行时可读取
    processResources {
        dependsOn(":agent:agentJar")
        from(project(":agent").tasks.named("agentJar")) {
            into("agent")
            rename { "api-radar-agent.jar" }
        }
    }
}
