plugins {
    `java-library`
}

dependencies {
    api(project(":libs:event-core"))
    api(project(":libs:registry-api"))
    api("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}
