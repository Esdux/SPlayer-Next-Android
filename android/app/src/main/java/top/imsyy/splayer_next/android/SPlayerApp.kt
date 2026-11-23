package top.imsyy.splayer_next.android

import android.app.Application
import top.imsyy.splayer_next.android.playback.PlaybackEngine

/**
 * 进程级单例，完成原生服务的装配
 */
class SPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 向系统显式声明主线程调度意图（渲染/输入优先、top-app 组）
        ThreadTuning.applyUiThread()
        instance = this
        SPlayerContext.attach(this)
        AppPaths.init(this)
        PlaybackEngine.init(this)
        PlaybackEngine.bind { channel, json -> EventBus.emitRaw(channel, json) }
    }

    companion object {
        lateinit var instance: SPlayerApp
            private set
    }
}
