plugins {
    application
}

dependencies {
    implementation(project(":libs:http-support"))
    implementation(project(":libs:persistence-api"))
    implementation(project(":libs:persistence-core"))
    implementation(project(":libs:registry-cardano"))
    implementation(libs.snakeyaml)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
}

application {
    mainClass.set("org.pubsub.prototype.replication.ReplicationServerMain")
}
