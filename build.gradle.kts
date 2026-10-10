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
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
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
