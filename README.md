# 串口通信框架

## 功能：
- 实现串口基本通信；
- 一发多收，一发一收；
- 超时重试；
- 失败重试。

## 使用：

### 依赖配置

在 `build.gradle` 文件中添加以下配置：

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