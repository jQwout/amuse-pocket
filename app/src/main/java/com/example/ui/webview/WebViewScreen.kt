package com.example.ui.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled", "InlinedApi")
@Composable
fun WebViewScreen(
    viewModel: WebViewViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Вход Qwen") },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.handleIntent(WebViewIntent.ForceProceed)
                        }
                    ) {
                        Text(
                            text = "Продолжить/Готово",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize().testTag("webview_screen")
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Embed web browser component loading Qwen AI
            AndroidView(
                modifier = Modifier.fillMaxSize().testTag("embedded_webview"),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        
                        // Enable cookie capture
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                viewModel.handleIntent(WebViewIntent.PageLoadProgress(newProgress))
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (url != null) {
                                    val cookieStr = CookieManager.getInstance().getCookie(url)
                                    viewModel.handleIntent(WebViewIntent.PageFinished(url, cookieStr))
                                }
                            }
                        }

                        // Load direct target URL
                        loadUrl(state.url)
                    }
                }
            )

            // Linear loader indicator
            if (state.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .testTag("webview_loader")
                )
            }
        }
    }
}

private fun getMockHtml(): String {
    return """
    <!DOCTYPE html>
    <html>
    <head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
      body {
        background-color: #0f172a;
        color: #f8fafc;
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        min-height: 100vh;
        margin: 0;
        padding: 24px;
        box-sizing: border-box;
      }
      .card {
        background: #1e293b;
        border-radius: 20px;
        padding: 32px;
        width: 100%;
        max-width: 400px;
        box-shadow: 0 10px 30px rgba(0,0,0,0.6);
        border: 1px solid #334155;
        text-align: center;
      }
      .logo {
        font-size: 2.4rem;
        font-weight: 800;
        margin-bottom: 6px;
        background: linear-gradient(135deg, #ec4899, #8b5cf6);
        -webkit-background-clip: text;
        -webkit-text-fill-color: transparent;
        letter-spacing: -0.06rem;
      }
      .subtitle {
        color: #94a3b8;
        font-size: 0.95rem;
        margin-bottom: 28px;
        line-height: 1.5;
      }
      input {
        width: 100%;
        padding: 14px;
        margin-bottom: 18px;
        border-radius: 10px;
        border: 1px solid #475569;
        background: #0f172a;
        color: white;
        font-size: 1rem;
        box-sizing: border-box;
      }
      input:focus {
        border-color: #ec4899;
        outline: none;
        box-shadow: 0 0 0 2px rgba(236, 72, 153, 0.2);
      }
      button {
        width: 100%;
        padding: 14px;
        border: none;
        border-radius: 10px;
        background: linear-gradient(135deg, #ec4899, #8b5cf6);
        color: white;
        font-size: 1.05rem;
        font-weight: 700;
        cursor: pointer;
        box-shadow: 0 4px 14px rgba(236, 72, 153, 0.4);
        transition: all 0.2s;
      }
      button:active {
        transform: scale(0.97);
      }
      .cookie-badge {
        margin-top: 20px;
        font-size: 0.82rem;
        color: #c084fc;
        background: rgba(192, 132, 252, 0.12);
        padding: 8px 16px;
        border-radius: 99px;
        display: inline-block;
      }
    </style>
    </head>
    <body>
      <div class="card">
        <div class="logo">AI Open Pocket</div>
        <div class="subtitle">Авторизуйтесь на защищенном узле связи Pocket ID, чтобы подгрузить сессионные куки.</div>
        
        <input type="text" id="username" placeholder="Имя пользователя" value="PocketAgent">
        <input type="password" placeholder="Ключ авторизации" value="••••••••">
        
        <button onclick="login()">Подключить Pocket ID</button>
        <div class="cookie-badge">SSL • AES-256 Защита включена</div>
      </div>

      <script>
        function login() {
          var user = document.getElementById('username').value || 'PocketAgent';
          var expiration = new Date();
          expiration.setTime(expiration.getTime() + (24 * 60 * 60 * 1000));
          document.cookie = "pocket_session_token=authorized_session_" + user + "_777; expires=" + expiration.toUTCString() + "; path=/;";
          
          alert('Авторизация успешна! Передаем извлеченную куки-сессию во ViewModel...');
          window.location.reload();
        }
      </script>
    </body>
    </html>
    """.trimIndent()
}
