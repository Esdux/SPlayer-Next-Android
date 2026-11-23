package top.imsyy.splayer_next.android

import android.os.Process

/**
 * 线程声明：向系统显式声明本进程各线程的调度意图
 *
 * 目标机为 4+4 架构（4×A78 + 4×A55），App 层无法直接绑核，
 * 这里通过「优先级 + 线程组」把线程交给调度器对应到合适的核：
 * - UI/渲染相关线程进 top-app 组，调度器优先给大核
 * - 音频线程进 audio 组，避免被普通任务抢占
 * - 下载/IO 线程进 background 组，倾向小核，省电且不挤占前台
 *
 * setThreadGroup 在编译 SDK 中是 @hide，运行时存在，故用反射调用，失败静默。
 */
object ThreadTuning {

    /** top-app 组：前台 UI 与渲染 */
    private const val GROUP_TOP_APP = 6
    /** 音频组 */
    private const val GROUP_AUDIO_APP = 4
    /** 后台非交互组 */
    private const val GROUP_BG_NONINTERACTIVE = 1
    /** 默认组 */
    private const val GROUP_DEFAULT = 0

    /**
     * 主线程：渲染与输入优先，进 top-app 组
     */
    fun applyUiThread() {
        runCatching { Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_DISPLAY) }
        applyGroup(GROUP_TOP_APP)
    }

    /**
     * 音频相关线程：高于普通任务，进 audio 组
     */
    fun applyAudioThread() {
        runCatching { Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_URGENT_AUDIO) }
        applyGroup(GROUP_AUDIO_APP)
    }

    /**
     * 桥接工作线程：显式声明为默认优先级与默认组，不与 UI 抢时间片
     */
    fun applyWorkerThread() {
        runCatching { Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_DEFAULT) }
        applyGroup(GROUP_DEFAULT)
    }

    /**
     * 下载/IO 线程：后台优先级，进后台组（调度器倾向小核）
     */
    fun applyBackgroundThread() {
        runCatching { Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_BACKGROUND) }
        applyGroup(GROUP_BG_NONINTERACTIVE)
    }

    /**
     * 反射设置当前线程所属调度组
     * @param group - Process.THREAD_GROUP_* 的数值
     */
    private fun applyGroup(group: Int) {
        runCatching {
            val method = Class.forName("android.os.Process")
                .getMethod("setThreadGroup", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            method.invoke(null, Process.myTid(), group)
        }
    }
}
