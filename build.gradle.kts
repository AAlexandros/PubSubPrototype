plugins {
    java
}

allprojects {
    group = "org.pubsub.prototype"
    version = "0.1.0-SNAPSHOT"
}

val junitPlatformLauncherDependency = libs.junit.platform.launcher

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    dependencies {
        "testRuntimeOnly"(junitPlatformLauncherDependency)
    }
}
