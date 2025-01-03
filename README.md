串口通信框架

使用：
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
