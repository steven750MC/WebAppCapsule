package monster.kawa.webappcapsule

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import monster.kawa.webappcapsule.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ثبت launcher برای انتخاب فایل
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                var results: Array<Uri>? = null
                
                if (data != null) {
                    val clipData = data.clipData
                    
                    if (clipData != null) {
                        results = Array(clipData.itemCount) { i ->
                            clipData.getItemAt(i).uri
                        }
                    } else {
                        data.data?.let { uri ->
                            results = arrayOf(uri)
                        }
                    }
                }
                filePathCallback?.onReceiveValue(results)
                filePathCallback = null
            } else {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
            }
        }

        // Immersive mode
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databasePath = applicationContext.filesDir.path + "/databases/"
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
            }

            webChromeClient = object : WebChromeClient() {
                // مدیریت انتخاب فایل برای Upload
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
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    return if (request.url.host == "appassets.androidplatform.net") {
                        assetLoader.shouldInterceptRequest(request.url)
                    } else {
                        super.shouldInterceptRequest(view, request)
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()

                    return when {
                        url.startsWith("intent://") -> {
                            try {
                                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                if (packageManager.resolveActivity(intent, 0) != null) {
                                    startActivity(intent)
                                } else {
                                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                    if (fallbackUrl != null) {
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
                                    } else {
                                        Toast.makeText(this@MainActivity, "اپی برای باز کردن این لینک یافت نشد", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, "خطا در باز کردن لینک: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                            true
                        }

                        request.url.host == "appassets.androidplatform.net" -> false

                        else -> {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, "مرورگری یافت نشد", Toast.LENGTH_SHORT).show()
                            }
                            true
                        }
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    view.requestFocus()
                }
            }

            setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                try {
                    if (url.startsWith("blob:")) {
                        val js = """
                            var xhr = new XMLHttpRequest();
                            xhr.open('GET', '$url', true);
                            xhr.responseType = 'blob';
                            xhr.onload = function() {
                                var reader = new FileReader();
                                reader.readAsDataURL(xhr.response);
                                reader.onloadend = function() {
                                    Android.saveBlob(reader.result, '$mimetype', '${contentDisposition ?: ""}');
                                };
                            };
                            xhr.send();
                        """.trimIndent()
                        
                        evaluateJavascript(js, null)
                        Toast.makeText(this@MainActivity, "در حال آماده‌سازی فایل سیو...", Toast.LENGTH_SHORT).show()
                        return@DownloadListener
                    }

                    val request = DownloadManager.Request(Uri.parse(url))
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    val cookies = CookieManager.getInstance().getCookie(url)
                    request.addRequestHeader("cookie", cookies)
                    request.addRequestHeader("User-Agent", userAgent)
                    request.setMimeType(mimetype)

                    val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadManager.enqueue(request)
                    Toast.makeText(this@MainActivity, "Downloading $fileName", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            })

            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun saveBlob(base64Data: String, mimeType: String, contentDisposition: String) {
                    runOnUiThread {
                        try {
                            val base64 = base64Data.substring(base64Data.indexOf(",") + 1)
                            val bytes = Base64.decode(base64, Base64.DEFAULT)
                            
                            // ذخیره مستقیم در پوشه Downloads
                            val fileName = "save_${System.currentTimeMillis()}.zip"
                            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            val file = File(downloadsDir, fileName)
                            FileOutputStream(file).use { it.write(bytes) }
                            
                            // اسکن فایل برای نمایش در فایل منیجر
                            MediaScannerConnection.scanFile(
                                this@MainActivity,
                                arrayOf(file.absolutePath),
                                arrayOf(mimeType)
                            ) { path, uri ->
                                // فایل اسکن شد
                            }
                            
                            Toast.makeText(
                                this@MainActivity,
                                "فایل سیو در Downloads ذخیره شد: $fileName",
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "خطا در ذخیره فایل: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }, "Android")

            loadUrl("https://appassets.androidplatform.net/index.html")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {}
}