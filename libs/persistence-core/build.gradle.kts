plugins {
    `java-library`
    application
}

application {
    mainClass.set("org.pubsub.prototype.persistence.core.ReplicationRegistryCli")
}

dependencies {
    api(project(":libs:persistence-api"))
    implementation(project(":libs:http-support"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
}
