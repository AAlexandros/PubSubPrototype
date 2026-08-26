plugins {
    `java-library`
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}
