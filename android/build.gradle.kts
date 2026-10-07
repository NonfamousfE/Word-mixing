// 顶层构建脚本：只声明插件版本，不在这里配置模块。
//
// 版本选择说明（都是踩过才定的）：
//   * Gradle 8.12（开发机上已缓存的发行版）
//   * AGP 8.9.1 —— **不能用 8.13.x**：那要求 Gradle ≥ 8.13，
//     而缓存里只有 8.12，会报
//     "Minimum supported Gradle version is 8.13. Current version is 8.12"。
//     8.9.1 与 Gradle 8.12 匹配。
//   * Kotlin 2.0.21（缓存里有的版本，与 AGP 8.9 兼容）
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
