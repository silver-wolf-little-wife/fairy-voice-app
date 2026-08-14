// SPDX-License-Identifier: AGPL-3.0-only
/**
 * 本地 ASR（P2）：sherpa-onnx + paraformer-zh-small。
 *
 * - 模型打包在 assets/models/ 下，通过 AssetManager 直接加载（sherpa-onnx Android API 支持），无需拷贝。
 * - 懒加载：首次识别时才创建 OfflineRecognizer（加载模型 ~数十 MB 内存）；释放可调 [release]。
 * - 识别输入：16kHz / 16bit / 单声道 WAV（与 AudioRecorder 产物一致）。
 *
 * 线程：调用方自行决定线程（VoiceController 在后台线程调用）；本类方法非线程安全（识别需串行）。
 */
package com.fairyvoice.app.audio

import android.content.Context
import com.fairyvoice.app.util.LogFile
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

object OnnxAsr {

    private const val MODEL = "models/sherpa-onnx-paraformer-zh-small-2024-03-09/model.int8.onnx"
    private const val TOKENS = "models/sherpa-onnx-paraformer-zh-small-2024-03-09/tokens.txt"
    /** 热词文件（方案B）：提升「Fairy」识别概率。 */
    private const val HOTWORDS = "models/hotwords.txt"
    private const val HOTWORDS_SCORE = 2.0f
    private const val SAMPLE_RATE = 16_000
    private const val FEATURE_DIM = 80

    @Volatile
    private var recognizer: OfflineRecognizer? = null
    private val initLock = Any()

    val isLoaded: Boolean get() = recognizer != null

    /** 懒加载模型；成功返回 true。失败记录日志（模型缺失/损坏）。 */
    fun ensureLoaded(context: Context): Boolean {
        if (recognizer != null) return true
        synchronized(initLock) {
            if (recognizer != null) return true
            return try {
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = SAMPLE_RATE,
                        featureDim = FEATURE_DIM,
                        dither = 0f,
                    ),
                    modelConfig = OfflineModelConfig(
                        paraformer = OfflineParaformerModelConfig(model = MODEL),
                        tokens = TOKENS,
                        numThreads = 2,
                        provider = "cpu",
                    ),
                    // 方案B：热词偏置，提高「Fairy」识别概率
                    hotwordsFile = HOTWORDS,
                ).apply { hotwordsScore = HOTWORDS_SCORE }
                recognizer = OfflineRecognizer(context.assets, config)
                LogFile.d("OnnxAsr.loaded model=paraformer-zh-small")
                true
            } catch (e: Throwable) {
                LogFile.e("OnnxAsr.init fail ${e.message}")
                recognizer = null
                false
            }
        }
    }

    fun release() {
        synchronized(initLock) {
            recognizer?.release()
            recognizer = null
        }
    }

    /**
     * 识别 WAV（16k/16bit/mono）→ 识别文本；识别为空或失败返回 null。
     * 调用前需 [ensureLoaded] 成功。
     */
    fun recognize(wavFile: File): String? {
        val rec = recognizer ?: return null
        val pcm = readWavPcm(wavFile) ?: return null
        if (pcm.isEmpty()) return null
        return try {
            val stream = rec.createStream()
            stream.acceptWaveform(pcm, SAMPLE_RATE)
            rec.decode(stream)
            val text = rec.getResult(stream).text.trim()
            // 方案A：后处理纠错（ferry/fairy → Fairy），再判空
            val corrected = AsrCorrection.correct(text)
            if (corrected.isEmpty()) null else corrected
        } catch (e: Throwable) {
            LogFile.e("OnnxAsr.recognize fail ${e.message}")
            null
        }
    }

    /** 读取 WAV，跳过 44 字节头，PCM 转归一化 FloatArray。 */
    private fun readWavPcm(file: File): FloatArray? = runCatching {
        val data = file.readBytes()
        if (data.size <= WavHeader.HEADER_SIZE) return null
        val pcm = data.copyOfRange(WavHeader.HEADER_SIZE, data.size)
        val out = FloatArray(pcm.size / 2)
        for (i in out.indices) {
            val s = (pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)
            out[i] = s / 32768.0f
        }
        out
    }.getOrNull()
}
