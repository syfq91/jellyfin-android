package org.jellyfin.mobile.book.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.book.model.BookPlayOptions
import org.jellyfin.mobile.book.repository.BookRepository
import org.jellyfin.mobile.data.dao.BookProgressDao
import org.jellyfin.mobile.data.entity.BookProgressEntity
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import timber.log.Timber

sealed class BookReaderState {
    data class Loading(val progress: Float) : BookReaderState()
    data class Ready(val publication: Publication, val initialLocator: Locator?) : BookReaderState()
    data class Error(val message: String) : BookReaderState()
}

class BookReaderViewModel(
    private val bookRepository: BookRepository,
    private val bookProgressDao: BookProgressDao,
    private val apiClient: ApiClient,
) : ViewModel() {

    private val _state = MutableStateFlow<BookReaderState>(BookReaderState.Loading(0f))
    val state: StateFlow<BookReaderState> = _state.asStateFlow()

    private var activeOptions: BookPlayOptions? = null
    private var lastRecordedLocator: Locator? = null
    private var progressSyncJob: Job? = null

    fun loadBook(options: BookPlayOptions) {
        activeOptions = options
        viewModelScope.launch {
            _state.value = BookReaderState.Loading(0f)
            try {
                val file = bookRepository.getOrDownloadBookFile(options) { progress ->
                    _state.value = BookReaderState.Loading(progress)
                }

                val publication = bookRepository.openPublication(file)

                // Retrieve saved locator from Room DB if available
                val savedProgress = bookProgressDao.getProgress(options.id)
                val initialLocator = savedProgress?.let {
                    try {
                        Locator.fromJSON(JSONObject(it.locatorJson))
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to parse saved locator JSON")
                        null
                    }
                }

                lastRecordedLocator = initialLocator
                _state.value = BookReaderState.Ready(publication, initialLocator)

                // Report session playback start
                reportPlaybackStart(options, initialLocator)
            } catch (e: Exception) {
                Timber.e(e, "Error loading book: %s", options.name)
                _state.value = BookReaderState.Error(e.localizedMessage ?: "Failed to open book")
            }
        }
    }

    fun onLocatorChanged(locator: Locator) {
        val options = activeOptions ?: return
        lastRecordedLocator = locator

        val progression = locator.locations.totalProgression ?: 0.0

        // Persist precision locator locally
        viewModelScope.launch(Dispatchers.IO) {
            bookProgressDao.saveProgress(
                BookProgressEntity(
                    itemId = options.id,
                    locatorJson = locator.toJSON().toString(),
                    totalProgression = progression,
                ),
            )
        }

        // Debounce reporting to Jellyfin server (every 3 seconds)
        progressSyncJob?.cancel()
        progressSyncJob = viewModelScope.launch(Dispatchers.IO) {
            delay(3000L)
            reportPlaybackProgress(options, locator)
        }
    }

    private suspend fun reportPlaybackStart(options: BookPlayOptions, locator: Locator?) {
        if (options.playFromDownloads) return
        try {
            val progression = locator?.locations?.totalProgression ?: 0.0
            val ticks = (progression * 10_000_000_000L).toLong()

            withContext(Dispatchers.IO) {
                apiClient.playStateApi.reportPlaybackStart(
                    PlaybackStartInfo(
                        itemId = options.id,
                        playMethod = PlayMethod.DIRECT_STREAM,
                        canSeek = true,
                        positionTicks = ticks,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        isPaused = false,
                        isMuted = false,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    ),
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to report playback start to Jellyfin server")
        }
    }

    private suspend fun reportPlaybackProgress(options: BookPlayOptions, locator: Locator) {
        if (options.playFromDownloads) return
        try {
            val progression = locator.locations.totalProgression ?: 0.0
            val ticks = (progression * 10_000_000_000L).toLong()

            withContext(Dispatchers.IO) {
                apiClient.playStateApi.reportPlaybackProgress(
                    PlaybackProgressInfo(
                        itemId = options.id,
                        playMethod = PlayMethod.DIRECT_STREAM,
                        canSeek = true,
                        positionTicks = ticks,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        isPaused = false,
                        isMuted = false,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    ),
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to report playback progress to Jellyfin server")
        }
    }

    fun onReaderClosing() {
        val options = activeOptions ?: return
        val locator = lastRecordedLocator
        val progression = locator?.locations?.totalProgression ?: 0.0
        val ticks = (progression * 10_000_000_000L).toLong()

        // Use global scope because viewModelScope is getting cancelled
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!options.playFromDownloads) {
                    apiClient.playStateApi.reportPlaybackStopped(
                        PlaybackStopInfo(
                            itemId = options.id,
                            positionTicks = ticks,
                            failed = false,
                        ),
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to report playback stop to Jellyfin server")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        onReaderClosing()
    }
}
