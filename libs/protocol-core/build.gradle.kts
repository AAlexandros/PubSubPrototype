plugins {
    `java-library`
}

dependencies {
    api(project(":libs:peer-sampling-api"))
    api(project(":libs:securecyclon"))
    api(project(":libs:event-core"))
    api("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}
