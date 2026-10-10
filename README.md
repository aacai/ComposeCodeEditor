# Compose Code Editor

Kotlin Multiplatform + Compose 实现的原生代码编辑器组件，支持桌面（JVM）与 Android，
既可整屏使用，也可作为可嵌入的编辑组件复用。不依赖任何 App 层代码。

## 特性

- **语法高亮**：基于正则的多语言高亮（Kotlin、Java、Smali、INI、YAML、TOML、Log 等，见 `EditorLanguage`），
  由 `HighlightCache` 做增量更新，几千行文件仍保持流畅。
- **文本模型**：`GapBuffer` / `LineBuffer` / `ChunkedLineBuffer` 提供高效随机读写与按行管理，
  超大文件走分块加载。
- **选区与拖拽**：Android 风格选区手柄、拖拽选取、点击行号栏选中整行。
- **撤销 / 重做**：基于 `EditOp` 的 `UndoManager`。
- **查找替换**：支持大小写、全字、正则。
- **主题**：浅色 / 深色 `EditorTheme`，token 配色可定制。
- **健壮性**：崩溃边界（`RootCrashBoundary` / `EditorErrorPage`）。

## 公开 API

| 类型 | 说明 |
|------|------|
| `NativeCodeEditor` | 核心编辑器 Composable |
| `NativeEditorState` / `NativeEditorEvent` / `NativeEditorViewModel` | 编辑器状态、事件与逻辑 |
| `EditorTheme` / `EditorLanguage` | 主题与语言配置 |
| `SyntaxHighlighter` / `HighlightCache` | 语法高亮扩展接口与增量缓存 |
| `EditorFileBridge` | 宿主文件能力桥接（远端回传、二进制 XML 回编译） |
| `NativeEditorStatusBar` / `NativeSearchReplacePanel` / `NativeGoToLineDialog` / `TranslateResultDialog` | 配套面板与弹窗 |
| `NativeEditorPreviewContent` | 预览用装配入口（含浅色/深色两个入口） |

## 集成方式

作为源码模块被宿主 Gradle 构建包含：

```kotlin
// settings.gradle.kts
include(":codeeditor")
project(":codeeditor").projectDir = file("libs/ComposeCodeEditor")

// 宿主模块 build.gradle.kts
dependencies {
    api(project(":codeeditor"))
}
```

宿主若需要 FTP/SFTP 回传或 APK 内二进制 XML 回编译，实现 `EditorFileBridge` 并注入：

```kotlin
NativeEditorViewModel(fileBridge = MyBridge)
```

未注入时编辑器只做本地文件读写。

## 构建与测试

```bash
./gradlew build          # 编译 android + jvm 两个目标
./gradlew jvmTest        # 编辑器交互回归测试（Compose UI Test）
./gradlew allTests       # 含 commonTest 的缓冲/高亮/ViewModel 单测
```

## 目录结构

```
src/commonMain/kotlin/zhiqiu/codeeditor/
├── NativeEditorState.kt / NativeEditorEvent.kt / NativeEditorViewModel.kt
├── EditorLanguage.kt / EditorFileBridge.kt
├── buffer/       # GapBuffer、LineBuffer、分块缓冲与大文件策略
├── highlight/    # 正则高亮器、token 与规则
├── render/       # EditorTheme、光标几何、括号 guides
├── text/         # 选区、撤销、折叠、单词边界、XML 格式化
└── components/   # NativeCodeEditor 与状态栏、查找替换、弹窗
src/androidMain / src/jvmMain  # 滚动条、字体、剪贴板工具栏的平台实现
src/commonMain/composeResources # 编辑器自有文案（values / values-zh）
```

## 许可

MIT，见 `LICENSE`。
