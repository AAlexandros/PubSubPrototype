plugins {
    application
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.avro)
    implementation(libs.parquet.avro)
    implementation(libs.hadoop.common)

    testImplementation(libs.junit.jupiter)
}

application {
    mainClass.set("org.pubsub.prototype.telemetry.TelemetryTool")
}
