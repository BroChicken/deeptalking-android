pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DeepTalking"

include(":app")

// Foundation
include(":core:common")
include(":core:model")
include(":core:network")
include(":core:database")
include(":core:data")
include(":core:security")
include(":core:notifications")
include(":core:designsystem")

// Domain
include(":domain:agent")
include(":domain:memory")

// On-device inference interfaces
include(":engine:ondevice")

// Features
include(":feature:chat")
include(":feature:characters")
include(":feature:memory")
include(":feature:settings")
include(":feature:richtext")
