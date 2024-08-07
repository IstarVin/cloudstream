package com.lagradost.cloudstream3.ui.download

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.VideoDownloadHelper
import com.lagradost.cloudstream3.utils.VideoDownloadManager.getDownloadFileInfoAndUpdateSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DownloadViewModel : ViewModel() {
    private val _headerCards =
        MutableLiveData<List<VisualDownloadHeaderCached>>().apply { listOf<VisualDownloadHeaderCached>() }
    val headerCards: LiveData<List<VisualDownloadHeaderCached>> = _headerCards

    private val _usedBytes = MutableLiveData<Long>()
    private val _availableBytes = MutableLiveData<Long>()
    private val _downloadBytes = MutableLiveData<Long>()

    val usedBytes: LiveData<Long> = _usedBytes
    val availableBytes: LiveData<Long> = _availableBytes
    val downloadBytes: LiveData<Long> = _downloadBytes

    private var previousVisual: List<VisualDownloadHeaderCached>? = null

    fun updateList(context: Context) = viewModelScope.launchSafe {
        val children = withContext(Dispatchers.IO) {
            context.getKeys(DOWNLOAD_EPISODE_CACHE)
                .mapNotNull { context.getKey<VideoDownloadHelper.DownloadEpisodeCached>(it) }
                .distinctBy { it.id } // Remove duplicates
        }

        // parentId : bytes
        val totalBytesUsedByChild = HashMap<Int, Long>()
        // parentId : bytes
        val currentBytesUsedByChild = HashMap<Int, Long>()
        // parentId : downloadsCount
        val totalDownloads = HashMap<Int, Int>()

        // Gets all children downloads
        withContext(Dispatchers.IO) {
            children.forEach { c ->
                val childFile = getDownloadFileInfoAndUpdateSettings(context, c.id) ?: return@forEach

                if (childFile.fileLength <= 1) return@forEach
                val len = childFile.totalBytes
                val flen = childFile.fileLength

                totalBytesUsedByChild[c.parentId] = totalBytesUsedByChild[c.parentId]?.plus(len) ?: len
                currentBytesUsedByChild[c.parentId] = currentBytesUsedByChild[c.parentId]?.plus(flen) ?: flen
                totalDownloads[c.parentId] = totalDownloads[c.parentId]?.plus(1) ?: 1
            }
        }

        val cached = withContext(Dispatchers.IO) { // Won't fetch useless keys
            totalDownloads.entries.filter { it.value > 0 }.mapNotNull {
                context.getKey<VideoDownloadHelper.DownloadHeaderCached>(
                    DOWNLOAD_HEADER_CACHE,
                    it.key.toString()
                )
            }
        }

        val visual = withContext(Dispatchers.IO) {
            cached.mapNotNull {
                val downloads = totalDownloads[it.id] ?: 0
                val bytes = totalBytesUsedByChild[it.id] ?: 0
                val currentBytes = currentBytesUsedByChild[it.id] ?: 0
                if (bytes <= 0 || downloads <= 0) return@mapNotNull null
                val movieEpisode =
                    if (!it.type.isMovieType()) null
                    else context.getKey<VideoDownloadHelper.DownloadEpisodeCached>(
                        DOWNLOAD_EPISODE_CACHE,
                        getFolderName(it.id.toString(), it.id.toString())
                    )
                VisualDownloadHeaderCached(
                    currentBytes = currentBytes,
                    totalBytes = bytes,
                    data = it,
                    child = movieEpisode,
                    currentOngoingDownloads = 0,
                    totalDownloads = downloads,
                )
            }.sortedBy {
                (it.child?.episode ?: 0) + (it.child?.season?.times(10000) ?: 0)
            } // Episode sorting by episode, lowest to highest
        }

        // Only update list if different from the previous one to prevent duplicate initialization
        if (visual != previousVisual) {
            previousVisual = visual

            try {
                val stat = StatFs(Environment.getExternalStorageDirectory().path)
                val localBytesAvailable = stat.availableBytes
                val localTotalBytes = stat.blockSizeLong * stat.blockCountLong
                val localDownloadedBytes = visual.sumOf { it.totalBytes }

                _usedBytes.postValue(localTotalBytes - localBytesAvailable - localDownloadedBytes)
                _availableBytes.postValue(localBytesAvailable)
                _downloadBytes.postValue(localDownloadedBytes)
            } catch (t: Throwable) {
                _downloadBytes.postValue(0)
                logError(t)
            }

            _headerCards.postValue(visual)
        }
    }
}