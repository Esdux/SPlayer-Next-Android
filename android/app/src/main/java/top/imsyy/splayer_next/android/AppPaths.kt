package top.imsyy.splayer_next.android

import android.content.Context
import java.io.File

/**
 * 应用数据目录，等价于桌面端 {userData}/app-data
 */
object AppPaths {

    lateinit var root: File
        private set

    lateinit var configDir: File
        private set

    lateinit var cacheDir: File
        private set

    lateinit var coversDir: File
        private set

    lateinit var logsDir: File
        private set

    lateinit var downloadsDir: File
        private set

    /**
     * 初始化目录结构
     * @param context - 应用上下文
     */
    fun init(context: Context) {
        root = File(context.filesDir, "app-data").apply { mkdirs() }
        configDir = File(root, "config").apply { mkdirs() }
        cacheDir = File(root, "cache").apply { mkdirs() }
        coversDir = File(cacheDir, "covers").apply { mkdirs() }
        logsDir = File(root, "logs").apply { mkdirs() }
        downloadsDir = File(context.getExternalFilesDir(null), "Music").apply { mkdirs() }
    }
}
