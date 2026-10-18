plugins {
    application
}

dependencies {
    implementation(project(":libs"))
    implementation(libs.jakarta.ws.rs.api)
    implementation(libs.jersey.container.jdk.http)
    implementation(libs.jersey.hk2)
    implementation(libs.jersey.media.json.jackson)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
}

application {
    mainClass.set("org.pubsub.prototype.node.PubSubNodeMain")
}
