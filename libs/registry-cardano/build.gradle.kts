plugins {
    application
    `java-library`
}

dependencies {
    api(project(":libs:registry-api"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)

    testImplementation(libs.junit.jupiter)
}

application {
    mainClass.set("org.pubsub.prototype.registry.cardano.RegistryCli")
}
