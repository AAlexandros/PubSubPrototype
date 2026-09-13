plugins {
    application
    `java-library`
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)

    testImplementation(libs.junit.jupiter)
}

application {
    mainClass.set("org.pubsub.prototype.registry.cardano.RegistryCli")
}
