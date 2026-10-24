import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

compose.resources {
    publicResClass = true
    packageOfResClass = "zhiqiu.codeeditor.generated.resources"
    generateResClass = always
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm()

    android {
        namespace = "zhiqiu.codeeditor"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material3.adaptive)
            implementation(libs.compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.collection)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.okio)
        }
        jvmMain.dependencies {
            // 桌面（JVM）tree-sitter：官方 ktreesitter 绑定 + 16 个语法包。加载失败自动回退正则高亮。
            implementation(libs.ktreesitter)
            implementation(libs.treesitter.languages.java)
            implementation(libs.treesitter.languages.kotlin)
            implementation(libs.treesitter.languages.xml)
            implementation(libs.treesitter.languages.toml)
            implementation(libs.treesitter.languages.yaml)
            implementation(libs.treesitter.languages.json)
            implementation(libs.treesitter.languages.python)
            implementation(libs.treesitter.languages.javascript)
            implementation(libs.treesitter.languages.typescript)
            implementation(libs.treesitter.languages.html)
            implementation(libs.treesitter.languages.css)
            implementation(libs.treesitter.languages.c)
            implementation(libs.treesitter.languages.cpp)
            implementation(libs.treesitter.languages.go)
            implementation(libs.treesitter.languages.rust)
            implementation(libs.treesitter.languages.bash)
        }
        androidMain.dependencies {
            // Android tree-sitter：AndroidIDE 的 C 绑定（无 Java record，可正常打包）+ 其已发版的
            // 7 个语法包。仅 AndroidIDE 发了版的语言走真解析，其余语言由调用方回退正则高亮。
            // ktreesitter 的 Android 变体带 record，AGP 9 的 D8 脱糖会让 APK 构建失败，故 Android 不用它。
            implementation(libs.android.tree.sitter)
            implementation(libs.android.ts.kotlin)
            implementation(libs.android.ts.java)
            implementation(libs.android.ts.json)
            implementation(libs.android.ts.xml)
            implementation(libs.android.ts.python)
            implementation(libs.android.ts.c)
            implementation(libs.android.ts.cpp)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.compose.ui.test)
        }
        // Compose 桌面 UI 测试（jvmTest）需要 Skiko 原生运行时加载 .dylib/.so，
        // 该原生库不会随 ui-test 自动进入 jvmTest 运行时类路径，按当前 OS/架构显式补上。
        val skikoRuntime: String? =
            when {
                System.getProperty("os.name").contains("Mac", ignoreCase = true) &&
                    System.getProperty("os.arch").contains("aarch64") ->
                    "org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:0.144.6"
                System.getProperty("os.name").contains("Mac", ignoreCase = true) &&
                    !System.getProperty("os.arch").contains("aarch64") ->
                    "org.jetbrains.skiko:skiko-awt-runtime-macos-x64:0.144.6"
                System.getProperty("os.name").contains("Linux", ignoreCase = true) ->
                    "org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.144.6"
                System.getProperty("os.name").contains("Windows", ignoreCase = true) ->
                    "org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.144.6"
                else -> null
            }
        if (skikoRuntime != null) {
            jvmTest.dependencies {
                implementation(libs.compose.ui.test)
                implementation(skikoRuntime)
            }
        }
    }
}
