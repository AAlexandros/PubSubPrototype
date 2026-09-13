plugins {
    `java-library`
}

dependencies {
    api(project(":libs:registry-cardano"))
    api(libs.jackson.databind)

    testImplementation(libs.junit.jupiter)
}
