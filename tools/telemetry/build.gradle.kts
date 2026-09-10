plugins {
    application
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("org.apache.avro:avro:1.12.0")
    implementation("org.apache.parquet:parquet-avro:1.15.2")
    implementation("org.apache.hadoop:hadoop-common:3.4.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}

application {
    mainClass.set("org.pubsub.prototype.telemetry.TelemetryTool")
}
