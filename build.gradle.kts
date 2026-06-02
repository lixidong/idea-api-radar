plugins {
    kotlin("jvm") version "2.3.0" apply false
}

allprojects {
    group = "com.lingshi.apiradar"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
}
