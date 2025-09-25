package io.github.jqssun.gpssetter.update

import android.content.Context
import android.os.Parcelable
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.utils.PrefManager
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject


class UpdateChecker @Inject constructor(private val apiResponse : GitHubService) {


    fun getLatestRelease() = callbackFlow {
        withContext(Dispatchers.IO){
            getReleaseList()?.let { gitHubReleaseResponse ->
                val currentTag = gitHubReleaseResponse.tagName

                if (currentTag != null && (currentTag != "v" + BuildConfig.TAG_NAME && PrefManager.isUpdateDisabled)) {
                    //New update available!
                    val asset =
                        gitHubReleaseResponse.assets?.firstOrNull { it.name?.endsWith(".apk") == true }
                    val releaseUrl =
                        asset?.browserDownloadUrl?.replace("/download/", "/tag/")?.apply {
                            substring(0, lastIndexOf("/"))
                        }
                    val name = gitHubReleaseResponse.name ?: run {
                        this@callbackFlow.trySend(null).isSuccess
                        return@let
                    }
                    val body = gitHubReleaseResponse.body ?: run {
                        this@callbackFlow.trySend(null).isSuccess
                        return@let
                    }
                    val publishedAt = gitHubReleaseResponse.publishedAt ?: run {
                        this@callbackFlow.trySend(null).isSuccess
                        return@let
                    }
                    this@callbackFlow.trySend(
                        Update(
                            name,
                            body,
                            publishedAt,
                            asset?.browserDownloadUrl
                                ?: "https://github.com/jqssun/android-gps-setter/releases",
                            asset?.name ?: "app-full-arm64-v8a-release.apk",
                            releaseUrl ?: "https://github.com/jqssun/android-gps-setter/releases"
                        )
                    ).isSuccess
                }
            } ?: run {
                this@callbackFlow.trySend(null).isSuccess
            }
        }
        awaitClose {  }
    }


    private fun getReleaseList(): GitHubRelease? {

        runCatching {
            apiResponse.getReleases().execute().body()
        }.onSuccess {
            return it
        }.onFailure {
            return null
        }
        return null
    }

    fun clearCachedDownloads(context: Context){
        File(context.externalCacheDir, "updates").deleteRecursively()
    }

    @Parcelize
    data class Update(val name: String, val changelog: String, val timestamp: String, val assetUrl: String, val assetName: String, val releaseUrl: String):
        Parcelable
}

