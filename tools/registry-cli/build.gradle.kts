plugins {
    application
}

dependencies {
    implementation(project(":libs"))
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass.set("org.pubsub.prototype.registry.cli.TopicRegistryCli")
}

tasks.register<JavaExec>("runRegistryCli") {
    group = "application"
    description = "Runs the Cardano topic registry CLI."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.pubsub.prototype.registry.cli.TopicRegistryCli")
}
