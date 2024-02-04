import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.8.21"
    application
}

group = "vootelerotov.github.io"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "1.8"
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.github.ajalt.clikt:clikt:4.2.0")

    implementation("com.spotify:github-client:0.2.14")
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")

    implementation("org.slf4j:slf4j-simple:2.0.7")
}


application {
    mainClass.set("io.github.vootelerotov.patchinator.PatchinatorKt")
    applicationName = "patchinator"
}