package cn.yojigen.sharehelper

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cleanupOldBatches()

        Thread {
            val result = runCatching { processIntent(intent) }
            runOnUiThread {
                result.fold(
                    onSuccess = { processedMedia ->
                        if (processedMedia.isEmpty()) {
                            showToastAndFinish(getString(R.string.no_media_message))
                        } else {
                            shareProcessedMedia(processedMedia)
                        }
                    },
                    onFailure = {
                        showToastAndFinish(getString(R.string.processing_failed_message))
                    },
                )
            }
        }.start()
    }

    private fun processIntent(sourceIntent: Intent): List<ProcessedMedia> {
        val inputs = collectInputMedia(sourceIntent)
        if (inputs.isEmpty()) return emptyList()

        val batchID = "batch_${System.currentTimeMillis()}_${UUID.randomUUID()}"
        val batchDirectory = File(File(cacheDir, SHARED_CACHE_DIRECTORY), batchID)
        if (!batchDirectory.mkdirs() && !batchDirectory.isDirectory) {
            throw IOException("Unable to create export directory")
        }

        return inputs.mapIndexed { index, input ->
            val exportID = "${batchID}_export_${index}_${UUID.randomUUID()}"
            when {
                input.mimeType.startsWith(IMAGE_MIME_PREFIX) -> processImage(input.uri, input.mimeType, batchDirectory, index, exportID)
                input.mimeType.startsWith(VIDEO_MIME_PREFIX) -> processVideo(input.uri, input.mimeType, batchDirectory, index, exportID)
                else -> throw IOException("Unsupported media type")
            }
        }
    }

    private fun collectInputMedia(sourceIntent: Intent): List<InputMedia> {
        val fallbackMimeType = sourceIntent.type
        val uris = buildList {
            sourceIntent.clipData?.let { clipData ->
                for (index in 0 until clipData.itemCount) {
                    clipData.getItemAt(index).uri?.let { uri -> add(uri) }
                }
            }

            IntentCompat.getParcelableExtra(sourceIntent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uri -> add(uri) }
            IntentCompat.getParcelableArrayListExtra(sourceIntent, Intent.EXTRA_STREAM, Uri::class.java)?.let { streamUris -> addAll(streamUris) }
        }.distinctBy { it.toString() }

        return uris.mapNotNull { uri ->
            val mimeType = resolveMediaMimeType(uri, fallbackMimeType)
            if (mimeType == null) null else InputMedia(uri, mimeType)
        }
    }

    private fun resolveMediaMimeType(uri: Uri, fallbackMimeType: String?): String? {
        val resolvedMimeType = contentResolver.getType(uri)
        return when {
            resolvedMimeType.isSupportedMediaMimeType() -> resolvedMimeType
            fallbackMimeType.isSupportedMediaMimeType() -> fallbackMimeType
            else -> null
        }
    }

    private fun processImage(uri: Uri, mimeType: String, batchDirectory: File, index: Int, exportID: String): ProcessedMedia {
        val preferredExtension = imageExtensionFor(mimeType)
        if (preferredExtension != null) {
            val copiedFile = File(batchDirectory, "image_${index}_${exportID}.$preferredExtension")
            copyUriToFile(uri, copiedFile)
            if (writeExportMetadata(copiedFile, exportID)) {
                return copiedFile.toProcessedMedia(mimeType)
            }
        }

        val encodedFile = File(batchDirectory, "image_${index}_${exportID}_encoded.jpg")
        reencodeImageAsJpeg(uri, encodedFile, exportID)
        writeExportMetadata(encodedFile, exportID)
        return encodedFile.toProcessedMedia(IMAGE_JPEG_MIME_TYPE)
    }

    private fun processVideo(uri: Uri, mimeType: String, batchDirectory: File, index: Int, exportID: String): ProcessedMedia {
        if (mimeType == VIDEO_MP4_MIME_TYPE) {
            val remuxedFile = File(batchDirectory, "video_${index}_${exportID}.mp4")
            if (remuxMp4WithMetadataTrack(uri, remuxedFile, exportID)) {
                return remuxedFile.toProcessedMedia(VIDEO_MP4_MIME_TYPE)
            }
            remuxedFile.delete()
        }

        val copiedFile = File(batchDirectory, "video_${index}_${exportID}.${videoExtensionFor(mimeType)}")
        // TODO: Replace this conservative fallback with a Media3 Transformer remux path for non-MP4
        // inputs and platform/device cases where MediaMuxer rejects an MP4 metadata track.
        copyUriToFile(uri, copiedFile)
        return copiedFile.toProcessedMedia(mimeType)
    }

    private fun remuxMp4WithMetadataTrack(uri: Uri, outputFile: File, exportID: String): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        return try {
            extractor.setDataSource(this, uri, null)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            setOrientationHintIfPresent(uri, muxer)

            val trackMappings = mutableMapOf<Int, Int>()
            var maxInputSize = DEFAULT_VIDEO_BUFFER_SIZE
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                trackMappings[trackIndex] = muxer.addTrack(format)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInputSize = max(maxInputSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                extractor.selectTrack(trackIndex)
            }

            val metadataTrackIndex = muxer.addTrack(createExportMetadataFormat(exportID))
            muxer.start()
            muxerStarted = true
            writeExportMetadataSample(muxer, metadataTrackIndex, exportID)
            copySelectedSamples(extractor, muxer, trackMappings, maxInputSize)
            true
        } catch (_: RuntimeException) {
            false
        } catch (_: IOException) {
            false
        } finally {
            extractor.release()
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
        }
    }

    private fun createExportMetadataFormat(exportID: String): MediaFormat {
        val metadataFormat = MediaFormat()
        metadataFormat.setString(MediaFormat.KEY_MIME, EXPORT_METADATA_MIME_TYPE)
        metadataFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, exportID.toByteArray(Charsets.UTF_8).size)
        return metadataFormat
    }

    private fun writeExportMetadataSample(muxer: MediaMuxer, metadataTrackIndex: Int, exportID: String) {
        val metadataBytes = "{\"ExportID\":\"$exportID\",\"source\":\"ShareHelper\"}".toByteArray(Charsets.UTF_8)
        val bufferInfo = MediaCodec.BufferInfo().apply {
            offset = 0
            size = metadataBytes.size
            presentationTimeUs = 0L
            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        muxer.writeSampleData(metadataTrackIndex, ByteBuffer.wrap(metadataBytes), bufferInfo)
    }

    private fun copySelectedSamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackMappings: Map<Int, Int>,
        maxInputSize: Int,
    ) {
        val buffer = ByteBuffer.allocate(maxInputSize)
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val sourceTrackIndex = extractor.sampleTrackIndex
            if (sourceTrackIndex < 0) break

            val destinationTrackIndex = trackMappings[sourceTrackIndex]
            if (destinationTrackIndex == null) {
                extractor.advance()
                continue
            }

            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            bufferInfo.set(0, sampleSize, extractor.sampleTime, extractor.sampleFlags)
            muxer.writeSampleData(destinationTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }

    private fun setOrientationHintIfPresent(uri: Uri, muxer: MediaMuxer) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
            if (rotation == 90 || rotation == 180 || rotation == 270) {
                muxer.setOrientationHint(rotation)
            }
        } catch (_: RuntimeException) {
            // Orientation metadata is optional; remuxing can continue without it.
        } finally {
            retriever.release()
        }
    }

    private fun reencodeImageAsJpeg(uri: Uri, outputFile: File, exportID: String) {
        val bitmap = contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            ?: throw IOException("Unable to decode image")
        val adjustedBitmap = bitmap.withExportPixelDifference(exportID)
        try {
            FileOutputStream(outputFile).use { outputStream ->
                if (!adjustedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)) {
                    throw IOException("Unable to encode image")
                }
            }
        } finally {
            if (adjustedBitmap !== bitmap) adjustedBitmap.recycle()
            bitmap.recycle()
        }
    }

    private fun Bitmap.withExportPixelDifference(exportID: String): Bitmap {
        if (width <= 0 || height <= 0) return this
        val mutableBitmap = if (isMutable) this else copy(Bitmap.Config.ARGB_8888, true)
        val pixel = mutableBitmap.getPixel(0, 0)
        val adjustedBlue = (pixel and 0xFF xor (exportID.hashCode() and 0x0F)) and 0xFF
        val adjustedPixel = (pixel and 0xFFFFFF00.toInt()) or adjustedBlue
        mutableBitmap.setPixel(0, 0, adjustedPixel)
        return mutableBitmap
    }

    private fun writeExportMetadata(file: File, exportID: String): Boolean {
        return try {
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "ShareHelper")
            exif.setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, exportID)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "ExportID=$exportID")
            exif.setAttribute(ExifInterface.TAG_DATETIME, exifTimestamp())
            exif.saveAttributes()
            true
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun copyUriToFile(uri: Uri, outputFile: File) {
        contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: throw IOException("Unable to open shared media")
    }

    private fun File.toProcessedMedia(mimeType: String): ProcessedMedia {
        val uri = FileProvider.getUriForFile(this@ShareActivity, "$packageName.fileprovider", this)
        return ProcessedMedia(uri, mimeType)
    }

    private fun shareProcessedMedia(processedMedia: List<ProcessedMedia>) {
        val uris = ArrayList(processedMedia.map { it.uri })
        val shareIntent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = outputMimeTypeFor(processedMedia)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = clipDataFor(uris)
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }

        try {
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)))
            finish()
        } catch (_: ActivityNotFoundException) {
            showToastAndFinish(getString(R.string.no_share_target_message))
        }
    }

    private fun clipDataFor(uris: List<Uri>): ClipData {
        val clipData = ClipData.newUri(contentResolver, getString(R.string.share_clip_label), uris.first())
        uris.drop(1).forEach { uri -> clipData.addItem(ClipData.Item(uri)) }
        return clipData
    }

    private fun outputMimeTypeFor(processedMedia: List<ProcessedMedia>): String {
        if (processedMedia.size == 1) return processedMedia.first().mimeType
        val allImages = processedMedia.all { it.mimeType.startsWith(IMAGE_MIME_PREFIX) }
        val allVideos = processedMedia.all { it.mimeType.startsWith(VIDEO_MIME_PREFIX) }
        return when {
            allImages -> "image/*"
            allVideos -> "video/*"
            else -> "*/*"
        }
    }

    private fun cleanupOldBatches() {
        val sharedDirectory = File(cacheDir, SHARED_CACHE_DIRECTORY)
        val cutoff = System.currentTimeMillis() - CACHE_BATCH_MAX_AGE_MILLIS
        sharedDirectory.listFiles()?.forEach { batchDirectory ->
            if (batchDirectory.isDirectory && batchDirectory.lastModified() < cutoff) {
                batchDirectory.deleteRecursively()
            }
        }
    }

    private fun showToastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun exifTimestamp(): String = SimpleDateFormat(EXIF_TIMESTAMP_PATTERN, Locale.US).format(Date())

    private fun imageExtensionFor(mimeType: String): String? {
        return when (mimeType.lowercase(Locale.US)) {
            IMAGE_JPEG_MIME_TYPE -> "jpg"
            IMAGE_PNG_MIME_TYPE -> "png"
            IMAGE_WEBP_MIME_TYPE -> "webp"
            else -> null
        }
    }

    private fun videoExtensionFor(mimeType: String): String {
        return when (mimeType.lowercase(Locale.US)) {
            VIDEO_MP4_MIME_TYPE -> "mp4"
            VIDEO_QUICKTIME_MIME_TYPE -> "mov"
            VIDEO_WEBM_MIME_TYPE -> "webm"
            else -> "bin"
        }
    }

    private fun String?.isSupportedMediaMimeType(): Boolean {
        return this != null && (startsWith(IMAGE_MIME_PREFIX) || startsWith(VIDEO_MIME_PREFIX))
    }

    private data class InputMedia(val uri: Uri, val mimeType: String)

    private data class ProcessedMedia(val uri: Uri, val mimeType: String)

    private companion object {
        const val SHARED_CACHE_DIRECTORY = "shared"
        const val CACHE_BATCH_MAX_AGE_MILLIS = 2L * 24L * 60L * 60L * 1000L
        const val DEFAULT_VIDEO_BUFFER_SIZE = 1024 * 1024
        const val JPEG_QUALITY = 95
        const val EXIF_TIMESTAMP_PATTERN = "yyyy:MM:dd HH:mm:ss"
        const val IMAGE_MIME_PREFIX = "image/"
        const val VIDEO_MIME_PREFIX = "video/"
        const val IMAGE_JPEG_MIME_TYPE = "image/jpeg"
        const val IMAGE_PNG_MIME_TYPE = "image/png"
        const val IMAGE_WEBP_MIME_TYPE = "image/webp"
        const val VIDEO_MP4_MIME_TYPE = "video/mp4"
        const val VIDEO_QUICKTIME_MIME_TYPE = "video/quicktime"
        const val VIDEO_WEBM_MIME_TYPE = "video/webm"
        const val EXPORT_METADATA_MIME_TYPE = "application/sharehelper-export"
    }
}
