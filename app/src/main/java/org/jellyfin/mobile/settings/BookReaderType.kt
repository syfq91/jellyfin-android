package org.jellyfin.mobile.settings

import androidx.annotation.StringDef

@Retention(AnnotationRetention.SOURCE)
@StringDef(
    BookReaderType.READIUM,
    BookReaderType.WEB_READER,
)
annotation class BookReaderType {
    companion object {
        const val READIUM = "readium"
        const val WEB_READER = "webui"
    }
}
