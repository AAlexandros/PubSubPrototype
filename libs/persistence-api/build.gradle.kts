plugins {
    `java-library`
}

dependencies {
    api(project(":libs:event-core"))
    api(project(":libs:registry-api"))
    api(libs.jackson.databind)
}
