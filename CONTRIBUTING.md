# 贡献指南

## 开发环境

- JDK 17+
- Android Studio / IntelliJ IDEA（Kotlin Multiplatform 插件）
- 无需安装 Gradle，使用仓库内置 `./gradlew`

## 构建命令

```bash
./gradlew build              # 两个目标全量编译
./gradlew :compileKotlinJvm  # 只编译桌面目标，最快
./gradlew jvmTest            # Compose UI 交互回归
./gradlew allTests           # 全部测试
```

本仓库可作为独立工程构建，也可被宿主以源码模块引入；
被引入时宿主的 `gradle.properties` 与版本目录生效，因此新增依赖需同时
在宿主的 `gradle/libs.versions.toml` 中登记同名 alias。

## 代码风格约定

- 未引入 ktlint / detekt / spotless，请自觉遵循
  [Kotlin 官方代码规范](https://kotlinlang.org/docs/coding-conventions.html)。
- 公开 API（`public`）需配套 KDoc；仅内部使用的声明标记 `internal`。
  注意 `internal` 在 KMP 下不能跨 source set 被普通代码引用，但 `expect`/`actual` 成对声明可以。
- 用户可见文案使用资源字符串（`src/commonMain/composeResources`），避免硬编码（i18n）。
- 不得引入宿主 App 的包（`zhiqiu.app.fileobservers.*`）；宿主能力一律经 `EditorFileBridge` 注入。
- 调试用的 `println` / `printStackTrace` 不应提交。

## 提交流程

- 分支命名：`feat/`、`fix/`、`refactor/` 等。
- 提交前确保 `./gradlew build` 与测试通过。
- 宿主以 git submodule 引用固定 commit：这里推送到 `master` 后，
  宿主需再提交一次 submodule 指针更新才会生效。
