plugins {
    `java-library`
}

dependencies {
    api(project(":libs:peer-sampling"))
    api(project(":libs:navigation"))
    api(project(":libs:dissemination"))
    api(project(":libs:event-core"))
    api(libs.jackson.databind)
    api(libs.jackson.jsr310)

    testImplementation(libs.junit.jupiter)
}
