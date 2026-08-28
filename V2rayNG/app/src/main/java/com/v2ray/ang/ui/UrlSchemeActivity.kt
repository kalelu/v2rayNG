package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UrlSchemeActivity : BaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val imported = try {
                importFromIntent(intent)
            } catch (error: Exception) {
                // Imported links may contain subscription tokens or proxy credentials. Never
                // attach the exception message or the input URI to logs.
                LogUtil.e(AppConfig.TAG, "External import failed: ${error.javaClass.simpleName}")
                false
            }

            if (imported) {
                toast(R.string.import_subscription_success)
            } else {
                toastError(R.string.import_subscription_failure)
            }
            startActivity(Intent(this@UrlSchemeActivity, MainActivity::class.java))
            finish()
        }
    }

    @Composable
    override fun ScreenContent() {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(text = stringResource(R.string.msg_dialog_progress))
        }
    }

    private suspend fun importFromIntent(sourceIntent: Intent): Boolean {
        val (uriString, fragment) = when (sourceIntent.action) {
            Intent.ACTION_SEND -> {
                if (sourceIntent.type != "text/plain") return false
                sourceIntent.getStringExtra(Intent.EXTRA_TEXT) to null
            }

            Intent.ACTION_VIEW -> {
                val sourceUri = sourceIntent.data ?: return false
                if (sourceUri.host != "install-config" && sourceUri.host != "install-sub") {
                    return false
                }
                sourceUri.getQueryParameter("url") to sourceUri.fragment
            }

            else -> return false
        }
        if (uriString.isNullOrBlank()) return false

        // ACTION_SEND text is not form-encoded, and getQueryParameter() already percent-decodes
        // ACTION_VIEW values. Decoding either a second time corrupts valid '+' credentials.
        var importedText = uriString
        val parsedUri = Uri.parse(importedText)
        if (parsedUri.fragment.isNullOrEmpty() && !fragment.isNullOrEmpty()) {
            importedText += "#$fragment"
        }
        val (count, countSub) = withContext(Dispatchers.IO) {
            AngConfigManager.importBatchConfig(importedText, "", false)
        }
        return count + countSub > 0
    }
}
