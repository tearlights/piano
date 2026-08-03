# ADR-0004：采用 Kotlin 与 Jetpack Compose 建立首版 UI 基础

日期：2026-07-29  
状态：已接受

## 决策

首版使用 Kotlin、Jetpack Compose 和 Material 3。项目采用单一 `app` 模块作为静态 UI 骨架，最低支持 Android 8.0（API 26），编译/目标 SDK 为 35。

## 原因

- Compose 有利于快速迭代自适应、沉浸式阅读界面与主题 token。
- API 26 覆盖绝大多数仍在使用的 Android 设备，同时避免过多旧版兼容成本。
- 在业务边界稳定前保持单模块，避免过早拆分带来的维护噪声。

## 固定版本策略

- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21
- Compose BOM 2024.12.01
- Gradle Wrapper 8.9

以上版本由 `gradle/libs.versions.toml` 与 `gradle/wrapper/gradle-wrapper.properties` 固定。Java/Kotlin 生成 JVM 1.8 字节码，以保持 Android 构建兼容性；本机可用 JDK 21 作为编译器运行环境。

## 后果

后续新增 Room、DataStore、WorkManager 和 PdfRenderer 时，应按功能需要引入依赖；不要在当前阶段提前加入网络或服务端 SDK。
