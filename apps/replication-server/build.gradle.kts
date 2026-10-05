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
