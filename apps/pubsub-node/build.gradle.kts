plugins {
    application
}

dependencies {
    implementation(project(":libs:peer-sampling"))
    implementation(project(":libs:http-support"))
    implementation(project(":libs:navigation"))
    implementation(project(":libs:dissemination"))
    implementation(project(":libs:persistence-core"))
    implementation(project(":libs:protocol-core"))
    implementation(project(":libs:event-core"))
    implementation(project(":libs:registry-cardano"))
    implementation(project(":libs:transport-netty"))
    implementation(libs.snakeyaml)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
}

application {
    mainClass.set("org.pubsub.prototype.node.PubSubNodeMain")
}
