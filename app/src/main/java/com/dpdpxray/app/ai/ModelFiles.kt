package com.dpdpxray.app.ai

import android.content.Context
import java.io.File

/**
 * Models are pushed to the app's external files dir (no root needed):
 *   adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.dpdpxray.app/files/models/
 */
object ModelFiles {
    fun dir(context: Context): File = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    private fun all(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && (f.name.endsWith(".litertlm") || f.name.endsWith(".tflite")) }
            ?.toList().orEmpty()

    /** The main multimodal LLM (prefers Gemma 4 E2B, then any Gemma 3n/Gemma .litertlm that is not an embedder). */
    fun mainModel(context: Context): File? {
        val llms = all(context).filter { it.name.endsWith(".litertlm") && !it.name.contains("embed", ignoreCase = true) && !isNpu(it) }
        return llms.firstOrNull { it.name.contains("gemma-4", true) && it.name.contains("e2b", true) }
            ?: llms.firstOrNull { it.name.contains("gemma-4", true) }
            ?: llms.firstOrNull { it.name.contains("3n", true) }
            ?: llms.firstOrNull()
    }

    /** A Qualcomm NPU-compiled bundle (e.g. Gemma 3 1B for SM8850), if present. */
    fun npuModel(context: Context): File? = all(context).firstOrNull { isNpu(it) }

    fun embeddingModel(context: Context): File? = all(context).firstOrNull { it.name.contains("embed", ignoreCase = true) }

    private fun isNpu(f: File) = listOf("npu", "sm8850", "sm8750", "qualcomm").any { f.name.contains(it, ignoreCase = true) }

    fun describe(context: Context): String =
        "Models folder: ${dir(context).absolutePath}\n" +
            (all(context).joinToString("\n") { "• ${it.name} (${it.length() / (1024 * 1024)} MB)" }.ifEmpty { "• (empty)" })
}
