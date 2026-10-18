package org.jellyfin.mobile.bridge

import android.webkit.JavascriptInterface
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.book.model.BookPlayOptions
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.settings.BookReaderType

@Suppress("unused")
class NativeBookPlayer(
    private val appPreferences: AppPreferences,
    private val activityEventHandler: ActivityEventHandler,
) {
    @JavascriptInterface
    fun isEnabled(): Boolean = appPreferences.bookReaderType == BookReaderType.READIUM

    @JavascriptInterface
    fun openBook(optionsRaw: String) {
        val options = BookPlayOptions.fromJson(optionsRaw) ?: return
        activityEventHandler.emit(ActivityEvent.LaunchNativeBookReader(options))
    }

    @JavascriptInterface
    fun closeBook() {
        activityEventHandler.emit(ActivityEvent.CloseNativeBookReader)
    }
}
