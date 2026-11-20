plugins {
    `java-library`
}

dependencies {
    api(libs.jackson.databind)
    api(libs.jackson.jsr310)
    api(libs.netty.all)
    api(libs.slf4j.api)
    implementation(libs.jackson.dataformat.yaml)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.logback.classic)
}

sourceSets {
    test {
        java.setSrcDirs(listOf("../test/java/libs/java"))
        resources.setSrcDirs(listOf("../test/java/libs/resources"))
    }
}
