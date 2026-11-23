package top.imsyy.splayer_next.android.audiofx

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 安卓音频效果器集合：均衡器、前置增益与频谱采集
 *
 * 所有效果器都绑定在 ExoPlayer 的 audioSessionId 上，会话变化（A/B 槽位切换、
 * 播放器重建）时必须先 [release] 再 [attach]，否则效果器会挂在已销毁的会话上
 *
 * 设计原则：任何一步失败都只降级为「无操作」，绝不向调用方抛异常，
 * 均衡器失效不影响播放，频谱失效只是没有柱子
 */
object AudioEffects {

    private const val TAG = "SPlayerAudioFx"

    /** 渲染层 EqualizerDialog 的 10 段中心频率（Hz），顺序必须与前端一致 */
    private val EQ_FREQUENCIES =
        floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    /** 每帧输出频点数，对应渲染层 BottomSpectrum 的 FFT_SIZE */
    private const val FFT_BINS = 128

    /** Visualizer 采集窗口，2 的幂，最终取设备支持范围内的合法值 */
    private const val CAPTURE_SIZE = 1024

    /** 频谱可视下限，低于该 dB 视为静音 */
    private const val FFT_FLOOR_DB = -70f

    /** 频谱可视上限 */
    private const val FFT_CEIL_DB = 0f

    /** 均衡器，未绑定或初始化失败时为 null */
    private var equalizer: Equalizer? = null

    /** 前置增益，Android 9 以下没有 DynamicsProcessing，保持 null */
    private var preamp: DynamicsProcessing? = null

    /** 频谱采集器 */
    private var visualizer: Visualizer? = null

    /** 已绑定的音频会话 id，0 表示未绑定 */
    private var boundSession = 0

    /** 均衡器开关的用户意图，晚于绑定到达时补应用 */
    private var eqEnabled = false

    /** 用户下发的 10 段增益（dB），未下发前全 0 */
    private var bandGains = FloatArray(EQ_FREQUENCIES.size)

    /** 用户下发的前置增益（dB） */
    private var preampGainDb = 0f

    /** 是否希望采集频谱，由 setFftEnabled 驱动 */
    private var wantFft = false

    /** 最新一帧频谱，null 表示还没有数据 */
    @Volatile
    private var latestFrame: JSONObject? = null

    /** 当前绑定的音频会话 id，0 表示未绑定 */
    fun sessionId(): Int = boundSession

    /** 均衡器是否真的在生效（设备初始化成功且已打开） */
    fun isEqualizerActive(): Boolean =
        equalizer?.let { runCatching { it.enabled }.getOrDefault(false) } == true

    /** 频谱是否真的在采集 */
    fun isFftActive(): Boolean = visualizer != null && wantFft

    /**
     * 是否已获得录音权限
     * Visualizer 读取输出混音需要 RECORD_AUDIO 运行时授权，仅清单声明不够
     * @param context - 任意上下文
     */
    fun hasCapturePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 绑定到指定音频会话，重复调用幂等
     * @param context - 应用上下文，用于校验录音权限
     * @param session - ExoPlayer 当前的 audioSessionId，0 表示尚未分配
     */
    @Synchronized
    fun attach(context: Context, session: Int) {
        if (session <= 0) return
        if (session == boundSession) {
            // 会话没变：只补齐上次初始化失败的部件，不动已经在工作的效果器
            if (equalizer == null) bindEqualizer(session)
            if (wantFft && visualizer == null) bindVisualizer(context, session)
            return
        }
        release()
        boundSession = session
        bindEqualizer(session)
        bindPreamp(session)
        if (wantFft) bindVisualizer(context, session)
        Log.i(TAG, "效果器已绑定 session=" + session)
    }

    /**
     * 释放全部效果器，可在任意时刻安全调用
     */
    @Synchronized
    fun release() {
        runCatching { visualizer?.setDataCaptureListener(null, 0, false, false) }
        runCatching { visualizer?.release() }
        visualizer = null
        runCatching { preamp?.release() }
        preamp = null
        runCatching { equalizer?.release() }
        equalizer = null
        boundSession = 0
        latestFrame = null
    }

    /**
     * 设置均衡器开关
     * @param enabled - 是否启用
     */
    @Synchronized
    fun setEqualizerEnabled(enabled: Boolean) {
        eqEnabled = enabled
        val eq = equalizer ?: return
        runCatching { eq.setEnabled(enabled) }
            .onFailure { Log.w(TAG, "均衡器开关设置失败，保持无操作", it) }
    }

    /**
     * 下发 10 段增益，单位 dB
     * @param gains - 10 个 dB 值，顺序与 [EQ_FREQUENCIES] 一致
     */
    @Synchronized
    fun setBands(gains: FloatArray) {
        val target = FloatArray(EQ_FREQUENCIES.size)
        for (i in target.indices) {
            target[i] = if (i < gains.size) gains[i].coerceIn(-15f, 15f) else 0f
        }
        bandGains = target
        equalizer?.let { applyBands(it) }
    }

    /**
     * 下发前置增益，单位 dB
     * @param db - 允许范围 -12..12，与前端滑杆一致
     */
    @Synchronized
    fun setPreampGain(db: Float) {
        preampGainDb = db.coerceIn(-12f, 12f)
        val dp = preamp ?: return
        runCatching { dp.setInputGainAllChannelsTo(preampGainDb) }
            .onFailure { Log.w(TAG, "前置增益写入失败，保持无操作", it) }
    }

    /**
     * 开启或关闭频谱采集
     * @param context - 应用上下文
     * @param enabled - 是否采集
     */
    @Synchronized
    fun setFftEnabled(context: Context?, enabled: Boolean) {
        wantFft = enabled
        if (!enabled) {
            runCatching { visualizer?.setDataCaptureListener(null, 0, false, false) }
            runCatching { visualizer?.release() }
            visualizer = null
            latestFrame = null
            return
        }
        val ctx = context ?: return
        // 会话还没绑定就先记下意图，attach 时自动补建
        if (boundSession <= 0 || visualizer != null) return
        bindVisualizer(ctx, boundSession)
    }

    /**
     * 取最新一帧频谱
     * @returns {ldata,rdata} 结构，没有数据时为 null
     */
    fun latestFftFrame(): JSONObject? = latestFrame

    /**
     * 构造一帧全零频谱，用于播放暂停后让渲染层柱子回落
     * @returns {ldata,rdata} 结构，两个数组等长
     */
    fun silentFftFrame(): JSONObject {
        val left = JSONArray()
        val right = JSONArray()
        for (i in 0 until FFT_BINS) {
            left.put(0.0)
            right.put(0.0)
        }
        return JSONObject().put("ldata", left).put("rdata", right)
    }

    /**
     * 绑定均衡器
     * 设备的频段数量与中心频率因机型而异，这里按真实中心频率把 10 段曲线映射上去
     * @param session - 音频会话 id
     */
    private fun bindEqualizer(session: Int) {
        val eq = runCatching { Equalizer(0, session) }.getOrElse {
            Log.w(TAG, "Equalizer 初始化失败，均衡器降级为无操作", it)
            return
        }
        equalizer = eq
        applyBands(eq)
        runCatching { eq.setEnabled(eqEnabled) }
            .onFailure { Log.w(TAG, "均衡器开关应用失败", it) }
    }

    /**
     * 绑定前置增益
     * Equalizer 没有全局增益接口，Virtualizer 只做空间化，因此前置增益走
     * DynamicsProcessing 的输入增益（API 28+），系统更旧时降级为不生效
     * @param session - 音频会话 id
     */
    private fun bindPreamp(session: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.i(TAG, "系统版本低于 Android 9，前置增益降级为不生效")
            return
        }
        runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                2,
                false,
                0,
                false,
                0,
                false,
                0,
                false,
            ).build()
            val dp = DynamicsProcessing(0, session, config)
            dp.setInputGainAllChannelsTo(preampGainDb)
            dp.setEnabled(true)
            preamp = dp
        }.onFailure { Log.w(TAG, "DynamicsProcessing 初始化失败，前置增益降级为不生效", it) }
    }

    /**
     * 把 10 段曲线写到设备频段上
     * @param eq - 已绑定的均衡器
     */
    private fun applyBands(eq: Equalizer) {
        runCatching {
            val range = eq.bandLevelRange
            val minMb = range[0].toInt()
            val maxMb = range[1].toInt()
            val count = eq.numberOfBands.toInt()
            for (band in 0 until count) {
                // getCenterFreq 返回毫赫兹
                val centerHz = eq.getCenterFreq(band.toShort()) / 1000f
                val mb = (gainAt(centerHz) * 100f).roundToInt().coerceIn(minMb, maxMb)
                eq.setBandLevel(band.toShort(), mb.toShort())
            }
        }.onFailure { Log.w(TAG, "频段增益写入失败，均衡器保持无操作", it) }
    }

    /**
     * 在 10 段曲线上按对数频率插值取增益
     * @param freqHz - 目标频率（Hz）
     * @returns 该频率处的增益（dB）
     */
    private fun gainAt(freqHz: Float): Float {
        val frequencies = EQ_FREQUENCIES
        if (freqHz <= frequencies.first()) return bandGains.first()
        if (freqHz >= frequencies.last()) return bandGains.last()
        for (i in 0 until frequencies.size - 1) {
            val lo = frequencies[i]
            val hi = frequencies[i + 1]
            if (freqHz >= lo && freqHz <= hi) {
                val span = log10(hi) - log10(lo)
                if (span <= 0f) return bandGains[i]
                val t = (log10(freqHz) - log10(lo)) / span
                return bandGains[i] + (bandGains[i + 1] - bandGains[i]) * t
            }
        }
        return 0f
    }

    /**
     * 绑定频谱采集
     * @param context - 应用上下文
     * @param session - 音频会话 id
     */
    private fun bindVisualizer(context: Context, session: Int) {
        if (!hasCapturePermission(context)) {
            Log.i(TAG, "缺少 RECORD_AUDIO 运行时权限，频谱先降级为无数据")
            return
        }
        runCatching {
            val v = Visualizer(session)
            val range = Visualizer.getCaptureSizeRange()
            val size = CAPTURE_SIZE.coerceIn(range[0], range[1])
            v.setCaptureSize(size)
            // 采集回调频率取系统上限的一半（约 10Hz），播放引擎再按 50ms 节拍取用
            val rate = max(Visualizer.getMaxCaptureRate() / 2, 1000)
            v.setDataCaptureListener(captureListener, rate, false, true)
            v.setEnabled(true)
            visualizer = v
            Log.i(TAG, "频谱已绑定 session=" + session + " size=" + size + " rate=" + rate)
        }.onFailure { Log.w(TAG, "Visualizer 初始化失败，频谱降级为无数据", it) }
    }

    /** 频谱回调：系统在音频线程调用，这里只做纯计算并写 volatile 帧 */
    private val captureListener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
            // 只用 FFT，波形不参与展示
        }

        override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
            if (fft == null || fft.isEmpty()) return
            latestFrame = runCatching { buildFrame(fft, samplingRate) }.getOrNull()
        }
    }

    /**
     * 把 Visualizer 的 8bit FFT 转成渲染层要的 0..1 频点
     * Visualizer 约定：0 表示 -100dB，255 表示 0dB，前 captureSize/2 个点为有效频点
     * @param fft - 原始 FFT 字节数组
     * @param samplingRate - 采集采样率（Hz），仅用于标注
     * @returns {ldata,rdata}，两侧目前共用同一份混音频谱
     */
    private fun buildFrame(fft: ByteArray, samplingRate: Int): JSONObject {
        val usable = max(fft.size / 2, 1)
        val left = JSONArray()
        val right = JSONArray()
        for (i in 0 until FFT_BINS) {
            val from = ((i.toFloat() * usable) / FFT_BINS).toInt().coerceIn(0, usable - 1)
            val to = (((i + 1).toFloat() * usable) / FFT_BINS).toInt().coerceIn(from + 1, usable)
            var peakDb = FFT_FLOOR_DB
            for (j in from until to) {
                val db = (fft[j].toInt() and 0xFF) / 255f * 100f - 100f
                if (db > peakDb) peakDb = db
            }
            val level = ((peakDb - FFT_FLOOR_DB) / (FFT_CEIL_DB - FFT_FLOOR_DB)).coerceIn(0f, 1f)
            left.put(level.toDouble())
            // Visualizer 只提供混音后的单路频谱，左右声道给同一份数据，再由渲染层镜像排布
            right.put(level.toDouble())
        }
        return JSONObject()
            .put("ldata", left)
            .put("rdata", right)
            .put("sampleRate", samplingRate)
    }
}
