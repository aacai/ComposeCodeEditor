package zhiqiu.codeeditor.components

/** 当前是否运行在桌面（JVM）端，Android 恒为 false。 */
internal expect fun isDesktopPlatform(): Boolean
