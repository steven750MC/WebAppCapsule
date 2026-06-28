package monster.kawa.webappcapsule

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import monster.kawa.webappcapsule.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                allowFileAccess = false
                allowContentAccess = false
            }
            
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    // فقط درخواست‌های دامنه خودمون رو از assets بخون
                    return if (request.url.host == "appassets.androidplatform.net") {
                        assetLoader.shouldInterceptRequest(request.url)
                    } else {
                        // بقیه رو بذار WebView از اینترنت بگیره (iframe آنلاین و...)
                        super.shouldInterceptRequest(view, request)
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    
                    return when {
                        // لینک‌های intent:// رو با Intent باز کن
                        url.startsWith("intent://") -> {
                            try {
                                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                if (packageManager.resolveActivity(intent, 0) != null) {
                                    startActivity(intent)
                                } else {
                                    // fallback اگه اپی نبود
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
                        
                        // لینک‌های داخلی رو خود WebView باز کنه
                        request.url.host == "appassets.androidplatform.net" -> false
                        
                        // بقیه لینک‌های خارجی رو با مرورگر باز کن
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
            
            // Download listener for WebView
            setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                try {
                    val request = DownloadManager.Request(Uri.parse(url))
                    
                    // Set notification visibility
                    request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    
                    // Set destination
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                    request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName
                    )
                    
                    // Add cookies for authentication if needed
                    val cookies = CookieManager.getInstance().getCookie(url)
                    request.addRequestHeader("cookie", cookies)
                    
                    request.addRequestHeader("User-Agent", userAgent)
                    request.setMimeType(mimetype)
                    
                    // Start download
                    val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadManager.enqueue(request)
                    
                    Toast.makeText(
                        this@MainActivity,
                        "Downloading $fileName",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "Download failed: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
            
            loadUrl("https://appassets.androidplatform.net/index.html")
        }
    }

    // Back button handling for WebView
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.webView.canGoBack()) {
            binding.webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Handle back press for WebView (alternative method)
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
} 