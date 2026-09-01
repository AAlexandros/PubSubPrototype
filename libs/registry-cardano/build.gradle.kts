plugins {
    application
    `java-library`
}

dependencies {
    api(project(":libs:registry-api"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}

application {
    mainClass.set("org.pubsub.prototype.registry.cardano.RegistryCli")
}
