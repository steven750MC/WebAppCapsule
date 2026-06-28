package monster.kawa.webappcapsule

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
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
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    assetLoader.shouldInterceptRequest(request.url)

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