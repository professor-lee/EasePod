pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "EasePod"
include(":app", ":core", ":data", ":playback", ":plugins", ":plugin-contract", ":sample-plugin", ":netease-plugin")
