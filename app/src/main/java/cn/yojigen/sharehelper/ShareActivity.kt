package cn.yojigen.sharehelper

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Base64OutputStream
import android.view.Gravity
import android.view.Window
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.max

class ShareActivity : Activity() {
    private var loadingDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLoadingDialog()
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
                    onFailure = { exception ->
                        val message = when (exception) {
                            is ImageTooLargeException -> getString(R.string.image_too_large_message)
                            is InsufficientVideoCacheSpaceException -> getString(R.string.video_cache_space_message)
                            is UnsupportedVideoMimeTypeException -> getString(R.string.unsupported_video_type_message)
                            else -> getString(R.string.processing_failed_message)
                        }
                        showToastAndFinish(message)
                    },
                )
            }
        }.start()
    }

    private fun showLoadingDialog() {
        if (isFinishing || isDestroyed) return

        val density = resources.displayMetrics.density
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val cardColor = if (isDark) Color.rgb(43, 43, 43) else Color.WHITE
        val textColor = if (isDark) Color.rgb(241, 241, 241) else Color.rgb(32, 32, 32)
        val progressTint = if (isDark) Color.rgb(180, 205, 255) else Color.rgb(38, 99, 235)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                (LOADING_HORIZONTAL_PADDING_DP * density).toInt(),
                (LOADING_VERTICAL_PADDING_DP * density).toInt(),
                (LOADING_HORIZONTAL_PADDING_DP * density).toInt(),
                (LOADING_VERTICAL_PADDING_DP * density).toInt(),
            )
            background = GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = LOADING_DIALOG_CORNER_RADIUS_DP * density
            }
        }

        val messageView = TextView(this).apply {
            text = getString(R.string.loading_message)
            gravity = Gravity.CENTER
            textSize = LOADING_TEXT_SIZE_SP
            setTextColor(textColor)
        }

        container.addView(
            ProgressBar(this).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(progressTint)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            messageView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (LOADING_TEXT_TOP_MARGIN_DP * density).toInt()
            },
        )

        loadingDialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(false)
            setContentView(container)
            show()
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setDimAmount(if (isDark) LOADING_DIALOG_DARK_DIM_AMOUNT else LOADING_DIALOG_LIGHT_DIM_AMOUNT)
            window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun dismissLoadingDialog() {
        loadingDialog?.takeIf { it.isShowing }?.dismiss()
        loadingDialog = null
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
            val fileID = UUID.randomUUID().toString()
            when {
                input.mimeType.startsWith(IMAGE_MIME_PREFIX) -> processImage(input.uri, batchDirectory, fileID, exportID)
                input.mimeType.startsWith(VIDEO_MIME_PREFIX) -> processVideo(input.uri, input.mimeType.requireConcreteVideoMimeType(), batchDirectory, fileID, exportID)
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
            sourceIntent.data?.let { uri -> add(uri) }
        }.distinctBy { it.toString() }

        return uris.mapNotNull { uri ->
            val mimeType = resolveMediaMimeType(uri, fallbackMimeType)
            if (mimeType == null) null else InputMedia(uri, mimeType)
        }
    }

    private fun resolveMediaMimeType(uri: Uri, fallbackMimeType: String?): String? {
        return contentResolver.getType(uri).supportedMediaMimeTypeOrNull()
            ?: uri.extensionMimeTypeOrNull()
            ?: fallbackMimeType.supportedMediaMimeTypeOrNull()
    }

    private fun processImage(uri: Uri, batchDirectory: File, fileID: String, exportID: String): ProcessedMedia {
        val htmlFile = File(batchDirectory, "$fileID.html")
        try {
            writeImageHtml(uri, htmlFile, exportID)
        } catch (exception: IOException) {
            htmlFile.delete()
            throw exception
        } catch (exception: RuntimeException) {
            htmlFile.delete()
            throw exception
        }
        return htmlFile.toProcessedMedia(TEXT_HTML_MIME_TYPE)
    }

    private fun processVideo(uri: Uri, mimeType: String, batchDirectory: File, fileID: String, exportID: String): ProcessedMedia {
        validateVideoSize(uri, batchDirectory)
        var videoFile: File? = null
        var htmlFile: File? = null
        if (mimeType == VIDEO_MP4_MIME_TYPE) {
            val remuxedFile = File(batchDirectory, "${fileID}_source.mp4")
            if (remuxMp4WithMetadataTrack(uri, remuxedFile, exportID)) {
                videoFile = remuxedFile
                htmlFile = File(batchDirectory, "$fileID.html")
                return try {
                    writeVideoHtml(remuxedFile, VIDEO_MP4_MIME_TYPE, htmlFile)
                    htmlFile.toProcessedMedia(TEXT_HTML_MIME_TYPE)
                } catch (exception: IOException) {
                    htmlFile.delete()
                    videoFile.delete()
                    throw exception
                } catch (exception: RuntimeException) {
                    htmlFile.delete()
                    videoFile.delete()
                    throw exception
                }
            }
            remuxedFile.delete()
        }

        videoFile = File(batchDirectory, "${fileID}_source.${videoExtensionFor(mimeType)}")
        htmlFile = File(batchDirectory, "$fileID.html")
        // TODO: Replace this conservative fallback with a Media3 Transformer remux path for non-MP4
        // inputs and platform/device cases where MediaMuxer rejects an MP4 metadata track.
        return try {
            copyUriToFile(uri, videoFile, batchDirectory)
            writeVideoHtml(videoFile, mimeType, htmlFile)
            htmlFile.toProcessedMedia(TEXT_HTML_MIME_TYPE)
        } catch (exception: IOException) {
            htmlFile.delete()
            videoFile.delete()
            throw exception
        } catch (exception: RuntimeException) {
            htmlFile.delete()
            videoFile.delete()
            throw exception
        }
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
                    maxInputSize = max(maxInputSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtMost(MAX_VIDEO_SAMPLE_BUFFER_SIZE))
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

    private fun writeImageHtml(uri: Uri, outputFile: File, exportID: String) {
        val bitmap = decodeOrientedBitmap(uri)
        try {
            FileOutputStream(outputFile).use { outputStream ->
                outputStream.write("<!doctype html>\n".toByteArray(Charsets.UTF_8))
                outputStream.write("<html lang=\"zh-CN\">\n".toByteArray(Charsets.UTF_8))
                outputStream.write("<head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>".toByteArray(Charsets.UTF_8))
                val title = getString(R.string.image_html_title).toHtmlAttributeText()
                outputStream.write(title.toByteArray(Charsets.UTF_8))
                outputStream.write("</title></head>\n".toByteArray(Charsets.UTF_8))
                outputStream.write("<body style=\"margin:0;background:#fff;color:#111;font-family:sans-serif;\">\n".toByteArray(Charsets.UTF_8))
                outputStream.write("<img style=\"display:block;width:100%;height:auto;\" alt=\"".toByteArray(Charsets.UTF_8))
                outputStream.write(title.toByteArray(Charsets.UTF_8))
                outputStream.write("\" src=\"data:".toByteArray(Charsets.UTF_8))
                outputStream.write(IMAGE_PNG_MIME_TYPE.toByteArray(Charsets.UTF_8))
                outputStream.write(";base64,".toByteArray(Charsets.UTF_8))
                Base64OutputStream(outputStream, Base64.NO_WRAP or Base64.NO_CLOSE).use { base64OutputStream ->
                    writeImagePng(bitmap, base64OutputStream, exportID)
                }
                outputStream.write("\">\n</body>\n</html>\n".toByteArray(Charsets.UTF_8))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeVideoHtml(videoFile: File, videoMimeType: String, outputFile: File) {
        validateVideoHtmlBudget(videoFile, outputFile)
        FileOutputStream(outputFile).use { outputStream ->
            outputStream.write("<!doctype html>\n".toByteArray(Charsets.UTF_8))
            outputStream.write("<html lang=\"zh-CN\">\n".toByteArray(Charsets.UTF_8))
            outputStream.write("<head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>".toByteArray(Charsets.UTF_8))
            outputStream.write(getString(R.string.video_html_title).toHtmlAttributeText().toByteArray(Charsets.UTF_8))
            outputStream.write("</title></head>\n".toByteArray(Charsets.UTF_8))
            outputStream.write("<body style=\"margin:0;background:#fff;color:#111;font-family:sans-serif;\">\n".toByteArray(Charsets.UTF_8))
            outputStream.write("<video controls style=\"display:block;width:100%;max-height:100vh;\" src=\"data:".toByteArray(Charsets.UTF_8))
            outputStream.write(videoMimeType.toHtmlAttributeText().toByteArray(Charsets.UTF_8))
            outputStream.write(";base64,".toByteArray(Charsets.UTF_8))
            Base64OutputStream(outputStream, Base64.NO_WRAP or Base64.NO_CLOSE).use { base64OutputStream ->
                FileInputStream(videoFile).use { inputStream ->
                    inputStream.copyTo(base64OutputStream, DEFAULT_VIDEO_BUFFER_SIZE)
                }
            }
            outputStream.write("\"></video>\n</body>\n</html>\n".toByteArray(Charsets.UTF_8))
        }
    }

    private fun decodeOrientedBitmap(uri: Uri): Bitmap {
        val imageBounds = decodeImageBounds(uri)
        if (imageBounds.width <= 0 || imageBounds.height <= 0) throw IOException("Unable to decode image bounds")

        val sampleSize = calculateSampleSize(imageBounds.width, imageBounds.height)
        val bitmap = contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(
                inputStream,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        }
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

    private fun decodeImageBounds(uri: Uri): ImageBounds {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { inputStream -> BitmapFactory.decodeStream(inputStream, null, options) }
        return ImageBounds(options.outWidth, options.outHeight)
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (
            width / sampleSize > PRIVACY_IMAGE_MAX_DIMENSION ||
            height / sampleSize > PRIVACY_IMAGE_MAX_DIMENSION ||
            (width.toLong() / sampleSize) * (height.toLong() / sampleSize) > PRIVACY_IMAGE_MAX_PIXELS
        ) {
            sampleSize *= 2
        }
        val sampledWidth = width / sampleSize
        val sampledHeight = height / sampleSize
        if (sampledWidth <= 0 || sampledHeight <= 0) throw ImageTooLargeException()
        return sampleSize
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

    private fun writeImagePng(bitmap: Bitmap, outputStream: OutputStream, exportID: String) {
        if (bitmap.width <= 0 || bitmap.height <= 0) throw IOException("Unable to encode empty image")
        DataOutputStream(outputStream).apply {
            write(PNG_SIGNATURE)
            writePngChunk(PNG_CHUNK_IHDR, createIhdrData(bitmap.width, bitmap.height))
            writePngChunk(PNG_CHUNK_TEXT, createTextChunkData(PNG_TEXT_KEYWORD_EXPORT_ID, exportID))
            writeCompressedPngChunk(PNG_CHUNK_IDAT) { chunkedOutput ->
                writeOriginalRows(bitmap, chunkedOutput)
            }
            writePngChunk(PNG_CHUNK_IEND, ByteArray(0))
        }
    }

    private fun createTextChunkData(keyword: String, text: String): ByteArray {
        return buildPngData {
            write(keyword.toByteArray(Charsets.ISO_8859_1))
            writeByte(0)
            write(text.toByteArray(Charsets.ISO_8859_1))
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

    private fun writeOriginalRows(bitmap: Bitmap, outputStream: OutputStream) {
        val pixels = IntArray(bitmap.width)
        writeRgbaRows(bitmap.width, bitmap.height, outputStream) { y, rowBuffer ->
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

    private fun writeRgbaRows(width: Int, height: Int, outputStream: OutputStream, writeRow: (Int, ByteArray) -> Unit) {
        val rowBuffer = ByteArray(width * PNG_RGBA_BYTES_PER_PIXEL + 1)
        for (y in 0 until height) {
            writeRow(y, rowBuffer)
            outputStream.write(rowBuffer)
        }
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
        writePngChunkData(this, type, data)
    }

    private fun DataOutputStream.writeCompressedPngChunk(type: ByteArray, writeRows: (OutputStream) -> Unit) {
        val chunkedOutput = ChunkedDeflaterPngOutput(this, type)
        try {
            writeRows(chunkedOutput)
        } finally {
            chunkedOutput.finish()
        }
    }

    private fun copyUriToFile(uri: Uri, outputFile: File, batchDirectory: File) {
        contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(outputFile).use { outputStream ->
                val buffer = ByteArray(DEFAULT_VIDEO_BUFFER_SIZE)
                var copiedBytes = 0L
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read < 0) break
                    copiedBytes += read.toLong()
                    if (estimatedBase64HtmlBytes(copiedBytes) > batchDirectory.usableSpace) throw InsufficientVideoCacheSpaceException()
                    outputStream.write(buffer, 0, read)
                }
            }
        } ?: throw IOException("Unable to open shared media")
    }

    private fun validateVideoSize(uri: Uri, batchDirectory: File) {
        val inputSize = queryContentSize(uri)
        if (inputSize > 0 && estimatedVideoCacheBytes(inputSize) > batchDirectory.usableSpace) {
            throw InsufficientVideoCacheSpaceException()
        }
    }

    private fun validateVideoHtmlBudget(videoFile: File, outputFile: File) {
        val videoSize = videoFile.length()
        val estimatedHtmlSize = estimatedBase64HtmlBytes(videoSize)
        val outputDirectory = outputFile.parentFile ?: throw InsufficientVideoCacheSpaceException()
        if (estimatedHtmlSize > outputDirectory.usableSpace) throw InsufficientVideoCacheSpaceException()
    }

    private fun estimatedVideoCacheBytes(videoSize: Long): Long {
        return videoSize + estimatedBase64HtmlBytes(videoSize)
    }

    private fun estimatedBase64HtmlBytes(videoSize: Long): Long {
        return ((videoSize + 2L) / 3L) * 4L + VIDEO_HTML_TEMPLATE_OVERHEAD_BYTES
    }

    private fun queryContentSize(uri: Uri): Long {
        val assetLength = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor -> descriptor.length } ?: -1L
        }.getOrDefault(-1L)
        if (assetLength > 0) return assetLength

        return contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            cursor.contentSizeColumnValue()
        } ?: -1L
    }

    private fun Cursor.contentSizeColumnValue(): Long {
        if (!moveToFirst()) return -1L
        val sizeColumnIndex = getColumnIndex(OpenableColumns.SIZE)
        if (sizeColumnIndex < 0 || isNull(sizeColumnIndex)) return -1L
        return getLong(sizeColumnIndex)
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
            dismissLoadingDialog()
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
        val allHtml = processedMedia.all { it.mimeType == TEXT_HTML_MIME_TYPE }
        return when {
            allHtml -> TEXT_HTML_MIME_TYPE
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
        dismissLoadingDialog()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        dismissLoadingDialog()
        super.onDestroy()
    }

    private fun videoExtensionFor(mimeType: String): String {
        return when (mimeType.lowercase(Locale.US)) {
            VIDEO_MP4_MIME_TYPE -> "mp4"
            VIDEO_QUICKTIME_MIME_TYPE -> "mov"
            VIDEO_WEBM_MIME_TYPE -> "webm"
            else -> "bin"
        }
    }

    private fun String.requireConcreteVideoMimeType(): String {
        if (this == VIDEO_WILDCARD_MIME_TYPE) throw UnsupportedVideoMimeTypeException()
        return this
    }

    private fun String?.supportedMediaMimeTypeOrNull(): String? {
        val mimeType = this?.substringBefore(';')?.trim()?.lowercase(Locale.US) ?: return null
        return mimeType.takeIf { it.startsWith(IMAGE_MIME_PREFIX) || (it.startsWith(VIDEO_MIME_PREFIX) && it != VIDEO_WILDCARD_MIME_TYPE) }
    }

    private fun Uri.extensionMimeTypeOrNull(): String? {
        val extension = MimeTypeMap.getFileExtensionFromUrl(toString()).ifBlank {
            lastPathSegment?.substringAfterLast('.', missingDelimiterValue = "").orEmpty()
        }.lowercase(Locale.US)
        if (extension.isBlank()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension).supportedMediaMimeTypeOrNull()
    }

    private fun String.toHtmlAttributeText(): String {
        return buildString(length) {
            for (character in this@toHtmlAttributeText) {
                when (character) {
                    '&' -> append("&amp;")
                    '"' -> append("&quot;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    else -> append(character)
                }
            }
        }
    }

    private data class InputMedia(val uri: Uri, val mimeType: String)

    private data class ImageBounds(val width: Int, val height: Int)

    private data class ProcessedMedia(val uri: Uri, val mimeType: String)

    private class ImageTooLargeException : IOException("Image is too large for HTML export")

    private class InsufficientVideoCacheSpaceException : IOException("Not enough cache space for HTML video export")

    private class UnsupportedVideoMimeTypeException : IOException("Video MIME type is too generic")

    private open class ChunkedDeflaterPngOutput(
        protected val destination: DataOutputStream,
        private val chunkType: ByteArray,
    ) : OutputStream() {
        private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        private val inputBuffer = ByteArray(1)
        private val outputBuffer = ByteArray(PNG_STREAM_CHUNK_SIZE)
        private var finished = false

        override fun write(b: Int) {
            inputBuffer[0] = b.toByte()
            write(inputBuffer, 0, 1)
        }

        override fun write(buffer: ByteArray, offset: Int, count: Int) {
            deflater.setInput(buffer, offset, count)
            drainDeflaterUntilInputNeeded()
        }

        fun finish() {
            if (finished) return
            try {
                deflater.finish()
                drainDeflaterUntilFinished()
            } finally {
                deflater.end()
                finished = true
            }
        }

        protected open fun writeCompressedChunk(compressedBytes: ByteArray, size: Int) {
            writePngChunkData(destination, chunkType, compressedBytes.copyOf(size))
        }

        private fun drainDeflaterUntilInputNeeded() {
            while (!deflater.needsInput()) {
                val size = deflater.deflate(outputBuffer)
                if (size > 0) {
                    writeCompressedChunk(outputBuffer, size)
                } else {
                    break
                }
            }
        }

        private fun drainDeflaterUntilFinished() {
            while (!deflater.finished()) {
                val size = deflater.deflate(outputBuffer)
                if (size > 0) {
                    writeCompressedChunk(outputBuffer, size)
                } else if (deflater.needsInput()) {
                    break
                }
            }
        }
    }

    private companion object {
        fun writePngChunkData(destination: DataOutputStream, type: ByteArray, data: ByteArray) {
            destination.writeInt(data.size)
            destination.write(type)
            destination.write(data)
            destination.writeInt(crcFor(type, data).toInt())
        }

        fun crcFor(type: ByteArray, data: ByteArray): Long {
            return CRC32().apply {
                update(type)
                update(data)
            }.value
        }

        const val SHARED_CACHE_DIRECTORY = "shared"
        const val CACHE_BATCH_MAX_AGE_MILLIS = 2L * 24L * 60L * 60L * 1000L
        const val DEFAULT_VIDEO_BUFFER_SIZE = 1024 * 1024
        const val MAX_VIDEO_SAMPLE_BUFFER_SIZE = 8 * 1024 * 1024
        const val VIDEO_HTML_TEMPLATE_OVERHEAD_BYTES = 16L * 1024L
        const val IMAGE_MIME_PREFIX = "image/"
        const val VIDEO_MIME_PREFIX = "video/"
        const val IMAGE_PNG_MIME_TYPE = "image/png"
        const val TEXT_HTML_MIME_TYPE = "text/html"
        const val VIDEO_WILDCARD_MIME_TYPE = "video/*"
        const val VIDEO_MP4_MIME_TYPE = "video/mp4"
        const val VIDEO_QUICKTIME_MIME_TYPE = "video/quicktime"
        const val VIDEO_WEBM_MIME_TYPE = "video/webm"
        const val EXPORT_METADATA_MIME_TYPE = "application/sharehelper-export"
        const val PRIVACY_IMAGE_MAX_DIMENSION = 4096
        const val PRIVACY_IMAGE_MAX_PIXELS = 8_000_000L
        const val LOADING_HORIZONTAL_PADDING_DP = 32
        const val LOADING_VERTICAL_PADDING_DP = 24
        const val LOADING_TEXT_TOP_MARGIN_DP = 16
        const val LOADING_TEXT_SIZE_SP = 16f
        const val LOADING_DIALOG_CORNER_RADIUS_DP = 18
        const val LOADING_DIALOG_LIGHT_DIM_AMOUNT = 0.24f
        const val LOADING_DIALOG_DARK_DIM_AMOUNT = 0.42f
        const val PNG_BIT_DEPTH = 8
        const val PNG_COLOR_TYPE_RGBA = 6
        const val PNG_COMPRESSION_DEFLATE = 0
        const val PNG_FILTER_ADAPTIVE = 0
        const val PNG_FILTER_NONE = 0
        const val PNG_INTERLACE_NONE = 0
        const val PNG_RGBA_BYTES_PER_PIXEL = 4
        const val PNG_STREAM_CHUNK_SIZE = 64 * 1024

        val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        val PNG_CHUNK_IHDR = byteArrayOf(73, 72, 68, 82)
        val PNG_CHUNK_IDAT = byteArrayOf(73, 68, 65, 84)
        val PNG_CHUNK_TEXT = byteArrayOf(116, 69, 88, 116)
        val PNG_CHUNK_IEND = byteArrayOf(73, 69, 78, 68)
        const val PNG_TEXT_KEYWORD_EXPORT_ID = "ExportID"
    }
}
