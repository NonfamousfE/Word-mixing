// 安卓端构建配置。
//
// 一个刻意的决定：**不依赖 AndroidX / Compose / OkHttp**。
//   * 开发机上这些库都没缓存，拉一圈依赖会让构建很脆（网络时好时坏）
//   * 界面需求很简单（列表 + 遮挡 + 输入框），框架自带的 View 完全够用
//   * 仍然是**原生 App**（原生控件、Dex 字节码），不是网页套壳
//   * JSON 用 org.json（Android 自带），网络用 HttpURLConnection（JDK 自带）
//
// 唯一需要的第三方就是 Kotlin 插件本身，而它已经在缓存里。

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wordmix.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wordmix.app"
        minSdk = 26              // Android 8.0+，覆盖绝大多数在用手机
        targetSdk = 34
        versionCode = 13
        versionName = "1.1.2"
        resourceConfigurations += listOf("zh", "en")
    }

    // 正式签名：自更新必须用**同一个签名**，否则安卓会拒绝覆盖安装。
    // keystore 随仓库提供（自用场景；它不是机密，泄漏的后果只是
    // 别人能签一个同包名的包——而这个包本来就只在自己设备上装）。
    signingConfigs {
        create("release") {
            storeFile = file("keystore/wordmix.jks")
            storePassword = "wordmix"
            keyAlias = "wordmix"
            keyPassword = "wordmix"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false          // 自用，不做混淆，便于排查
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // 注意：**不加 applicationIdSuffix**。
            // 自更新要求包名一致，否则系统会当成另一个应用、装不上去。
            // debug 与 release 也因此共用同一个 applicationId。
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // 纯 SDK 项目，不需要这些
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // 显式声明 Kotlin 源码目录。
    // 不写的话单元测试会报 ClassNotFoundException: com.wordmix.app.SyncProtocolTest
    // —— 类其实编译出来了（在 build/tmp/kotlin-classes/debugUnitTest 下），
    // 但没被放进测试的运行时 classpath。显式声明后就正常了。
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/java", "src/test/kotlin")
        }
    }
}

// 单元测试的运行时 classpath 补丁。
//
// 症状：测试类编译成功（build/intermediates/javac/debugUnitTest/.../classes 里
// 确实有 SyncProtocolTest.class），但运行时报
// ClassNotFoundException: com.wordmix.app.SyncProtocolTest。
//
// 原因：本工程把源码放在 src/test/java 下但内容是 Kotlin 风格的布局，
// AGP 生成的测试运行时 classpath 没包含 javac 的输出目录。
// 这里显式加进去 —— 只影响单元测试，不影响 APK 产物。
tasks.withType<Test>().configureEach {
    val variant = name.removePrefix("test")
    val javacOut = layout.buildDirectory.dir(
        "intermediates/javac/${variant.replaceFirstChar { it.lowercase() }}/compile${variant}JavaWithJavac/classes"
    )
    classpath += files(javacOut)
}
dependencies {
    // 只引 AndroidX 的 core —— 自更新要用它的 FileProvider
    // （直接给系统安装器一个 file:// 在 7.0+ 会抛 FileUriExposedException）。
    // 界面刻意不用 Compose / AppCompat / Material：
    //   这个 App 的界面就是列表 + 遮挡 + 输入框，框架自带控件够用，
    //   而且少一层依赖就少一堆下载和版本匹配问题。
    implementation("androidx.core:core-ktx:1.13.1")

    // 只有 JUnit：同步逻辑是纯 Kotlin，单元测试可以直接对真实服务器跑，
    // 不需要模拟器、也不需要 Robolectric 之类。
    // （一开始想用 org.json，但那是 Android 框架的类，JVM 测试里没有，
    //   所以自己实现了一份 AppJson。）
    testImplementation("junit:junit:4.13.2")
}
