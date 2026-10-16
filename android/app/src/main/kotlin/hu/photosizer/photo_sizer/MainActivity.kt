package hu.photosizer.photo_sizer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val pickRequest = 4711
    private var pendingPick: MethodChannel.Result? = null
    private var pendingRemember = true

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger

        MethodChannel(messenger, "photo_sizer/native").setMethodCallHandler { call, result ->
            try { handle(call, result) } catch (t: Throwable) { result.error("native", t.message, null) }
        }

        EventChannel(messenger, "photo_sizer/events").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(args: Any?, sink: EventChannel.EventSink) {
                ResizeEngine.listener = { sink.success(it) }
                ResizeEngine.replayLastEvent()
            }
            override fun onCancel(args: Any?) { ResizeEngine.listener = null }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "pickFolder" -> {
                if (pendingPick != null) { result.error("busy", "Már fut egy mappaválasztás", null); return }
                pendingPick = result
                pendingRemember = call.argument<Boolean>("remember") ?: true
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                @Suppress("DEPRECATION")
                startActivityForResult(intent, pickRequest)
            }
            "lastFolder" -> result.success(savedFolder("lastFolder"))
            "loadSettings" -> {
                val p = prefs()
                result.success(mapOf(
                    "maxSide" to p.getInt("maxSide", 1280),
                    "quality" to p.getInt("quality", 80),
                    "overwrite" to p.getBoolean("overwrite", true),
                    "copyOthers" to p.getBoolean("copyOthers", false),
                    "dst" to savedFolder("dstFolder"),
                ))
            }
            "saveSettings" -> {
                val e = prefs().edit()
                e.putInt("maxSide", call.argument<Int>("maxSide") ?: 1280)
                e.putInt("quality", call.argument<Int>("quality") ?: 80)
                e.putBoolean("overwrite", call.argument<Boolean>("overwrite") ?: true)
                e.putBoolean("copyOthers", call.argument<Boolean>("copyOthers") ?: false)
                val dst = call.argument<String>("dst")
                if (dst != null) e.putString("dstFolder", dst) else e.remove("dstFolder")
                e.apply()
                result.success(null)
            }
            "scan" -> {
                ResizeEngine.startScan(applicationContext, Uri.parse(call.argument<String>("uri")!!))
                result.success(null)
            }
            "run" -> {
                val p = RunParams(
                    src = Uri.parse(call.argument<String>("src")!!),
                    dst = call.argument<String>("dst")?.let { Uri.parse(it) },
                    copyOthers = call.argument<Boolean>("copyOthers") ?: false,
                    maxSide = call.argument<Int>("maxSide") ?: 1280,
                    quality = call.argument<Int>("quality") ?: 80,
                    limit = call.argument<Int>("limit") ?: 0,
                    total = (call.argument<Number>("total") ?: 0).toLong(),
                )
                ResizeEngine.startRun(applicationContext, p)
                result.success(null)
            }
            "cancel" -> { ResizeEngine.cancel(); result.success(null) }
            "isBusy" -> result.success(ResizeEngine.busy)
            else -> result.notImplemented()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != pickRequest) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val result = pendingPick ?: return
        pendingPick = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { result.success(null); return }
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (pendingRemember) prefs().edit().putString("lastFolder", uri.toString()).apply()
            result.success(folderInfo(uri))
        } catch (t: Throwable) {
            result.error("perm", t.message, null)
        }
    }

    private fun prefs() = getSharedPreferences("photo_sizer", Context.MODE_PRIVATE)

    /** A remembered folder, only if Android still lets the app read and write it. */
    private fun savedFolder(key: String): Map<String, String>? {
        val saved = prefs().getString(key, null) ?: return null
        val ok = contentResolver.persistedUriPermissions.any {
            it.uri.toString() == saved && it.isReadPermission && it.isWritePermission
        }
        return if (ok) folderInfo(Uri.parse(saved)) else null
    }

    /** {uri, label}: label like "SD-kártya: DCIM/Camera" */
    private fun folderInfo(uri: Uri): Map<String, String> {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val i = docId.indexOf(':')
        val volume = if (i > 0) docId.substring(0, i) else docId
        val path = if (i > 0) docId.substring(i + 1) else ""
        val where = if (volume == "primary") "Belső tárhely" else "SD-kártya"
        return mapOf("uri" to uri.toString(), "label" to if (path.isEmpty()) where else "$where: $path")
    }
}
