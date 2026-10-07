plugins {
    application
}

dependencies {
    implementation(project(":libs"))
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
}

application {
    mainClass.set("org.pubsub.prototype.replication.ReplicationServerMain")
}

tasks.register<JavaExec>("runReplicationRegistryCli") {
    group = "application"
    description = "Deploys, queries, or updates the Cardano replication registry."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.pubsub.prototype.replication.ReplicationRegistryCli")
}
