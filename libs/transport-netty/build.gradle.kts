plugins {
    `java-library`
}

dependencies {
    api(project(":libs:peer-sampling-api"))
    api(project(":libs:protocol-core"))
    api("io.netty:netty-all:4.1.112.Final")
    api("org.slf4j:slf4j-api:2.0.13")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("ch.qos.logback:logback-classic:1.5.6")
}
