plugins {
    `java-library`
}

dependencies {
    api(project(":libs:navigation"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}
