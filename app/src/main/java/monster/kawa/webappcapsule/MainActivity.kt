package monster.kawa.webappcapsule

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.MimeTypeMap
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
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import monster.kawa.webappcapsule.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var prefs: SharedPreferences

    // ---- Auto-import config ----
    // Folder name that is reported to the loader as the "picked" root folder.
    // The loader only cares that a "www/" segment shows up somewhere in the
    // relative path, so the exact name here doesn't matter.
    private val GAME_ROOT_LABEL = "Game"
    private val PREFS_NAME = "webappcapsule_prefs"
    private val KEY_IMPORTED = "game_imported"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Remote debugging: connect the phone via USB, enable USB debugging
        // in Developer Options, then open chrome://inspect on a PC with
        // Chrome. This app's WebView will show up there with a real
        // Console + Network tab - the only reliable way to see what's
        // actually happening with sw.js registration and script loads
        // inside THIS app (as opposed to some other local server/tab).
        WebView.setWebContentsDebuggingEnabled(true)

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

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        // CRITICAL: requests made by the Service Worker itself (fetching
        // sw.js to register/update it, and any fetch() inside the SW's own
        // fetch handler) do NOT go through WebViewClient.shouldInterceptRequest.
        // They go through a separate pipe. Without wiring the same
        // assetLoader in here too, sw.js registration fails with a generic
        // "unknown error occurred when fetching the script" - which is
        // exactly what was happening. This is what actually fixes it.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
        ) {
            val swController = ServiceWorkerControllerCompat.getInstance()
            swController.setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    return if (request.url.host == "appassets.androidplatform.net") {
                        assetLoader.shouldInterceptRequest(request.url)
                    } else {
                        null
                    }
                }
            })
        } else {
            Toast.makeText(
                this,
                "این نسخه‌ی WebView از Service Worker پشتیبانی کامل نمی‌کنه؛ ممکنه بازی بالا نیاد",
                Toast.LENGTH_LONG
            ).show()
        }

        // First run (or if a previous import never finished): start at
        // loader.html so the auto-import script can run. Once the game is
        // confirmed imported, jump straight to index.html forever after.
        val alreadyImported = prefs.getBoolean(KEY_IMPORTED, false)
        val startUrl = if (alreadyImported) {
            "https://appassets.androidplatform.net/index.html"
        } else {
            "https://appassets.androidplatform.net/loader.html"
        }

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

                    // Only auto-import from loader.html, and only if we
                    // haven't already imported successfully before.
                    if (!prefs.getBoolean(KEY_IMPORTED, false) && url.contains("loader.html")) {
                        view.evaluateJavascript(buildAutoImportScript(), null)
                    }
                }
            }

            setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                if (url.startsWith("blob:")) {
                    // نکته مهم: mimetype و contentDisposition که خود WebView برای blob می‌ده
                    // اغلب نادرست/خالی است (مثلا text/plain به‌جای image/png).
                    // برای همین نوع واقعی رو مستقیم از خود blob.type می‌خونیم.
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

                            // به‌جای اعتماد کامل به mimeType (که برای بعضی فایل‌ها نادرست می‌رسه)،
                            // نوع واقعی فایل رو از روی magic bytes خودش تشخیص می‌دیم.
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

                // Called from the injected auto-import script once storeFiles()
                // has actually completed (not just "started"), right before it
                // clicks the Play button. Persists the flag so every future
                // launch skips loader.html and goes straight to index.html.
                @android.webkit.JavascriptInterface
                fun markImported() {
                    runOnUiThread {
                        prefs.edit().putBoolean(KEY_IMPORTED, true).apply()
                    }
                }

                // Optional: surface import failures as a Toast instead of
                // failing silently if something in the page changed.
                @android.webkit.JavascriptInterface
                fun reportImportError(message: String) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "خطا در ایمپورت خودکار بازی: $message", Toast.LENGTH_LONG).show()
                    }
                }
            }, "Android")

            loadUrl(startUrl)
        }
    }

    /**
     * Builds the JS that runs once loader.html finishes loading. It:
     *  1. Fetches assets/gamefiles-manifest.json (list of relative paths under www/).
     *  2. Fetches each file's bytes from assets/gamefiles/<path> (served via the
     *     existing WebViewAssetLoader, so this is a normal same-origin fetch()).
     *  3. Wraps them as File objects with a faked webkitRelativePath, exactly
     *     what the folder-picker's own onFileInputChange() handler expects.
     *  4. Assigns them to #file-input and dispatches a real "change" event,
     *     which runs the loader's unmodified import pipeline (storeFiles()),
     *     including its own required-files validation.
     *  5. Waits for #btn-play to appear (import succeeded) and clicks it,
     *     which is the same as the user pressing Play themselves.
     *
     * Every fetch (manifest + each per-file fetch) goes through
     * fetchWithRetry(): a handful of retries with exponential backoff before
     * giving up, plus a cache-busting query param on retries so a broken/
     * empty response served once (e.g. transient WebViewAssetLoader hiccup)
     * doesn't get reused. This is what previously made the game hang on
     * "Loading" forever the moment a single fetch failed once - now a
     * transient failure is retried instead of aborting the whole import.
     *
     * If the game's own www/ root already contains "www" as its first path
     * segment inside the manifest, GAME_ROOT_LABEL is irrelevant; the loader's
     * normalizeFolderPath() only strips the FIRST path segment then an
     * optional leading "www/", so prefixing every path with "Game/www/" is
     * always safe regardless of how gamefiles/ is laid out.
     */
    private fun buildAutoImportScript(): String {
        return """
        (function() {
          if (window.__autoImportStarted) return;
          window.__autoImportStarted = true;

          function fail(msg) {
            try { Android.reportImportError(String(msg)); } catch (e) {}
          }

          function ss(key) {
            try { return sessionStorage.getItem(key); } catch (e) { return null; }
          }
          function ssSet(key, val) {
            try { sessionStorage.setItem(key, val); } catch (e) {}
          }
          function ssRemove(key) {
            try { sessionStorage.removeItem(key); } catch (e) {}
          }

          function sleep(ms) {
            return new Promise(function(resolve) { setTimeout(resolve, ms); });
          }

          // Tries to reach the loader's own status line if it's already
          // defined on the page by the time we run (it is, since this
          // script only fires from onPageFinished). Best-effort only -
          // never lets a UI failure break the import itself.
          function reportProgress(msg) {
            try {
              var el = document.getElementById('status');
              if (el) el.textContent = msg;
            } catch (e) {}
          }

          // Fetches url with retries + exponential backoff (with jitter)
          // before giving up. On every retry attempt (not the first try)
          // a cache-busting query param is appended, so a bad/empty
          // response that got served once isn't just replayed from some
          // intermediate cache. Treats both network errors (fetch reject)
          // and non-OK HTTP statuses as retryable failures.
          async function fetchWithRetry(url, maxRetries, label) {
            var attempt = 0;
            var lastErr = null;
            while (attempt <= maxRetries) {
              try {
                var target = url;
                if (attempt > 0) {
                  target += (url.indexOf('?') === -1 ? '?' : '&') + '_retry=' + attempt;
                  reportProgress('در حال تلاش مجدد (' + attempt + '/' + maxRetries + ')... ' + (label || ''));
                }
                var resp = await fetch(target, { cache: 'no-store' });
                if (resp.ok) return resp;
                lastErr = new Error('HTTP ' + resp.status + ' for ' + url);
              } catch (e) {
                lastErr = e;
              }
              attempt++;
              if (attempt <= maxRetries) {
                var backoff = Math.min(4000, 400 * Math.pow(2, attempt - 1));
                var jitter = Math.floor(Math.random() * 150);
                await sleep(backoff + jitter);
              }
            }
            throw lastErr || new Error('fetch failed for ' + url);
          }

          // Waits for the Play button to appear AND the Service Worker to
          // actually be controlling this page before clicking it. If the
          // button shows up before the SW has taken control (a real race:
          // storeFiles() can finish and reveal #btn-play before the SW's
          // controllerchange fires), clicking immediately leads straight to
          // index.html trying to load js/*.js with no SW to serve them from
          // IndexedDB -> "Failed to load script" boot crash.
          //
          // Fix: if we see the button but no controller yet, reload once
          // (same recovery pattern the loader itself uses elsewhere) and
          // keep waiting after the reload lands. Guarded by sessionStorage
          // so we never reload more than once per import attempt.
          function waitAndClickPlay() {
            var reloadedForControl = ss('twal_reload_for_control') === '1';
            var tries = 0;
            var maxTries = 1200; // ~10 minutes at 500ms, generous for big installs
            var poll = setInterval(function() {
              tries++;
              var playBtn = document.getElementById('btn-play');
              var visible = playBtn && playBtn.offsetParent !== null;
              var controlled = !!(navigator.serviceWorker && navigator.serviceWorker.controller);

              if (visible && controlled) {
                clearInterval(poll);
                ssRemove('twal_reload_for_control');
                ssRemove('twal_import_started');
                try { Android.markImported(); } catch (e) {}
                playBtn.click();
              } else if (visible && !controlled && !reloadedForControl) {
                clearInterval(poll);
                ssSet('twal_reload_for_control', '1');
                location.reload();
              } else if (tries >= maxTries) {
                clearInterval(poll);
                fail('timed out waiting for import / service worker control');
              }
            }, 500);
          }

          async function run() {
            try {
              // If we already dispatched the import on a previous load of
              // this same page (before a self-reload for SW control),
              // don't redo the fetch/File/dispatch dance - just resume
              // waiting for Play + control.
              if (ss('twal_import_started') === '1') {
                waitAndClickPlay();
                return;
              }
              ssSet('twal_import_started', '1');

              var manifestResp = await fetchWithRetry('gamefiles-manifest.json', 4, 'manifest');
              var paths = await manifestResp.json();
              if (!Array.isArray(paths) || paths.length === 0) throw new Error('empty manifest');

              var dt = new DataTransfer();
              var failedPaths = [];
              for (var i = 0; i < paths.length; i++) {
                var relPath = paths[i];
                reportProgress('در حال دریافت فایل ' + (i + 1) + ' از ' + paths.length + '...');
                try {
                  // Up to 5 retries per file: game installs can have
                  // thousands of small requests, and a handful of
                  // transient failures among them is normal - it's the
                  // give-up-on-first-miss behavior that caused the stuck
                  // "Loading" screen, not the failures themselves.
                  var resp = await fetchWithRetry('gamefiles/' + relPath, 5, relPath);
                  var blob = await resp.blob();
                  var name = relPath.split('/').pop();
                  var file = new File([blob], name, { type: blob.type || 'application/octet-stream' });
                  Object.defineProperty(file, 'webkitRelativePath', {
                    value: '$GAME_ROOT_LABEL/www/' + relPath,
                    configurable: true
                  });
                  dt.items.add(file);
                } catch (fileErr) {
                  failedPaths.push(relPath + ': ' + (fileErr && fileErr.message ? fileErr.message : String(fileErr)));
                }
              }

              // Fail loudly and specifically instead of silently handing
              // an incomplete file list to the loader's own storeFiles(),
              // which would otherwise just report generic "missing files".
              if (failedPaths.length > 0) {
                throw new Error(
                  'failed to fetch ' + failedPaths.length + ' of ' + paths.length + ' file(s) after retries:\n' +
                  failedPaths.slice(0, 10).join('\n') +
                  (failedPaths.length > 10 ? '\n...and ' + (failedPaths.length - 10) + ' more' : '')
                );
              }

              var input = document.getElementById('file-input');
              if (!input) throw new Error('#file-input not found on page');
              input.files = dt.files;
              input.dispatchEvent(new Event('change', { bubbles: true }));

              waitAndClickPlay();
            } catch (err) {
              // Let a fresh page load retry the whole import from scratch
              // rather than getting stuck permanently flagged as "started".
              ssRemove('twal_import_started');
              fail(err && err.message ? err.message : String(err));
            }
          }

          run();
        })();
        """.trimIndent()
    }

    /**
     * اسم فایل رو یا از هدر Content-Disposition استخراج می‌کنه،
     * یا در نبود اون، بر اساس mimeType واقعی یک اسم و پسوند درست می‌سازه
     * (به‌جای پیش‌فرض هاردکد شده‌ی .zip که باعث خرابی پسوند می‌شد).
     */
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

    /**
     * تشخیص نوع واقعی فایل از روی چند بایت اول (magic bytes/file signature).
     * چون mimeType و blob.type که از WebView/JS می‌رسن قابل‌اعتماد نیستن،
     * این روش قطعی‌ترین راه برای تشخیص PNG (اسکرین‌شات) در برابر ZIP (فایل سیو) است.
     * برای انواع دیگه (که تشخیص داده نشن) null برمی‌گردونه و رفتار قبلی حفظ می‌شه.
     */
    private fun detectMimeTypeFromMagicBytes(bytes: ByteArray): String? {
        if (bytes.size < 8) return null

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        val pngSignature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        if (bytes.copyOfRange(0, 8).contentEquals(pngSignature)) {
            return "image/png"
        }

        // JPEG: FF D8 FF
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }

        // ZIP (و فرمت‌های مبتنی بر ZIP): 50 4B 03 04 یا 50 4B 05 06 (خالی) یا 50 4B 07 08
        if (bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())
        ) {
            return "application/zip"
        }

        return null
    }

    /**
     * پسوند فایل رو با توجه به نوع تشخیص‌داده‌شده از magic bytes اصلاح می‌کنه.
     * مثلا save_123.zip.txt -> save_123.png وقتی محتوا واقعا PNG باشه.
     */
    private fun fixExtensionForDetectedType(fileName: String, detectedType: String): String {
        val correctExtension = extensionFromMimeType(detectedType)
        if (correctExtension.isEmpty()) return fileName

        // اگر اسم فایل از قبل با پسوند درست تموم می‌شه، دست‌نخورده برش می‌گردونیم
        if (fileName.endsWith(correctExtension, ignoreCase = true)) return fileName

        // نام پایه رو با حذف تمام پسوندهای شناخته‌شده‌ی اشتباه می‌سازیم
        val baseName = fileName.substringBeforeLast('.', fileName)
        return "$baseName$correctExtension"
    }

    /**
     * تابع مرکزی و یکسان برای ذخیره‌ی هر نوع فایل در پوشه‌ی Downloads.
     * روی Android 10+ (API 29+) از MediaStore استفاده می‌کنه (سازگار با Scoped Storage).
     * روی نسخه‌های قدیمی‌تر مستقیم توی File می‌نویسه.
     */
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
        val uri = Uri.parse(url)

        if (uri.host == "appassets.androidplatform.net") {
            copyFromAssets(uri, fileName, mimetype)
            return
        }

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
                        currentUrl = location
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

    private fun copyFromAssets(uri: Uri, fileName: String, mimetype: String?) {
        runOnUiThread {
            Toast.makeText(this, "در حال آماده‌سازی: $fileName", Toast.LENGTH_SHORT).show()
        }

        Thread {
            try {
                val assetPath = uri.path?.trimStart('/') ?: throw Exception("مسیر فایل نامعتبر است")

                assets.open(assetPath).use { input ->
                    saveToDownloads(fileName, mimetype, input)
                }

                runOnUiThread {
                    Toast.makeText(this, "فایل در Downloads ذخیره شد: $fileName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "خطا در ذخیره‌سازی: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {}
}