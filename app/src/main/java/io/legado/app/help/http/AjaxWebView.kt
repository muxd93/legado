package io.legado.app.help.http

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.webkit.*
import io.legado.app.App
import org.apache.commons.text.StringEscapeUtils
import java.lang.ref.WeakReference


class AjaxWebView {
    var callback: Callback? = null
    private var mHandler: AjaxHandler

    init {
        mHandler = AjaxHandler(this)
    }

    class AjaxHandler(private val ajaxWebView: AjaxWebView) : Handler(Looper.getMainLooper()) {

        private var mWebView: WebView? = null

        override fun handleMessage(msg: Message) {
            val params: AjaxParams
            when (msg.what) {
                MSG_AJAX_START -> {
                    params = msg.obj as AjaxParams
                    mWebView = createAjaxWebView(params, this)
                }
                MSG_SNIFF_START -> {
                    params = msg.obj as AjaxParams
                    mWebView = createAjaxWebView(params, this)
                }
                MSG_SUCCESS -> {
                    ajaxWebView.callback?.onResult(msg.obj as String)
                    destroyWebView()
                }
                MSG_ERROR -> {
                    ajaxWebView.callback?.onError(msg.obj as Throwable)
                    destroyWebView()
                }
            }
        }

        @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
        fun createAjaxWebView(params: AjaxParams, handler: Handler): WebView {
            val webView = WebView(App.INSTANCE)
            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.blockNetworkImage = true
            settings.userAgentString = params.userAgent
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            if (params.isSniff) {
                webView.webViewClient = SnifferWebClient(params, handler)
            } else {
                webView.webViewClient = HtmlWebViewClient(params, handler)
            }
            when (params.requestMethod) {
                RequestMethod.POST -> webView.postUrl(params.url, params.postData)
                RequestMethod.GET -> webView.loadUrl(
                    params.url,
                    params.headerMap
                )
            }
            return webView
        }

        private fun destroyWebView() {
            mWebView?.destroy()
            mWebView = null
        }
    }

    fun load(params: AjaxParams) {
        if (params.sourceRegex != "") {
            mHandler.obtainMessage(MSG_SNIFF_START, params)
                .sendToTarget()
        } else {
            mHandler.obtainMessage(MSG_AJAX_START, params)
                .sendToTarget()
        }
    }

    fun destroyWebView() {
        mHandler.obtainMessage(DESTROY_WEB_VIEW)
    }

    class AjaxParams(private val tag: String) {
        var requestMethod = RequestMethod.GET
        var url: String? = null
        var postData: ByteArray? = null
        var headerMap: Map<String, String>? = null
        var cookieStore: CookieStore? = null
        var sourceRegex: String? = null
        var javaScript: String? = null

        val userAgent: String?
            get() = this.headerMap?.get("User-Agent")

        val isSniff: Boolean
            get() = !TextUtils.isEmpty(sourceRegex)

        fun setCookie(url: String) {
            if (cookieStore != null) {
                val cookie = CookieManager.getInstance().getCookie(url)
                cookieStore?.setCookie(tag, cookie)
            }
        }

        fun hasJavaScript(): Boolean {
            return !TextUtils.isEmpty(javaScript)
        }

        fun clearJavaScript() {
            javaScript = null
        }

    }

    class HtmlWebViewClient(
        private val params: AjaxParams,
        private val handler: Handler
    ) : WebViewClient() {

        override fun onPageFinished(view: WebView, url: String) {
            params.setCookie(url)
            handler.postDelayed({
                view.evaluateJavascript("document.documentElement.outerHTML") {
                    handler.obtainMessage(MSG_SUCCESS, StringEscapeUtils.unescapeJson(it))
                        .sendToTarget()
                }
            }, 1000)
        }

        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String,
            failingUrl: String
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                handler.obtainMessage(MSG_ERROR, Exception(description))
                    .sendToTarget()
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                handler.obtainMessage(
                    MSG_ERROR,
                    Exception(error.description.toString())
                ).sendToTarget()
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.proceed()
        }
    }

    class SnifferWebClient(
        private val params: AjaxParams,
        private val handler: Handler
    ) : WebViewClient() {

        override fun onLoadResource(view: WebView, url: String) {
            params.sourceRegex?.let {
                if (url.matches(it.toRegex())) {
                    handler.obtainMessage(MSG_SUCCESS, url)
                        .sendToTarget()
                }
            }
        }

        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String,
            failingUrl: String
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                handler.obtainMessage(MSG_ERROR, Exception(description))
                    .sendToTarget()
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                handler.obtainMessage(
                    MSG_ERROR,
                    Exception(error.description.toString())
                ).sendToTarget()
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.proceed()
        }

        override fun onPageFinished(view: WebView, url: String) {
            params.setCookie(url)
            if (params.hasJavaScript()) {
                evaluateJavascript(view, params.javaScript)
                params.clearJavaScript()
            }
        }

        private fun evaluateJavascript(webView: WebView, javaScript: String?) {
            val runnable = ScriptRunnable(webView, javaScript)
            handler.postDelayed(runnable, 1000L)
        }
    }

    class ScriptRunnable(
        webView: WebView,
        private val mJavaScript: String?
    ) : Runnable {

        private val mWebView: WeakReference<WebView> = WeakReference(webView)

        override fun run() {
            mWebView.get()?.loadUrl("javascript:${mJavaScript ?: ""}")
        }
    }

    companion object {
        const val MSG_AJAX_START = 0
        const val MSG_SNIFF_START = 1
        const val MSG_SUCCESS = 2
        const val MSG_ERROR = 3
        const val DESTROY_WEB_VIEW = 4
    }

    abstract class Callback {
        abstract fun onResult(result: String)
        abstract fun onError(error: Throwable)
    }
}