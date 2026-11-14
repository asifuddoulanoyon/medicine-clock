package com.noyon.medclock

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Offline Bangla voice packed in the app (no internet, no download).
 * Used for medicine names that are not in the recorded name list:
 * the name is written in Bangla letters and turned into a short sound file once, when the medicine is saved.
 */
object BnTts {
    private var tts: OfflineTts? = null
    private var failed = false

    @Synchronized
    private fun engine(ctx: Context): OfflineTts? {
        if (tts != null || failed) return tts
        try {
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "bntts/model.int8.onnx",
                        tokens = "bntts/tokens.txt",
                        lengthScale = 1.1f
                    ),
                    numThreads = 2,
                    provider = "cpu"
                )
            )
            tts = OfflineTts(assetManager = ctx.applicationContext.assets, config = cfg)
        } catch (e: Throwable) {
            failed = true
            tts = null
        }
        return tts
    }

    fun clipFile(ctx: Context, id: String): File = File(File(ctx.filesDir, "clips"), id)

    /** Writes the spoken text to filesDir/clips/<id> (a WAV file). Returns true when it worked. */
    @JvmStatic
    @Synchronized
    fun toFile(ctx: Context, id: String, text: String): Boolean {
        if (id.contains("/") || text.isBlank()) return false
        return try {
            val t = engine(ctx) ?: return false
            val audio = t.generate(text = text, sid = 0, speed = 1.0f)
            if (audio.samples.isEmpty()) return false
            val f = clipFile(ctx, id)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "$id.tmp.wav")
            val ok = audio.save(tmp.absolutePath)
            ok && tmp.renameTo(f)
        } catch (e: Throwable) {
            false
        }
    }

    @JvmStatic
    fun exists(ctx: Context, id: String): Boolean = !id.contains("/") && clipFile(ctx, id).length() > 1000
}
