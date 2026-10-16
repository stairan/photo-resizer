package hu.photosizer.photo_sizer

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

class RunParams(
    val src: Uri,
    val dst: Uri?,            // null = overwrite the originals
    val copyOthers: Boolean,  // copy non-image files (new-folder mode only)
    val maxSide: Int,
    val quality: Int,
    val limit: Int,           // >0: stop after this many resized images (test run)
    val total: Long,          // expected number of files, for the progress bar only
) {
    val overwrite get() = dst == null
}

private class Entry(val docId: String, val name: String, val mime: String?, val size: Long) {
    val isDir get() = mime == Document.MIME_TYPE_DIR
}

private class Info(val w: Int, val h: Int, val orientation: Int)

private class Encoded(val bytes: ByteArray, val w: Int, val h: Int)

/**
 * Walks a folder tree (Storage Access Framework) and shrinks images / copies files.
 * Lives at process level so a foreground service can keep it alive while the UI is gone.
 */
object ResizeEngine {
    private const val TMP_SUFFIX = ".photosizer-tmp"
    private const val MAX_FAILURES_LISTED = 300
    private const val WORKERS = 3

    private val main = Handler(Looper.getMainLooper())

    @Volatile var listener: ((Map<String, Any?>) -> Unit)? = null
    @Volatile private var lastEvent: Map<String, Any?>? = null
    @Volatile var busy = false
        private set
    @Volatile private var cancelFlag = false

    // ---- run statistics ----
    private val done = AtomicLong()
    private val resized = AtomicLong()
    private val skippedSmall = AtomicLong()
    private val skippedNotSmaller = AtomicLong()
    private val skippedExisting = AtomicLong()
    private val copiedAsIs = AtomicLong()
    private val others = AtomicLong()
    private val failed = AtomicLong()
    private val bytesBefore = AtomicLong()
    private val bytesAfter = AtomicLong()
    private val failures = ConcurrentLinkedQueue<String>()
    private val touchedPaths = ConcurrentLinkedQueue<String>()
    @Volatile private var current = ""
    @Volatile private var lastEmit = 0L
    @Volatile private var lastNotify = 0L

    fun replayLastEvent() {
        lastEvent?.let { e -> main.post { listener?.invoke(e) } }
    }

    fun cancel() { cancelFlag = true }

    private fun emit(e: Map<String, Any?>) {
        lastEvent = e
        main.post { listener?.invoke(e) }
    }

    // ---------------------------------------------------------------- scan

    fun startScan(ctx: Context, tree: Uri) {
        if (busy) return
        busy = true
        cancelFlag = false
        thread(name = "scan") {
            val r = ctx.contentResolver
            var images = 0L
            var imageBytes = 0L
            var otherFiles = 0L
            var otherBytes = 0L
            var dirs = 0L
            var last = 0L
            fun snapshot(isDone: Boolean) = mapOf(
                "type" to "scan", "done" to isDone, "images" to images, "imageBytes" to imageBytes,
                "others" to otherFiles, "otherBytes" to otherBytes, "dirs" to dirs,
            )
            fun walk(docId: String) {
                if (cancelFlag) return
                for (e in listChildren(r, tree, docId)) {
                    if (e.isDir) {
                        dirs++
                        walk(e.docId)
                    } else if (isImage(e.name)) {
                        images++; imageBytes += e.size
                    } else if (!e.name.endsWith(TMP_SUFFIX)) {
                        otherFiles++; otherBytes += e.size
                    }
                    val now = System.currentTimeMillis()
                    if (now - last > 250) { last = now; emit(snapshot(false)) }
                }
            }
            try {
                walk(DocumentsContract.getTreeDocumentId(tree))
                emit(snapshot(true))
            } catch (t: Throwable) {
                emit(mapOf("type" to "error", "message" to (t.message ?: t.toString())))
            } finally {
                busy = false
            }
        }
    }

    // ----------------------------------------------------------------- run

    fun startRun(ctx: Context, p: RunParams) {
        if (busy) return
        busy = true
        cancelFlag = false
        listOf(done, resized, skippedSmall, skippedNotSmaller, skippedExisting, copiedAsIs, others,
            failed, bytesBefore, bytesAfter).forEach { it.set(0) }
        failures.clear(); touchedPaths.clear(); current = ""; lastEmit = 0; lastNotify = 0

        val app = ctx.applicationContext
        ResizeService.start(app)
        val wake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "photo_sizer:run")
        wake.acquire(12 * 60 * 60 * 1000L)

        thread(name = "run") {
            val pool = ThreadPoolExecutor(
                WORKERS, WORKERS, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64),
                ThreadPoolExecutor.CallerRunsPolicy(),
            )
            var fatal: String? = null
            try {
                val srcRoot = DocumentsContract.getTreeDocumentId(p.src)
                val dstRoot = p.dst?.let { DocumentsContract.getTreeDocumentId(it) }
                walk(app, p, pool, srcRoot, dstRoot, "")
            } catch (t: Throwable) {
                fatal = t.message ?: t.toString()
            }
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.DAYS)
            try {
                val paths = touchedPaths.toTypedArray()
                if (paths.isNotEmpty()) MediaScannerConnection.scanFile(app, paths, null, null)
            } catch (_: Throwable) { }
            if (wake.isHeld) wake.release()
            ResizeService.stop(app)
            busy = false
            emit(snapshotRun(p, "done", cancelled = cancelFlag, fatal = fatal, withFailures = true))
        }
    }

    private fun limitReached(p: RunParams) = p.limit > 0 && resized.get() >= p.limit

    private fun walk(
        ctx: Context, p: RunParams, pool: ThreadPoolExecutor,
        srcDocId: String, dstDocId: String?, rel: String,
    ) {
        val r = ctx.contentResolver
        val children = listChildren(r, p.src, srcDocId)
        val dstNames: MutableMap<String, Entry>? =
            if (p.dst != null && dstDocId != null)
                listChildren(r, p.dst, dstDocId).associateBy { it.name }.toMutableMap()
            else null

        for (e in children) {
            if (cancelFlag || limitReached(p)) return
            val path = if (rel.isEmpty()) e.name else "$rel/${e.name}"
            if (e.isDir) {
                // never walk into the destination folder when it lives inside the source
                if (p.dst != null && p.src.authority == p.dst.authority &&
                    e.docId == DocumentsContract.getTreeDocumentId(p.dst)) continue
                var childDst: String? = null
                if (p.dst != null && dstDocId != null) {
                    val existing = dstNames!![e.name]
                    childDst = if (existing != null && existing.isDir) existing.docId
                    else {
                        val made = DocumentsContract.createDocument(
                            r, DocumentsContract.buildDocumentUriUsingTree(p.dst, dstDocId),
                            Document.MIME_TYPE_DIR, e.name,
                        ) ?: throw IOException("Mappa nem hozható létre: $path")
                        DocumentsContract.getDocumentId(made)
                    }
                }
                walk(ctx, p, pool, e.docId, childDst, path)
            } else {
                val image = isImage(e.name)
                if (e.name.endsWith(TMP_SUFFIX)) continue
                if (!image && !(p.dst != null && p.copyOthers && p.limit == 0)) continue
                if (p.dst != null && dstNames!!.containsKey(e.name)) {
                    skippedExisting.incrementAndGet(); done.incrementAndGet(); progress(p)
                    continue
                }
                pool.execute {
                    if (cancelFlag) return@execute
                    current = path
                    try {
                        if (image) handleImage(ctx, p, e, path, srcDocId, dstDocId)
                        else handleOther(ctx, p, e, path, dstDocId!!)
                    } catch (t: Throwable) {
                        fail(path, t.message ?: t.toString())
                    }
                    done.incrementAndGet()
                    progress(p)
                }
            }
        }
    }

    // ------------------------------------------------------------- one file

    private fun handleImage(ctx: Context, p: RunParams, e: Entry, path: String,
                            srcParent: String, dstParent: String?) {
        val r = ctx.contentResolver
        val srcUri = DocumentsContract.buildDocumentUriUsingTree(p.src, e.docId)
        val jpeg = isJpeg(e.name)
        val png = !jpeg

        val info = try { readInfo(r, srcUri, jpeg) } catch (_: Throwable) { null }
        if (info == null) {
            // unreadable (damaged / empty): never touch the original, keep a copy in new-folder mode
            if (p.dst != null && dstParent != null) {
                copyRaw(r, p, srcUri, e, dstParent)
                fail(path, "Sérült kép, változatlanul átmásolva")
            } else fail(path, "Sérült vagy üres kép, nem módosítva")
            return
        }
        if (max(info.w, info.h) <= p.maxSide) {
            skippedSmall.incrementAndGet()
            if (p.dst != null && dstParent != null) { copyRaw(r, p, srcUri, e, dstParent); copiedAsIs.incrementAndGet() }
            return
        }

        val enc = encode(r, srcUri, info, p, png)

        if (p.dst == null) {
            // ---------- overwrite: temp file next to the original, verify, then swap
            if (e.size > 0 && enc.bytes.size >= e.size) { skippedNotSmaller.incrementAndGet(); return }
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(p.src, srcParent)
            var tmp: Uri? = null
            var originalDeleted = false
            try {
                tmp = DocumentsContract.createDocument(
                    r, parentUri, "application/octet-stream", e.name + TMP_SUFFIX,
                ) ?: throw IOException("Ideiglenes fájl nem hozható létre")
                r.openOutputStream(tmp, "wt")!!.use { it.write(enc.bytes) }
                if (jpeg) copyExif(r, srcUri, tmp, enc.w, enc.h)
                val check = readInfo(r, tmp, false)
                if (check == null || check.w != enc.w || check.h != enc.h)
                    throw IOException("Az új fájl ellenőrzése sikertelen")
                val newSize = querySize(r, tmp)
                if (!DocumentsContract.deleteDocument(r, srcUri))
                    throw IOException("Az eredeti fájl nem törölhető")
                originalDeleted = true
                DocumentsContract.renameDocument(r, tmp, e.name)
                    ?: throw IOException("Átnevezés sikertelen, az új fájl itt maradt: ${e.name}$TMP_SUFFIX")
                tmp = null
                resized.incrementAndGet()
                bytesBefore.addAndGet(e.size); bytesAfter.addAndGet(newSize)
                docIdToPath(e.docId)?.let { touchedPaths.add(it) }
            } catch (t: Throwable) {
                if (tmp != null && !originalDeleted) try { DocumentsContract.deleteDocument(r, tmp) } catch (_: Throwable) { }
                throw t
            }
        } else {
            // ---------- new folder
            val dstParentUri = DocumentsContract.buildDocumentUriUsingTree(p.dst, dstParent!!)
            val out = DocumentsContract.createDocument(r, dstParentUri, mimeFor(e.name), e.name)
                ?: throw IOException("Cél fájl nem hozható létre")
            try {
                r.openOutputStream(out, "wt")!!.use { it.write(enc.bytes) }
                if (jpeg) try { copyExif(r, srcUri, out, enc.w, enc.h) }
                catch (t: Throwable) { fail(path, "EXIF nem másolható: ${t.message}") }
            } catch (t: Throwable) {
                try { DocumentsContract.deleteDocument(r, out) } catch (_: Throwable) { }
                throw t
            }
            resized.incrementAndGet()
            bytesBefore.addAndGet(e.size); bytesAfter.addAndGet(querySize(r, out))
            docIdToPath(DocumentsContract.getDocumentId(out))?.let { touchedPaths.add(it) }
        }
    }

    private fun handleOther(ctx: Context, p: RunParams, e: Entry, path: String, dstParent: String) {
        val r = ctx.contentResolver
        val srcUri = DocumentsContract.buildDocumentUriUsingTree(p.src, e.docId)
        copyRaw(r, p, srcUri, e, dstParent)
        others.incrementAndGet()
    }

    private fun copyRaw(r: ContentResolver, p: RunParams, srcUri: Uri, e: Entry, dstParent: String) {
        val dstParentUri = DocumentsContract.buildDocumentUriUsingTree(p.dst!!, dstParent)
        val out = DocumentsContract.createDocument(r, dstParentUri, mimeFor(e.name), e.name)
            ?: throw IOException("Cél fájl nem hozható létre")
        try {
            r.openInputStream(srcUri)!!.use { input ->
                r.openOutputStream(out, "wt")!!.use { output -> input.copyTo(output, 64 * 1024) }
            }
        } catch (t: Throwable) {
            try { DocumentsContract.deleteDocument(r, out) } catch (_: Throwable) { }
            throw t
        }
        docIdToPath(DocumentsContract.getDocumentId(out))?.let { touchedPaths.add(it) }
    }

    // ---------------------------------------------------------- image work

    private fun readInfo(r: ContentResolver, uri: Uri, jpeg: Boolean): Info? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var orientation = ExifInterface.ORIENTATION_NORMAL
        if (jpeg) try {
            r.openInputStream(uri)?.use {
                orientation = ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (_: Throwable) { }
        return Info(o.outWidth, o.outHeight, orientation)
    }

    private fun encode(r: ContentResolver, uri: Uri, info: Info, p: RunParams, png: Boolean): Encoded {
        val long = max(info.w, info.h)
        var sample = 1
        while (long / (sample * 2) >= p.maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = r.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("A kép nem dekódolható")
        val scale = p.maxSide.toFloat() / long
        val tw = (info.w * scale).roundToInt().coerceAtLeast(1)
        val th = (info.h * scale).roundToInt().coerceAtLeast(1)
        val m = Matrix()
        m.postScale(tw.toFloat() / bmp.width, th.toFloat() / bmp.height)
        applyOrientation(m, info.orientation)
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        val bos = ByteArrayOutputStream(256 * 1024)
        out.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, p.quality, bos)
        val w = out.width
        val h = out.height
        out.recycle()
        return Encoded(bos.toByteArray(), w, h)
    }

    private fun applyOrientation(m: Matrix, orientation: Int) {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        }
    }

    private val exifTags = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS,
        "PhotographicSensitivity",
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, "LensModel", // these two tags only exist in the API 34+ constants
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
    )

    /** Copies the camera metadata to the resized file; the pixels are already rotated, so orientation = normal. */
    private fun copyExif(r: ContentResolver, srcUri: Uri, dstUri: Uri, w: Int, h: Int) {
        val values = HashMap<String, String>()
        r.openInputStream(srcUri)!!.use { input ->
            val src = ExifInterface(input)
            for (tag in exifTags) src.getAttribute(tag)?.let { values[tag] = it }
        }
        r.openFileDescriptor(dstUri, "rw")!!.use { pfd ->
            val dst = ExifInterface(pfd.fileDescriptor)
            for ((k, v) in values) dst.setAttribute(k, v)
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, w.toString())
            dst.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, h.toString())
            dst.saveAttributes()
        }
    }

    // ------------------------------------------------------------- helpers

    private fun fail(path: String, reason: String) {
        failed.incrementAndGet()
        if (failures.size < MAX_FAILURES_LISTED) failures.add("$path — $reason")
    }

    private fun progress(p: RunParams) {
        val now = System.currentTimeMillis()
        if (now - lastEmit < 300) return
        lastEmit = now
        val snap = snapshotRun(p, "progress")
        emit(snap)
        if (now - lastNotify > 1000) {
            lastNotify = now
            ResizeService.update(done.get(), p.total, "${resized.get()} kép átméretezve")
        }
    }

    private fun snapshotRun(p: RunParams, type: String, cancelled: Boolean = false,
                            fatal: String? = null, withFailures: Boolean = false) = mutableMapOf<String, Any?>(
        "type" to type, "total" to p.total, "done" to done.get(), "resized" to resized.get(),
        "skippedSmall" to skippedSmall.get(), "skippedNotSmaller" to skippedNotSmaller.get(),
        "skippedExisting" to skippedExisting.get(), "copiedAsIs" to copiedAsIs.get(),
        "others" to others.get(), "failed" to failed.get(),
        "bytesBefore" to bytesBefore.get(), "bytesAfter" to bytesAfter.get(),
        "current" to current, "cancelled" to cancelled, "fatal" to fatal,
    ).also { if (withFailures) it["failures"] = failures.toList() }

    private fun listChildren(r: ContentResolver, tree: Uri, parentDocId: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val out = ArrayList<Entry>()
        r.query(
            uri,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(Entry(
                    c.getString(0), c.getString(1) ?: continue, c.getString(2),
                    if (c.isNull(3)) 0L else c.getLong(3),
                ))
            }
        }
        return out
    }

    private fun querySize(r: ContentResolver, uri: Uri): Long =
        r.query(uri, arrayOf(Document.COLUMN_SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        } ?: 0L

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    private fun isJpeg(name: String) = ext(name).let { it == "jpg" || it == "jpeg" }
    private fun isImage(name: String) = isJpeg(name) || ext(name) == "png"
    private fun mimeFor(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(name)) ?: "application/octet-stream"

    /** "primary:DCIM/Camera/x.jpg" -> /storage/emulated/0/DCIM/Camera/x.jpg ; "1A2B-3C4D:..." -> /storage/1A2B-3C4D/... */
    fun docIdToPath(docId: String): String? {
        val i = docId.indexOf(':')
        if (i <= 0) return null
        val volume = docId.substring(0, i)
        val rel = docId.substring(i + 1)
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return "$root/$rel"
    }
}
