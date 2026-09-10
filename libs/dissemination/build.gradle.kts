plugins {
    `java-library`
}

dependencies {
    api(project(":libs:navigation"))
    testImplementation(libs.junit.jupiter)
}
