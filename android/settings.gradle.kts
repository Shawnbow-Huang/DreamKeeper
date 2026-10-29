pluginManagement {
    repositories {
        // 国内环境：repo.maven.apache.org 极慢/超时，优先走腾讯云 maven-public 镜像
        maven {
            name = "TencentMirror"
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内环境：repo.maven.apache.org 极慢/超时，优先走腾讯云 maven-public 镜像
        maven {
            name = "TencentMirror"
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "DreamKeeper"
include(":app")
