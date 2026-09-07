plugins {
    `java-library`
    application
}

application {
    mainClass.set("org.pubsub.prototype.persistence.core.ReplicationRegistryCli")
}

dependencies {
    api(project(":libs:persistence-api"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
    implementation("org.slf4j:slf4j-api:2.0.13")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}
