plugins {
    `java-library`
}

dependencies {
    api(libs.jackson.databind)
    api(libs.jackson.jsr310)
    api(libs.netty.all)
    api(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.logback.classic)
}

tasks.register<JavaExec>("runRegistryCli") {
    group = "application"
    description = "Runs the Cardano topic registry CLI."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.pubsub.prototype.registry.cardano.RegistryCli")
}

tasks.register<JavaExec>("runReplicationRegistryCli") {
    group = "application"
    description = "Runs the replication registry CLI."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.pubsub.prototype.persistence.core.ReplicationRegistryCli")
}
