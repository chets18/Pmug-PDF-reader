package com.example.domain.tts

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Owns Piper model storage and installation. The voice is intentionally not an APK asset. */
class PiperModelInstaller(private val filesDir: File) {

    data class InstalledModel(
        val modelFile: File,
        val tokensFile: File,
        val phonemeDataDir: File
    )

    private val modelRoot = File(filesDir, "tts/piper")
    private val installedDir = File(modelRoot, MODEL_DIRECTORY)
    private val completeMarker = File(installedDir, ".installed")

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Synchronized
    @Throws(IOException::class)
    fun ensureInstalled(progress: (Int) -> Unit = {}): InstalledModel {
        validInstalledModel()?.let {
            logInventory("existing valid model")
            Log.i(TAG, "Existing model validated model=${it.modelFile.absolutePath} bytes=${it.modelFile.length()} tokens=${it.tokensFile.absolutePath} espeak=${it.phonemeDataDir.absolutePath}")
            return it
        }

        Log.i(TAG, "Model download started url=$MODEL_ARCHIVE_URL destination=${modelRoot.absolutePath}")
        logInventory("before download/recovery")
        Log.i(TAG, "Storage path check filesDir=${filesDir.absolutePath} exists=${filesDir.exists()} directory=${filesDir.isDirectory} writable=${filesDir.canWrite()}")
        if (!modelRoot.mkdirs() && !modelRoot.isDirectory) {
            val error = IOException("Could not create Piper model directory ${modelRoot.absolutePath}")
            Log.e(TAG, "Model storage path creation failed", error)
            throw error
        }
        val stagingDir = File(modelRoot, "$MODEL_DIRECTORY.installing")
        val archiveFile = File(modelRoot, "$MODEL_DIRECTORY.tar.bz2")
        stagingDir.deleteRecursively()
        archiveFile.delete()

        try {
            downloadArchive(archiveFile, progress)
            progress(85)
            extractArchive(archiveFile, stagingDir)
            progress(98)

            val modelDir = File(stagingDir, MODEL_DIRECTORY)
            val model = File(modelDir, MODEL_FILE)
            val tokens = File(modelDir, TOKENS_FILE)
            val phonemeData = File(modelDir, PHONEME_DATA_DIRECTORY)
            Log.i(TAG, "Extracted candidate files model=${model.absolutePath} bytes=${model.length()} tokens=${tokens.absolutePath} bytes=${tokens.length()} espeak=${phonemeData.absolutePath} exists=${phonemeData.isDirectory}")
            if (!model.isFile || model.length() < MIN_MODEL_BYTES || !tokens.isFile || !phonemeData.isDirectory) {
                throw IOException("Downloaded Piper voice package is incomplete")
            }

            File(modelDir, ".installed").writeText(MODEL_VERSION)
            installedDir.deleteRecursively()
            if (!modelDir.renameTo(installedDir)) {
                throw IOException("Could not finalize Piper voice installation")
            }
            val installed = checkNotNull(validInstalledModel())
            logInventory("installation complete")
            Log.i(TAG, "Model installation verified modelBytes=${installed.modelFile.length()} tokensBytes=${installed.tokensFile.length()} espeak=${installed.phonemeDataDir.absolutePath}")
            return installed
        } catch (error: Throwable) {
            Log.e(TAG, "Model install/extraction failed root=${modelRoot.absolutePath}", error)
            throw error
        } finally {
            archiveFile.delete()
            stagingDir.deleteRecursively()
        }
    }

    fun logInventory(reason: String) {
        val entries = if (modelRoot.exists()) modelRoot.walkTopDown().filter { it.isFile }.map {
            "${it.relativeTo(modelRoot).path}=${it.length()}B"
        }.toList() else emptyList()
        Log.i(TAG, "files/tts/piper inventory ($reason): root=${modelRoot.absolutePath} exists=${modelRoot.exists()} entries=$entries")
    }

    fun isInstalled(): Boolean = validInstalledModel() != null

    /** Removes the downloaded voice files so the feature can release its model storage. */
    @Synchronized
    fun deleteDownloadedModel(): Boolean = installedDir.deleteRecursively() || !installedDir.exists()

    private fun validInstalledModel(): InstalledModel? {
        if (!completeMarker.isFile || completeMarker.readText() != MODEL_VERSION) return null
        val modelDir = installedDir
        val model = File(modelDir, MODEL_FILE)
        val tokens = File(modelDir, TOKENS_FILE)
        val phonemeData = File(modelDir, PHONEME_DATA_DIRECTORY)
        if (!model.isFile || model.length() < MIN_MODEL_BYTES || !tokens.isFile || !phonemeData.isDirectory) return null
        return InstalledModel(model, tokens, phonemeData)
    }

    @Throws(IOException::class)
    private fun downloadArchive(destination: File, progress: (Int) -> Unit) {
        val request = Request.Builder().url(MODEL_ARCHIVE_URL).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Piper model download failed (HTTP ${response.code})")
            val body = response.body ?: throw IOException("Piper model download returned no data")
            if (body.contentLength() > MAX_ARCHIVE_BYTES) throw IOException("Piper model archive is larger than expected")
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var totalBytes = 0L
                    val contentLength = body.contentLength()
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        if (totalBytes > MAX_ARCHIVE_BYTES) throw IOException("Piper model archive is larger than expected")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        if (contentLength > 0L) progress((totalBytes * 80L / contentLength).toInt().coerceIn(0, 80))
                    }
                    output.fd.sync()
                    val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    val sizeCheck = when {
                        contentLength < 0L -> "UNKNOWN (server omitted Content-Length)"
                        totalBytes == contentLength -> "PASS"
                        else -> "FAIL"
                    }
                    Log.i(TAG, "Model download finished bytes=$totalBytes contentLength=$contentLength sizeCheck=$sizeCheck sha256=$sha256 archive=${destination.absolutePath}")
                    if (contentLength >= 0L && totalBytes != contentLength) {
                        throw IOException("Piper model download size mismatch: expected $contentLength bytes, received $totalBytes")
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun extractArchive(archive: File, destination: File) {
        destination.mkdirs()
        val canonicalRoot = destination.canonicalFile
        var extractedBytes = 0L
        BufferedInputStream(archive.inputStream()).use { buffered ->
            BZip2CompressorInputStream(buffered).use { bzip ->
                TarArchiveInputStream(bzip).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (entry.isSymbolicLink || entry.isLink || entry.isDirectory) continue
                        val outputFile = File(destination, entry.name).canonicalFile
                        if (outputFile != canonicalRoot && !outputFile.path.startsWith(canonicalRoot.path + File.separator)) {
                            throw IOException("Piper archive contains an invalid path")
                        }
                        extractedBytes += entry.size
                        if (entry.size < 0 || extractedBytes > MAX_EXTRACTED_BYTES) {
                            throw IOException("Piper model package is larger than expected")
                        }
                        outputFile.parentFile?.mkdirs()
                        val copiedBytes = FileOutputStream(outputFile).use { output -> tar.copyTo(output) }
                        if (copiedBytes != entry.size) {
                            throw IOException("Piper extraction size mismatch for ${entry.name}: expected ${entry.size} bytes, extracted $copiedBytes")
                        }
                        Log.i(TAG, "Extracted ${entry.name} expectedBytes=${entry.size} extractedBytes=$copiedBytes")
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "PmugPiper"
        const val MODEL_VERSION = "sherpa-onnx-v1.13.8-lessac-medium"
        const val MODEL_DIRECTORY = "vits-piper-en_US-lessac-medium"
        const val MODEL_FILE = "en_US-lessac-medium.onnx"
        const val TOKENS_FILE = "tokens.txt"
        const val PHONEME_DATA_DIRECTORY = "espeak-ng-data"
        const val MODEL_ARCHIVE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2"
        const val MIN_MODEL_BYTES = 1_000_000L
        const val MAX_ARCHIVE_BYTES = 150L * 1024 * 1024
        const val MAX_EXTRACTED_BYTES = 250L * 1024 * 1024
    }
}
