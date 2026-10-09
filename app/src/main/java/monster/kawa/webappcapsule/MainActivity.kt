package monster.kawa.webappcapsule

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import monster.kawa.webappcapsule.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        private const val START_URL = "https://ynoproject.net/"
        private const val SITE_HOST = "ynoproject.net"
        private const val SPOOF_USER_AGENT = false
    }

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private fun exitCustomView() {
        val v = customView ?: return
        (window.decorView as ViewGroup).removeView(v)
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
    }

    private lateinit var binding: ActivityMainBinding
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // جلوگیری از خاموش شدن خودکار صفحه
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                var results: Array<Uri>? = null

                if (data != null) {
                    val clipData = data.clipData
                    if (clipData != null) {
                        results = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                    } else {
                        data.data?.let { uri -> results = arrayOf(uri) }
                    }
                }
                filePathCallback?.onReceiveValue(results)
            } else {
                filePathCallback?.onReceiveValue(null)
            }
            filePathCallback = null
        }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* حتی اگر رد بشه سرویس کار می‌کنه، فقط نوتیف دیده نمی‌شه */ }

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setupKeepAlive()

        // کوکی‌ها (شامل third-party) برای لاگین و سشن
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true            // localStorage + IndexedDB
                @Suppress("DEPRECATION")
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                javaScriptCanOpenWindowsAutomatically = true
                // این دو مورد باعث می‌شدن صفحه با عرض ۹۸۰px چیده و کوچک بشه
                useWideViewPort = false
                loadWithOverviewMode = false
                setSupportZoom(false)
                builtInZoomControls = false

                allowFileAccess = false
                allowContentAccess = false

                // اگه با true شدن این گزینه سایت درست کار کرد و لگ نداشت، نگهش دار
                if (SPOOF_USER_AGENT) {
                    userAgentString = userAgentString
                        .replace("; wv", "")
                        .replace(Regex("Version/\\d+\\.\\d+\\s"), "")
                }
            }

            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            webChromeClient = object : WebChromeClient() {
                // پشتیبانی از دکمه‌ی Full Screen سایت (Fullscreen API)
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (customView != null) {
                        callback.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    (window.decorView as ViewGroup).addView(
                        view,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }

                override fun onHideCustomView() {
                    exitCustomView()
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback

                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }

                    try {
                        fileChooserLauncher.launch(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "نمی‌توان فایل منیجر را باز کرد", Toast.LENGTH_SHORT).show()
                        return false
                    }
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val host = request.url.host ?: ""
                    if (host == SITE_HOST || host.endsWith(".$SITE_HOST")) return false

                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "مرورگری یافت نشد", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    view.requestFocus()
                }

                // اگر سیستم پروسه‌ی رندر وب‌ویو رو برای آزادسازی رم کشت، اکتیویتی دوباره ساخته می‌شه
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    recreate()
                    return true
                }
            }

            setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                if (url.startsWith("blob:")) {
                    val js = """
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$url', true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            var blob = xhr.response;
                            var realType = blob.type && blob.type.length > 0 ? blob.type : '$mimetype';
                            var reader = new FileReader();
                            reader.readAsDataURL(blob);
                            reader.onloadend = function() {
                                Android.saveBlob(reader.result, realType, '${contentDisposition ?: ""}');
                            };
                        };
                        xhr.send();
                    """.trimIndent()

                    evaluateJavascript(js, null)
                    Toast.makeText(this@MainActivity, "در حال آماده‌سازی فایل...", Toast.LENGTH_SHORT).show()
                } else {
                    downloadFileManually(url, userAgent, contentDisposition, mimetype)
                }
            })

            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun saveBlob(base64Data: String, mimeType: String, contentDisposition: String) {
                    runOnUiThread {
                        try {
                            val base64 = base64Data.substring(base64Data.indexOf(",") + 1)
                            val bytes = Base64.decode(base64, Base64.DEFAULT)

                            val detectedType = detectMimeTypeFromMagicBytes(bytes)
                            val effectiveMimeType = detectedType ?: mimeType

                            var fileName = guessFileNameFromDisposition(contentDisposition, effectiveMimeType)
                            if (detectedType != null) {
                                fileName = fixExtensionForDetectedType(fileName, detectedType)
                            }

                            bytes.inputStream().use { input ->
                                saveToDownloads(fileName, effectiveMimeType, input)
                            }

                            Toast.makeText(
                                this@MainActivity,
                                "فایل در Downloads ذخیره شد: $fileName",
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "خطا در ذخیره فایل: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }, "Android")

            if (savedInstanceState == null) {
                loadUrl(START_URL)
            } else {
                restoreState(savedInstanceState)
            }
        }
    }

    // ───────────── زنده نگه داشتن برنامه در بکگراند ─────────────

    private fun setupKeepAlive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
        requestIgnoreBatteryOptimizations()
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (_: Exception) {
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // جلوگیری از متوقف شدن تایمرها و JS وقتی اپ به بکگراند میره
        binding.webView.onResume()
        binding.webView.resumeTimers()
        CookieManager.getInstance().flush()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) {
            stopService(Intent(this, KeepAliveService::class.java))
        }
        binding.webView.destroy()
        super.onDestroy()
    }

    // ───────────── مدیریت فایل و دانلود ─────────────

    private fun guessFileNameFromDisposition(contentDisposition: String, mimeType: String): String {
        val regex = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?")
        val match = regex.find(contentDisposition)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        val extension = extensionFromMimeType(mimeType)
        return "file_${System.currentTimeMillis()}$extension"
    }

    private fun extensionFromMimeType(mimeType: String): String {
        if (mimeType.isBlank() || mimeType == "application/octet-stream") return ""
        val guessed = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return if (guessed != null) ".$guessed" else ""
    }

    private fun detectMimeTypeFromMagicBytes(bytes: ByteArray): String? {
        if (bytes.size < 8) return null

        val pngSignature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        if (bytes.copyOfRange(0, 8).contentEquals(pngSignature)) return "image/png"

        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }

        if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())
        ) {
            return "application/zip"
        }

        return null
    }

    private fun fixExtensionForDetectedType(fileName: String, detectedType: String): String {
        val correctExtension = extensionFromMimeType(detectedType)
        if (correctExtension.isEmpty()) return fileName
        if (fileName.endsWith(correctExtension, ignoreCase = true)) return fileName
        val baseName = fileName.substringBeforeLast('.', fileName)
        return "$baseName$correctExtension"
    }

    private fun saveToDownloads(fileName: String, mimeType: String?, input: InputStream) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType ?: "application/octet-stream")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val itemUri = resolver.insert(collection, values)
                ?: throw Exception("امکان ساخت فایل در Downloads وجود ندارد")

            resolver.openOutputStream(itemUri)?.use { output ->
                input.copyTo(output, bufferSize = 8 * 1024)
            } ?: throw Exception("امکان نوشتن در فایل وجود ندارد")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            FileOutputStream(file).use { output ->
                input.copyTo(output, bufferSize = 8 * 1024)
            }
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf(mimeType)) { _, _ -> }
        }
    }

    private fun downloadFileManually(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimetype: String?
    ) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)

        runOnUiThread {
            Toast.makeText(this, "در حال دانلود: $fileName", Toast.LENGTH_SHORT).show()
        }

        Thread {
            var connection: HttpURLConnection? = null
            try {
                val cookies = CookieManager.getInstance().getCookie(url)
                var currentUrl = url
                var redirects = 0

                while (redirects < 5) {
                    connection = URL(currentUrl).openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", userAgent)
                    if (!cookies.isNullOrEmpty()) {
                        connection.setRequestProperty("Cookie", cookies)
                    }
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000
                    connection.connect()

                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location") ?: break
                        currentUrl = URL(URL(currentUrl), location).toString()
                        connection.disconnect()
                        redirects++
                        continue
                    }
                    break
                }

                val conn = connection ?: throw Exception("اتصال برقرار نشد")
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP ${conn.responseCode}")
                }

                conn.inputStream.use { input ->
                    saveToDownloads(fileName, mimetype, input)
                }

                runOnUiThread {
                    Toast.makeText(this, "فایل ذخیره شد: $fileName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "دانلود ناموفق: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            exitCustomView()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}
}