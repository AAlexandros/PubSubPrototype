plugins {
    `java-library`
}

dependencies {
    api(project(":libs:registry-api"))
    api(libs.jackson.databind)

    testImplementation(libs.junit.jupiter)
}
