plugins {
    `java-library`
}

dependencies {
    api(project(":libs:registry-api"))
    api("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}
