plugins {
    application
}

dependencies {
    implementation(project(":libs:persistence-api"))
    implementation(project(":libs:persistence-core"))
    implementation(project(":libs:registry-cardano"))
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.slf4j:slf4j-api:2.0.13")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.6")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}

application {
    mainClass.set("org.pubsub.prototype.replication.ReplicationServerMain")
}
