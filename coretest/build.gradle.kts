plugins {
    kotlin("jvm") version "1.9.24"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

sourceSets {
    main {
        kotlin.srcDirs(
            "../app/src/main/java/dev/pearlwallet/core",
            "../app/src/main/java/dev/pearlwallet/logic"
        )
    }
    test {
        kotlin.srcDirs("../app/src/test/java")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.test {
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
