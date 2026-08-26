plugins {
    application
}

dependencies {
    implementation(project(":libs:protocol-core"))
    implementation(project(":libs:transport-netty"))
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.slf4j:slf4j-api:2.0.13")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.6")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}

application {
    mainClass.set("org.pubsub.prototype.node.PubSubNodeMain")
}
