package app.pulse.android.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pulse.android.BuildConfig
import app.pulse.android.preferences.DataPreferences
import app.pulse.core.data.utils.Version
import app.pulse.core.data.utils.version
import app.pulse.providers.github.GitHub
import app.pulse.providers.github.models.Release
import app.pulse.providers.github.requests.releases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.toJavaDuration

private val VERSION_NAME = BuildConfig.VERSION_NAME.substringBeforeLast("-")
private const val REPO_OWNER = "yashrajrocxx"
private const val REPO_NAME = "VMusic"
suspend fun Version.getNewerVersion(
    repoOwner: String = REPO_OWNER,
    repoName: String = REPO_NAME,
    contentType: String = "application/vnd.android.package-archive"
) = GitHub.releases(
    owner = repoOwner,
    repo = repoName
)?.mapCatching { releases ->
    releases
        .sortedByDescending { it.publishedAt }
        .firstOrNull { release ->
            !release.draft &&
                !release.preRelease &&
                release.tag.version > this &&
                release.assets.any { asset ->
                    (asset.name.endsWith(".apk", ignoreCase = true) || asset.contentType == contentType) &&
                        asset.state == Release.Asset.State.Uploaded
                }
        }
}

fun findBestApkAsset(assets: List<Release.Asset>): Release.Asset? {
    val apkAssets = assets.filter {
        (it.name.endsWith(".apk", ignoreCase = true) || it.contentType == "application/vnd.android.package-archive") &&
            it.state == Release.Asset.State.Uploaded
    }
    if (apkAssets.isEmpty()) return null
    if (apkAssets.size == 1) return apkAssets.first()

    val supportedAbis = android.os.Build.SUPPORTED_ABIS.map { it.lowercase() }
    for (abi in supportedAbis) {
        val match = apkAssets.firstOrNull { asset ->
            val name = asset.name.lowercase()
            when {
                abi.contains("arm64") -> name.contains("arm64") || name.contains("aarch64")
                abi.contains("armeabi-v7a") || abi.contains("armv7") -> name.contains("armeabi-v7a") || name.contains("armv7") || name.contains("arm32")
                abi.contains("x86_64") -> name.contains("x86_64")
                abi.contains("x86") -> name.contains("x86") && !name.contains("x86_64")
                else -> false
            }
        }
        if (match != null) return match
    }

    return apkAssets.firstOrNull { it.name.contains("universal", ignoreCase = true) } ?: apkAssets.first()
}

class VersionCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_TAG = "version_check_worker"

        fun executeOneTime(context: Context) {
            val request = OneTimeWorkRequestBuilder<VersionCheckWorker>()
                .addTag(WORK_TAG)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_TAG}_onetime",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun upsert(context: Context, period: Duration?) = runCatching {
            val workManager = WorkManager.getInstance(context)

            if (period == null) {
                workManager.cancelAllWorkByTag(WORK_TAG)
                return@runCatching
            }

            val request = PeriodicWorkRequestBuilder<VersionCheckWorker>(period.toJavaDuration())
                .addTag(WORK_TAG)
                .setConstraints(
                    Constraints(
                        requiredNetworkType = NetworkType.CONNECTED,
                        requiresBatteryNotLow = true
                    )
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                /* uniqueWorkName = */ WORK_TAG,
                /* existingPeriodicWorkPolicy = */ ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                /* periodicWork = */ request
            )
        }.onFailure { it.printStackTrace() }
    }

    override suspend fun doWork(): Result = with(applicationContext) {
        val result = withContext(Dispatchers.IO) {
            VERSION_NAME.version.getNewerVersion().also { it?.exceptionOrNull()?.printStackTrace() }
        }

        result?.getOrNull()?.let { release ->
            val asset = findBestApkAsset(release.assets)
            val url = asset?.downloadUrl?.toString() ?: release.frontendUrl.toString()

            // store pending update info for UI to show dialog
            DataPreferences.pendingUpdateVersion = release.tag.removePrefix("v")
            DataPreferences.pendingUpdateUrl = url
        }

        return when {
            result == null || result.isFailure -> Result.retry()
            result.isSuccess -> Result.success()
            else -> Result.failure()
        }
    }
}


