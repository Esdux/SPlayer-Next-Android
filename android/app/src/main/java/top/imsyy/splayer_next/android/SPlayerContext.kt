package top.imsyy.splayer_next.android

import android.content.Context

/**
 * 全局 Context 访问点，供无 Context 参数的引擎使用
 */
object SPlayerContext {
    lateinit var app: Context
        private set

    /**
     * 注入应用上下文
     * @param context - 应用上下文
     */
    fun attach(context: Context) {
        app = context.applicationContext
    }

    /**
     * 取应用上下文
     * @returns 应用上下文
     */
    fun get(): Context = app
}
