plugins { `java-library` }
dependencies {
    api(project(":libs:peer-sampling-api"))
    testImplementation(libs.junit.jupiter)
}
