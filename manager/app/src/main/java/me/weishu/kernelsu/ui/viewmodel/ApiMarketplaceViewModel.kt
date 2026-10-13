package me.weishu.kernelsu.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.data.modulestore.RepoHttp
import me.weishu.kernelsu.data.themestore.normalizeStoreUrl
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.modulebanner.ApiMarketplaceItem
import me.weishu.kernelsu.modulebanner.ModuleBannerConfig
import okhttp3.Request
import org.json.JSONArray

class ApiMarketplaceViewModel : ViewModel() {

    var items by mutableStateOf<List<ApiMarketplaceItem>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var applyingUrl by mutableStateOf<String?>(null)
        private set
    var appliedUrl by mutableStateOf<String?>(null)
        private set
    var applyError by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            val result = withContext(Dispatchers.IO) {
                runCatching { parse(RepoHttp.get(buildUrl(Natives.getApiToken(ksuApp))).getOrThrow()) }
            }
            result.onSuccess { items = it }
                .onFailure { errorMessage = it.message ?: it::class.java.simpleName }
            isLoading = false
        }
    }

    fun apply(item: ApiMarketplaceItem) {
        viewModelScope.launch {
            applyingUrl = item.url
            appliedUrl = null
            applyError = null
            val verified = withContext(Dispatchers.IO) { verifyImage(item.url) }
            applyingUrl = null
            if (verified) {
                ModuleBannerConfig.updateApiSource(item.url)
                ModuleBannerConfig.updateApiModeEnabled(true)
                appliedUrl = item.url
            } else {
                applyError = item.url
            }
        }
    }

    fun consumeApplied() {
        appliedUrl = null
    }

    fun consumeApplyError() {
        applyError = null
    }

    private fun parse(body: String): List<ApiMarketplaceItem> {
        val array = JSONArray(body)
        val result = LinkedHashMap<String, ApiMarketplaceItem>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = normalizeStoreUrl(item.optString("url", ""))
            if (url.isBlank() || result.containsKey(url)) continue
            result[url] = ApiMarketplaceItem(
                name = item.optString("name", ""),
                url = url,
                description = item.optString("description", ""),
                descriptionEn = item.optString("description_en", ""),
            )
        }
        return result.values.toList()
    }

    private fun verifyImage(url: String): Boolean = runCatching {
        val request = Request.Builder().url(url).header("Accept", "image/*").build()
        ksuApp.okhttpClient.newCall(request).execute().use { response ->
            response.isSuccessful &&
                response.header("Content-Type")?.startsWith("image/", ignoreCase = true) == true
        }
    }.getOrDefault(false)

    private fun buildUrl(token: String): String =
        if (MARKETPLACE_URL.contains('?')) "$MARKETPLACE_URL&token=$token" else "$MARKETPLACE_URL?token=$token"

    companion object {
        private const val MARKETPLACE_URL = "https://folk.mysqil.com/api/banners"
    }
}
