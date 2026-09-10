plugins {
    `java-library`
}

dependencies {
    api(project(":libs:peer-sampling-api"))
    api(project(":libs:protocol-core"))
    api(libs.netty.all)
    api(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.logback.classic)
}
