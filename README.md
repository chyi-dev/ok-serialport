# 串口通信框架配置

## 1. 配置 `dependencyResolutionManagement`

在 `settings.gradle` 文件中配置 `dependencyResolutionManagement`，确保使用正确的仓库源。

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}

dependencies {
    implementation 'com.github.chyi-blip:ok-serialport:Tag'
}

请确保将 Tag 替换为你所需要的版本标签，或者直接使用 latest.release 获取最新的版本。