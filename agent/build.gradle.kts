plugins {
    id("java")
}

val pluginGroup: String by project
val pluginVersion: String by project

group = pluginGroup
version = pluginVersion

java {
    // Agent 用 Java 11，兼容更多目标项目
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation("net.bytebuddy:byte-buddy:1.15.11")
    implementation("net.bytebuddy:byte-buddy-agent:1.15.11")
    // 不引入 gson，避免与目标项目类冲突；agent 内手写极简 JSON 序列化
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "com.lingshi.apiradar.agent.ApiRadarAgent",
            "Agent-Class" to "com.lingshi.apiradar.agent.ApiRadarAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Boot-Class-Path" to "api-radar-agent.jar"
        )
    }
}

// 把 ByteBuddy 等依赖打进 fat jar，避免目标项目缺依赖
val agentJar by tasks.registering(Jar::class) {
    archiveClassifier.set("all")
    archiveBaseName.set("api-radar-agent")

    manifest {
        attributes(
            "Premain-Class" to "com.lingshi.apiradar.agent.ApiRadarAgent",
            "Agent-Class" to "com.lingshi.apiradar.agent.ApiRadarAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}

configurations {
    create("agentJar") {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
}

artifacts {
    add("agentJar", agentJar)
}
