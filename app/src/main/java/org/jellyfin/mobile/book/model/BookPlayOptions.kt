package org.jellyfin.mobile.book.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID

@Parcelize
data class BookPlayOptions(
    val id: UUID,
    val name: String,
    val container: String? = null,
    val startPositionTicks: Long? = null,
    val playFromDownloads: Boolean = false,
    val localPath: String? = null,
) : Parcelable {
    companion object {
        fun fromJson(json: String): BookPlayOptions? = try {
            val jsonObject = Json.parseToJsonElement(json).jsonObject
            val idStr = jsonObject["id"]?.jsonPrimitive?.content ?: jsonObject["Id"]?.jsonPrimitive?.content ?: ""
            val id = idStr.toUUIDOrNull() ?: return null
            val name = jsonObject["name"]?.jsonPrimitive?.content
                ?: jsonObject["Name"]?.jsonPrimitive?.content
                ?: "Book"
            val container = jsonObject["container"]?.jsonPrimitive?.content
                ?: jsonObject["Container"]?.jsonPrimitive?.content
            val ticks = jsonObject["playbackPositionTicks"]?.jsonPrimitive?.longOrNull
                ?: jsonObject["startPositionTicks"]?.jsonPrimitive?.longOrNull
            val fromDownloads = jsonObject["playFromDownloads"]?.jsonPrimitive?.booleanOrNull ?: false
            val localPath = jsonObject["localPath"]?.jsonPrimitive?.content

            BookPlayOptions(
                id = id,
                name = name,
                container = container,
                startPositionTicks = ticks,
                playFromDownloads = fromDownloads,
                localPath = localPath,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse book play options: %s", json)
            null
        }
    }
}
