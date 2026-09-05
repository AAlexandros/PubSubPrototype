plugins { `java-library` }
dependencies {
    api(project(":libs:peer-sampling-api"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}
