pluginManagement {
    repositories {
        val base = System.getenv("CAAS_ARTIFACTORY_BASE_URL")
        val user = System.getenv("CAAS_ARTIFACTORY_READER_USERNAME")
        val passwordValue = System.getenv("CAAS_ARTIFACTORY_READER_PASSWORD")
        if (base != null && user != null && passwordValue != null) {
            maven {
                url = uri("https://$base/artifactory/dl.google.com-public/android/maven2")
                credentials {
                    username = user
                    password = passwordValue
                }
            }
            maven {
                url = uri("https://$base/artifactory/maven-public")
                credentials {
                    username = user
                    password = passwordValue
                }
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val base = System.getenv("CAAS_ARTIFACTORY_BASE_URL")
        val user = System.getenv("CAAS_ARTIFACTORY_READER_USERNAME")
        val passwordValue = System.getenv("CAAS_ARTIFACTORY_READER_PASSWORD")
        if (base != null && user != null && passwordValue != null) {
            maven {
                url = uri("https://$base/artifactory/dl.google.com-public/android/maven2")
                credentials {
                    username = user
                    password = passwordValue
                }
            }
            maven {
                url = uri("https://$base/artifactory/maven-public")
                credentials {
                    username = user
                    password = passwordValue
                }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "SpreadtrumFlashAndroid"
include(":app")
