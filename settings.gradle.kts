pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "pubsub-prototype"

include("libs:protocol-core")
include("libs:event-core")
include("libs:registry-api")
include("libs:registry-cardano")
include("libs:transport-netty")
include("apps:pubsub-node")

include("libs:peer-sampling-api")
include("libs:securecyclon")
include("libs:navigation")
