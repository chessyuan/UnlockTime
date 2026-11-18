package com.phoneuse.files

import com.phoneuse.i18n.tr
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import com.phoneuse.chat.Attachment
import com.phoneuse.chat.AttachmentFiles
import com.phoneuse.model.InputItem
import com.phoneuse.service.PhoneUseAccessibilityService
import com.phoneuse.unified.AndroidDeviceAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * Copies what the user attaches into app-private storage: images (album, camera, screen, PDF pages) as
 * [ImageDownscaler]-sized JPEGs in `files/attachments/<uuid>.jpg`, so history thumbnails and re-sent images
 * survive a restart (picker URI grants do not); text files as their text. Files leave with their conversation
 * (SqliteChatRepository.delete) or, for an unsent draft, through [discard].
 */
class AttachmentStore(context: Context) {
    private val context = context.applicationContext

    private val dir: File get() = File(context.filesDir, AttachmentFiles.DIR).apply { mkdirs() }

    sealed interface Result {
        /** [notice] is a line for the user, e.g. that only the first PDF pages were taken. */
        data class Added(val attachments: List<Attachment>, val notice: String? = null) : Result
        data class Refused(val message: String) : Result
    }

    suspend fun importImage(uri: Uri): Attachment? = io("image") {
        decode(ImageDecoder.createSource(context.contentResolver, uri))
    }

    /** A camera shot written to [file] (see [cameraTarget]); the temporary file is removed. */
    suspend fun importCameraShot(file: File): Attachment? = io("camera") {
        try {
            if (file.length() > 0) decode(ImageDecoder.createSource(file)) else null
        } finally {
            file.delete()
        }
    }

    /** A new temporary file for the camera app to write to, and its content URI. */
    fun cameraTarget(): Pair<File, Uri> {
        val folder = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(folder, "shot-${UUID.randomUUID()}.jpg")
        return file to FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
    }

    /**
     * The screen of the app the user was just in. From inside this app that screen is gone (a capture would show
     * this app), so it is refused while this app is in front; the side-button sheet is the way to it.
     */
    suspend fun recentScreen(): Result {
        // This metadata-only check precedes preflight's HOME action. A request made in our chat must
        // not silently capture the launcher instead of the app the user intended to attach.
        val serviceAtRequest = PhoneUseAccessibilityService.instance ?: return Result.Refused(NEED_SERVICE)
        @Suppress("DEPRECATION")
        val front = withContext(Dispatchers.Main.immediate) {
            val root = serviceAtRequest.rootInActiveWindow
            try { root?.packageName?.toString() } finally { root?.recycle() }
        }
        if (front == context.packageName) return Result.Refused(USE_SIDE_BUTTON)
        var saved: Attachment? = null
        var delivered = false
        try {
            val result = AndroidDeviceAccess.task {
                val lease = AndroidDeviceAccess.ensureScreenAccess()
                val service = PhoneUseAccessibilityService.instance ?: return@task Result.Refused(NEED_SERVICE)
                val snapshot = service.controller.snapshot()
                AndroidDeviceAccess.validate(lease)
                if (snapshot.packageName == null || snapshot.ownAppInFront || snapshot.packageName == context.packageName) {
                    return@task Result.Refused(USE_SIDE_BUTTON)
                }
                val bytes = service.controller.screenshot()
                    ?: return@task Result.Refused(tr("Couldn't capture the screen", "화면을 찍지 못했어요"))
                try {
                    AndroidDeviceAccess.validate(lease)
                    io("screen") {
                        AndroidDeviceAccess.validate(lease)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@io null
                        save(scaled(bitmap), screenCapture = true) { AndroidDeviceAccess.validate(lease) }
                            .also { saved = it }
                    }
                    AndroidDeviceAccess.validate(lease)
                    saved?.let { Result.Added(listOf(it)) }
                        ?: Result.Refused(tr("Couldn't capture the screen", "화면을 찍지 못했어요"))
                } finally {
                    bytes.fill(0)
                }
            }
            currentCoroutineContext().ensureActive()
            delivered = true
            return result
        } finally {
            // Only this capture's new file is eligible for cleanup, never imported photos or history.
            if (!delivered) saved?.let { capture ->
                withContext(NonCancellable + Dispatchers.IO) { AttachmentFiles.delete(listOf(capture), dir) }
            }
        }
    }

    /** A text file becomes its text (up to [Attachment.MAX_TEXT_CHARS]); a PDF its first [PDF_PAGES] pages as images. */
    suspend fun importDocument(uri: Uri): Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: uri.lastPathSegment ?: tr("file", "파일")
        val mime = try {
            resolver.getType(uri)
        } catch (e: Exception) {
            null
        }.orEmpty()
        try {
            when {
                mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> pdf(uri, name)
                isText(mime, name) -> text(uri, name)
                else -> Result.Refused(UNSUPPORTED)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "import document failed", e)
            Result.Refused(tr("Couldn't read the file", "파일을 읽지 못했어요"))
        }
    }

    /** The stored image as model input; null when its file is gone. */
    fun loadImage(attachment: Attachment): InputItem.UserImage? {
        val file = AttachmentFiles.resolve(attachment, dir) ?: return null
        val screenCapture = isScreenCapture(attachment)
        if (screenCapture) AndroidDeviceAccess.requireAccess()
        return try {
            val bytes = file.readBytes()
            try {
                if (screenCapture) AndroidDeviceAccess.requireAccess()
                InputItem.UserImage(Base64.getEncoder().encodeToString(bytes), "image/jpeg").also {
                    if (screenCapture) AndroidDeviceAccess.requireAccess()
                }
            } finally {
                if (screenCapture) bytes.fill(0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Startup cleanup: attachment images no message refers to ([referenced] file names), e.g. from a draft the
     * process lost or a run that failed before storing its message, and stale camera shots. Only files older
     * than a day go, so a draft being written now is safe.
     */
    suspend fun sweep(referenced: Set<String>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val removed = AttachmentFiles.sweep(dir, referenced, now)
        AttachmentFiles.sweep(File(context.cacheDir, "camera"), emptySet(), now)
        if (removed > 0) Log.i(TAG, "swept $removed unreferenced attachment files")
    }

    /** Deletes the files of attachments that were never sent. */
    suspend fun discard(attachments: List<Attachment>) {
        if (attachments.isEmpty()) return
        withContext(Dispatchers.IO) { AttachmentFiles.delete(attachments, dir) }
    }

    private fun pdf(uri: Uri, name: String): Result {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return Result.Refused(tr("Couldn't read the file", "파일을 읽지 못했어요"))
        val opened = try {
            PdfRenderer(pfd)
        } catch (e: Exception) {
            pfd.close()
            if (e is SecurityException) return Result.Refused(tr("Can't open a locked PDF", "잠긴 PDF는 열 수 없어요"))
            throw e
        }
        // Pages already saved are deleted again when a later page fails.
        val pages = ArrayList<Attachment>()
        var total = 0
        try {
            opened.use { renderer ->
                total = renderer.pageCount
                for (i in 0 until minOf(total, PDF_PAGES)) {
                    renderer.openPage(i).use { page ->
                        val (w, h) = ImageDownscaler.targetSize(page.width, page.height, upscale = true)
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pages += save(bitmap, tr("$name p.${i + 1}", "$name ${i + 1}쪽"))
                    }
                }
            }
        } catch (e: Throwable) {
            AttachmentFiles.delete(pages, dir)
            // Password-protected PDFs.
            if (e is SecurityException) return Result.Refused(tr("Can't open a locked PDF", "잠긴 PDF는 열 수 없어요"))
            throw e
        }
        if (pages.isEmpty()) return Result.Refused(tr("This PDF is empty", "빈 PDF예요"))
        return Result.Added(pages, if (total > PDF_PAGES) tr("Attached only the first $PDF_PAGES pages of the PDF", "PDF는 앞 ${PDF_PAGES}쪽만 붙였어요") else null)
    }

    private fun text(uri: Uri, name: String): Result {
        val stream = context.contentResolver.openInputStream(uri) ?: return Result.Refused(tr("Couldn't read the file", "파일을 읽지 못했어요"))
        // Bounded: enough bytes for the character cap in any supported encoding, plus one to tell if there is more.
        val limit = TextDecoding.bytesFor(Attachment.MAX_TEXT_CHARS)
        val buffer = ByteArray(limit + 1)
        var read = 0
        stream.use { s ->
            while (read < buffer.size) {
                val n = s.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
        }
        val more = read > limit
        val text = TextDecoding.decode(buffer.copyOf(minOf(read, limit))) ?: return Result.Refused(UNREADABLE)
        if (text.isBlank()) return Result.Refused(tr("This file is empty", "빈 파일이에요"))
        val cut = more || text.length > Attachment.MAX_TEXT_CHARS
        return Result.Added(listOf(Attachment.text(if (cut) tr("$name (first 20,000 characters)", "$name (앞 20,000자)") else name, text)))
    }

    private fun decode(source: ImageDecoder.Source): Attachment {
        // ImageDecoder applies the EXIF rotation; the target size keeps the aspect ratio either way.
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = ImageDownscaler.targetSize(info.size.width, info.size.height)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return save(bitmap)
    }

    private fun scaled(bitmap: Bitmap): Bitmap {
        val (w, h) = ImageDownscaler.targetSize(bitmap.width, bitmap.height)
        if (w == bitmap.width && h == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, w, h, true).also { bitmap.recycle() }
    }

    /** Writes [bitmap] as JPEG (transparency on white) and recycles it. */
    private fun save(
        bitmap: Bitmap,
        name: String? = null,
        screenCapture: Boolean = false,
        validate: () -> Unit = {},
    ): Attachment {
        val opaque = if (bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { out ->
                Canvas(out).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
                bitmap.recycle()
            }
        } else {
            bitmap
        }
        val file = File(dir, "${if (screenCapture) SCREEN_PREFIX else ""}${UUID.randomUUID()}.jpg")
        try {
            validate()
            file.outputStream().use { opaque.compress(Bitmap.CompressFormat.JPEG, ImageDownscaler.JPEG_QUALITY, it) }
            validate()
        } catch (e: Throwable) {
            file.delete()
            throw e
        } finally {
            opaque.recycle()
        }
        return Attachment.image(file.name, name)
    }

    private suspend fun <T> io(what: String, block: () -> T?): T? = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "import $what failed", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "import $what ran out of memory", e)
            null
        }
    }

    companion object {
        private const val TAG = "AttachmentStore"
        private const val SCREEN_PREFIX = "screen-"
        fun isScreenCapture(attachment: Attachment): Boolean = attachment.file?.let { File(it).name.startsWith(SCREEN_PREFIX) } == true
        /** Past screen images are context, not a fresh request to unlock/read the device. */
        fun historicalImageLoader(loadImage: (Attachment) -> InputItem.UserImage?): (Attachment) -> InputItem.UserImage? =
            { attachment -> if (isScreenCapture(attachment)) null else loadImage(attachment) }
        const val PDF_PAGES = 3
        val USE_SIDE_BUTTON get() = tr("Use the side button sheet for the screen you just saw", "방금 본 화면은 측면 버튼 시트에서 쓰세요")
        val NEED_SERVICE get() = tr("Turn on the accessibility service to use the last screen", "방금 화면을 쓰려면 접근성 서비스를 켜 주세요")
        val UNSUPPORTED get() = tr("Only text or PDF files can be attached", "텍스트나 PDF 파일만 붙일 수 있어요")
        val UNREADABLE get() = tr("The text in this file can't be read", "글자를 읽을 수 없는 파일이에요")

        private val TEXT_TYPES = setOf(
            "application/json", "application/xml", "application/javascript", "application/x-yaml",
            "application/csv", "application/x-sh", "application/rtf",
        )
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "log", "html", "htm", "kt", "java",
            "py", "js", "ts", "c", "cpp", "h", "sh", "ini", "conf", "srt", "vtt",
        )

        fun isText(mime: String, name: String): Boolean =
            mime.startsWith("text/") || mime in TEXT_TYPES ||
                name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

        /** Decodes [path] for a thumbnail with a long edge of at least [px]; null when the file is gone. */
        fun thumbnail(path: String, px: Int): Bitmap? = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) {
                null
            } else {
                BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply { inSampleSize = ImageDownscaler.sampleSize(bounds.outWidth, bounds.outHeight, px) },
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
