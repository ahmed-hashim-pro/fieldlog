plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // The long fuzz runs are opt-in: ./gradlew :core:test -Pfuzz-seeds=5000
    systemProperty("fuzz.seeds", providers.gradleProperty("fuzz-seeds").getOrElse("300"))
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
