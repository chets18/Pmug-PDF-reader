package com.example.domain.tts

import android.os.StatFs
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Owns Piper model storage, multi-voice installation, shared espeak-ng-data, and cancellation. */
class PiperModelInstaller(private val filesDir: File) {

    data class InstalledModel(
        val voiceId: String,
        val modelFile: File,
        val tokensFile: File,
        val phonemeDataDir: File
    )

    private val modelRoot = File(filesDir, "tts/piper")
    private val sharedEspeakDir = File(modelRoot, "espeak-ng-data")
    private val activeCall = AtomicReference<Call?>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Synchronized
    @Throws(IOException::class)
    fun ensureInstalled(voice: PiperVoice = PiperVoiceCatalog.voices.first(), progress: (Int) -> Unit = {}): InstalledModel {
        validInstalledModel(voice.id)?.let {
            Log.i(TAG, "Existing voice validated id=${voice.id} model=${it.modelFile.absolutePath}")
            return it
        }

        val stat = StatFs(filesDir.path)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        val requiredBytes = voice.expectedDownloadSize + (50L * 1024 * 1024)
        if (availableBytes < requiredBytes) {
            throw IOException("Not enough storage space. Required ~${requiredBytes / (1024*1024)}MB free, available ${availableBytes / (1024*1024)}MB.")
        }

        val installedDir = File(modelRoot, "vits-piper-${voice.id}")
        val stagingDir = File(modelRoot, "vits-piper-${voice.id}.installing")
        val archiveFile = File(modelRoot, "vits-piper-${voice.id}.tar.bz2")
        stagingDir.deleteRecursively()
        archiveFile.delete()

        try {
            downloadArchive(voice.downloadUrl, archiveFile, voice.expectedDownloadSize, progress)
            progress(85)
            extractArchive(archiveFile, stagingDir, voice.id)
            progress(98)

            val modelDir = File(stagingDir, "vits-piper-${voice.id}")
            val modelFileName = "${voice.id}.onnx"
            val model = File(modelDir, modelFileName).takeIf { it.isFile } ?: modelDir.listFiles()?.firstOrNull { it.extension == "onnx" }
            val tokens = File(modelDir, "tokens.txt")
            val phonemeSource = File(modelDir, "espeak-ng-data")

            if (model == null || !model.isFile || !tokens.isFile) {
                throw IOException("Downloaded Piper voice package is incomplete for ${voice.id}")
            }

            // Share espeak-ng-data
            if (phonemeSource.isDirectory) {
                if (!sharedEspeakDir.exists()) {
                    phonemeSource.copyRecursively(sharedEspeakDir, overwrite = true)
                }
            }
            val targetPhonemeDir = if (sharedEspeakDir.isDirectory) sharedEspeakDir else phonemeSource

            File(modelDir, ".installed").writeText(voice.id)
            installedDir.deleteRecursively()
            if (!modelDir.renameTo(installedDir)) {
                throw IOException("Could not finalize Piper voice installation for ${voice.id}")
            }

            val finalModel = File(installedDir, model.name)
            val finalTokens = File(installedDir, "tokens.txt")
            val installed = InstalledModel(voice.id, finalModel, finalTokens, targetPhonemeDir)
            Log.i(TAG, "Voice installation verified id=${voice.id} model=${finalModel.absolutePath}")
            return installed
        } catch (error: Throwable) {
            Log.e(TAG, "Voice install/extraction failed id=${voice.id}", error)
            throw error
        } finally {
            archiveFile.delete()
            stagingDir.deleteRecursively()
            activeCall.set(null)
        }
    }

    fun cancelDownload() {
        activeCall.get()?.cancel()
    }

    fun isInstalled(voiceId: String): Boolean = validInstalledModel(voiceId) != null

    @Synchronized
    fun deleteVoice(voiceId: String): Boolean {
        val dir = File(modelRoot, "vits-piper-$voiceId")
        val deleted = dir.deleteRecursively() || !dir.exists()
        Log.i(TAG, "Deleted voice id=$voiceId success=$deleted")
        return deleted
    }

    @Synchronized
    fun deleteAllVoices(): Boolean {
        val deleted = modelRoot.deleteRecursively() || !modelRoot.exists()
        Log.i(TAG, "Deleted all Piper voices success=$deleted")
        return deleted
    }

    private fun validInstalledModel(voiceId: String): InstalledModel? {
        val installedDir = File(modelRoot, "vits-piper-$voiceId")
        val completeMarker = File(installedDir, ".installed")
        if (!completeMarker.isFile || completeMarker.readText().trim() != voiceId) return null
        val model = installedDir.listFiles()?.firstOrNull { it.extension == "onnx" } ?: return null
        val tokens = File(installedDir, "tokens.txt")
        if (!tokens.isFile) return null
        val phonemeDir = if (sharedEspeakDir.isDirectory) sharedEspeakDir else File(installedDir, "espeak-ng-data")
        if (!phonemeDir.isDirectory) return null
        return InstalledModel(voiceId, model, tokens, phonemeDir)
    }

    @Throws(IOException::class)
    private fun downloadArchive(url: String, destination: File, expectedSize: Long, progress: (Int) -> Unit) {
        val request = Request.Builder().url(url).build()
        val call = httpClient.newCall(request)
        activeCall.set(call)
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Piper voice download failed (HTTP ${response.code})")
            val body = response.body ?: throw IOException("Piper voice download returned no data")
            val contentLength = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var totalBytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        if (totalBytes > MAX_ARCHIVE_BYTES) throw IOException("Piper voice archive exceeds size limit")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val totalExpected = if (contentLength > 0L) contentLength else expectedSize
                        if (totalExpected > 0L) {
                            progress((totalBytes * 80L / totalExpected).toInt().coerceIn(0, 80))
                        }
                    }
                    output.fd.sync()
                    Log.i(TAG, "Voice download finished bytes=$totalBytes url=$url")
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun extractArchive(archive: File, destination: File, voiceId: String) {
        destination.mkdirs()
        val canonicalRoot = destination.canonicalFile
        var extractedBytes = 0L
        BufferedInputStream(archive.inputStream()).use { buffered ->
            BZip2CompressorInputStream(buffered).use { bzip ->
                TarArchiveInputStream(bzip).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (entry.isSymbolicLink || entry.isLink) continue
                        val outputFile = File(destination, entry.name).canonicalFile
                        if (outputFile != canonicalRoot && !outputFile.path.startsWith(canonicalRoot.path + File.separator)) {
                            throw IOException("Piper archive contains invalid path")
                        }
                        extractedBytes += entry.size
                        if (entry.size < 0 || extractedBytes > MAX_EXTRACTED_BYTES) {
                            throw IOException("Piper package exceeds extracted size limit")
                        }
                        if (entry.isDirectory) {
                            outputFile.mkdirs()
                            continue
                        }
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).use { output -> tar.copyTo(output) }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "PmugPiper"
        const val MAX_ARCHIVE_BYTES = 200L * 1024 * 1024
        const val MAX_EXTRACTED_BYTES = 400L * 1024 * 1024
    }
}
