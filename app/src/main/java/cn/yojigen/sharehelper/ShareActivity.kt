package cn.yojigen.sharehelper

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
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
                input.mimeType.startsWith(IMAGE_MIME_PREFIX) -> processImage(input.uri, batchDirectory, index, exportID)
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

    private fun processImage(uri: Uri, batchDirectory: File, index: Int, exportID: String): ProcessedMedia {
        val animatedFile = File(batchDirectory, "image_${index}_${exportID}_privacy.png")
        encodePrivacyApng(uri, animatedFile)
        return animatedFile.toProcessedMedia(IMAGE_PNG_MIME_TYPE)
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

    private fun encodePrivacyApng(uri: Uri, outputFile: File) {
        val bitmap = decodeOrientedBitmap(uri)
        try {
            FileOutputStream(outputFile).use { outputStream ->
                writePrivacyApng(bitmap, outputStream)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeOrientedBitmap(uri: Uri): Bitmap {
        val bitmap = contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            ?: throw IOException("Unable to decode image")
        val orientation = readExifOrientation(uri)
        val orientationMatrix = orientation.toBitmapMatrix()
        if (orientationMatrix == null) return bitmap

        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, orientationMatrix, true)
        } catch (exception: RuntimeException) {
            bitmap
        }.also { orientedBitmap ->
            if (orientedBitmap !== bitmap) bitmap.recycle()
        }
    }

    private fun readExifOrientation(uri: Uri): Int {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                ExifInterface(inputStream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED,
                )
            } ?: ExifInterface.ORIENTATION_UNDEFINED
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_UNDEFINED
        } catch (_: RuntimeException) {
            ExifInterface.ORIENTATION_UNDEFINED
        }
    }

    private fun Int.toBitmapMatrix(): Matrix? {
        return when (this) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply {
                setRotate(180f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f) }
            else -> null
        }
    }

    private fun writePrivacyApng(bitmap: Bitmap, outputStream: OutputStream) {
        if (bitmap.width <= 0 || bitmap.height <= 0) throw IOException("Unable to encode empty image")

        DataOutputStream(outputStream).apply {
            write(PNG_SIGNATURE)
            writePngChunk(PNG_CHUNK_IHDR, createIhdrData(bitmap.width, bitmap.height))
            writePngChunk(PNG_CHUNK_ACTL, createAnimationControlData())
            writePngChunk(
                PNG_CHUNK_FCTL,
                createFrameControlData(
                    sequenceNumber = 0,
                    width = bitmap.width,
                    height = bitmap.height,
                    delayMillis = PRIVACY_NOISE_FRAME_DELAY_MILLIS,
                ),
            )
            writePngChunk(PNG_CHUNK_IDAT, compressNoiseFrame(bitmap.width, bitmap.height))
            writePngChunk(
                PNG_CHUNK_FCTL,
                createFrameControlData(
                    sequenceNumber = 1,
                    width = bitmap.width,
                    height = bitmap.height,
                    delayMillis = PRIVACY_IMAGE_FRAME_DELAY_MILLIS,
                ),
            )
            writePngChunk(PNG_CHUNK_FDAT, createFrameData(sequenceNumber = 2, compressedFrame = compressBitmapFrame(bitmap)))
            writePngChunk(PNG_CHUNK_IEND, ByteArray(0))
        }
    }

    private fun createIhdrData(width: Int, height: Int): ByteArray {
        return buildPngData {
            writeInt(width)
            writeInt(height)
            writeByte(PNG_BIT_DEPTH)
            writeByte(PNG_COLOR_TYPE_RGBA)
            writeByte(PNG_COMPRESSION_DEFLATE)
            writeByte(PNG_FILTER_ADAPTIVE)
            writeByte(PNG_INTERLACE_NONE)
        }
    }

    private fun createAnimationControlData(): ByteArray {
        return buildPngData {
            writeInt(PRIVACY_APNG_FRAME_COUNT)
            writeInt(PRIVACY_APNG_PLAY_ONCE)
        }
    }

    private fun createFrameControlData(sequenceNumber: Int, width: Int, height: Int, delayMillis: Int): ByteArray {
        return buildPngData {
            writeInt(sequenceNumber)
            writeInt(width)
            writeInt(height)
            writeInt(0)
            writeInt(0)
            writeShort(delayMillis)
            writeShort(APNG_DELAY_DENOMINATOR)
            writeByte(APNG_DISPOSE_NONE)
            writeByte(APNG_BLEND_SOURCE)
        }
    }

    private fun createFrameData(sequenceNumber: Int, compressedFrame: ByteArray): ByteArray {
        return buildPngData {
            writeInt(sequenceNumber)
            write(compressedFrame)
        }
    }

    private fun compressNoiseFrame(width: Int, height: Int): ByteArray {
        val tileColors = IntArray((width + PRIVACY_NOISE_TILE_SIZE - 1) / PRIVACY_NOISE_TILE_SIZE)
        return compressRgbaRows(width, height) { y, rowBuffer ->
            if (y % PRIVACY_NOISE_TILE_SIZE == 0) {
                for (index in tileColors.indices) {
                    tileColors[index] = 0xFF000000.toInt() or noiseRandom.nextInt(0x1000000)
                }
            }
            rowBuffer.writePngFilterByte()
            var offset = 1
            for (x in 0 until width) {
                val color = tileColors[x / PRIVACY_NOISE_TILE_SIZE]
                rowBuffer[offset++] = ((color shr 16) and 0xFF).toByte()
                rowBuffer[offset++] = ((color shr 8) and 0xFF).toByte()
                rowBuffer[offset++] = (color and 0xFF).toByte()
                rowBuffer[offset++] = 0xFF.toByte()
            }
        }
    }

    private fun compressBitmapFrame(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width)
        return compressRgbaRows(bitmap.width, bitmap.height) { y, rowBuffer ->
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            rowBuffer.writePngFilterByte()
            var offset = 1
            for (pixel in pixels) {
                rowBuffer[offset++] = ((pixel shr 16) and 0xFF).toByte()
                rowBuffer[offset++] = ((pixel shr 8) and 0xFF).toByte()
                rowBuffer[offset++] = (pixel and 0xFF).toByte()
                rowBuffer[offset++] = ((pixel ushr 24) and 0xFF).toByte()
            }
        }
    }

    private fun compressRgbaRows(width: Int, height: Int, writeRow: (Int, ByteArray) -> Unit): ByteArray {
        val compressedOutput = ByteArrayOutputStream()
        DeflaterOutputStream(compressedOutput).use { deflaterOutput ->
            val rowBuffer = ByteArray(width * PNG_RGBA_BYTES_PER_PIXEL + 1)
            for (y in 0 until height) {
                writeRow(y, rowBuffer)
                deflaterOutput.write(rowBuffer)
            }
        }
        return compressedOutput.toByteArray()
    }

    private fun ByteArray.writePngFilterByte() {
        this[0] = PNG_FILTER_NONE.toByte()
    }

    private fun buildPngData(writeData: DataOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { dataOutput -> dataOutput.writeData() }
        return output.toByteArray()
    }

    private fun DataOutputStream.writePngChunk(type: ByteArray, data: ByteArray) {
        writeInt(data.size)
        write(type)
        write(data)
        writeInt(crcFor(type, data).toInt())
    }

    private fun crcFor(type: ByteArray, data: ByteArray): Long {
        return CRC32().apply {
            update(type)
            update(data)
        }.value
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
        const val IMAGE_MIME_PREFIX = "image/"
        const val VIDEO_MIME_PREFIX = "video/"
        const val IMAGE_PNG_MIME_TYPE = "image/png"
        const val VIDEO_MP4_MIME_TYPE = "video/mp4"
        const val VIDEO_QUICKTIME_MIME_TYPE = "video/quicktime"
        const val VIDEO_WEBM_MIME_TYPE = "video/webm"
        const val EXPORT_METADATA_MIME_TYPE = "application/sharehelper-export"
        const val PRIVACY_APNG_FRAME_COUNT = 2
        const val PRIVACY_APNG_PLAY_ONCE = 1
        const val PRIVACY_NOISE_FRAME_DELAY_MILLIS = 350
        const val PRIVACY_IMAGE_FRAME_DELAY_MILLIS = 1000
        const val PRIVACY_NOISE_TILE_SIZE = 12
        const val PNG_BIT_DEPTH = 8
        const val PNG_COLOR_TYPE_RGBA = 6
        const val PNG_COMPRESSION_DEFLATE = 0
        const val PNG_FILTER_ADAPTIVE = 0
        const val PNG_FILTER_NONE = 0
        const val PNG_INTERLACE_NONE = 0
        const val PNG_RGBA_BYTES_PER_PIXEL = 4
        const val APNG_DELAY_DENOMINATOR = 1000
        const val APNG_DISPOSE_NONE = 0
        const val APNG_BLEND_SOURCE = 0

        val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        val PNG_CHUNK_IHDR = byteArrayOf(73, 72, 68, 82)
        val PNG_CHUNK_ACTL = byteArrayOf(97, 99, 84, 76)
        val PNG_CHUNK_FCTL = byteArrayOf(102, 99, 84, 76)
        val PNG_CHUNK_IDAT = byteArrayOf(73, 68, 65, 84)
        val PNG_CHUNK_FDAT = byteArrayOf(102, 100, 65, 84)
        val PNG_CHUNK_IEND = byteArrayOf(73, 69, 78, 68)
        val noiseRandom = SecureRandom()
    }
}
