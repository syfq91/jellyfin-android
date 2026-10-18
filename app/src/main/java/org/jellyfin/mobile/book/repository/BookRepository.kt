package org.jellyfin.mobile.book.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.mobile.book.model.BookPlayOptions
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class BookRepository(
    private val context: Context,
    private val apiClient: ApiClient,
    private val okHttpClient: OkHttpClient,
) {
    private val readiumHttpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(
        contentResolver = context.contentResolver,
        httpClient = readiumHttpClient,
    )
    private val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = context,
            httpClient = readiumHttpClient,
            assetRetriever = assetRetriever,
            pdfFactory = PdfiumDocumentFactory(context),
        ),
    )

    private val booksCacheDir = File(context.cacheDir, "books").apply { mkdirs() }

    suspend fun getOrDownloadBookFile(
        options: BookPlayOptions,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        if (options.playFromDownloads && !options.localPath.isNullOrEmpty()) {
            val localFile = File(options.localPath)
            if (localFile.exists() && localFile.length() > 0) {
                return@withContext localFile
            }
        }

        val extension = options.container?.takeIf { it.isNotBlank() } ?: "epub"
        val cachedFile = File(booksCacheDir, "${options.id}.$extension")
        if (cachedFile.exists() && cachedFile.length() > 0) {
            onProgress(1f)
            return@withContext cachedFile
        }

        val baseUrl = apiClient.baseUrl ?: throw IllegalStateException("API client baseUrl is null")
        val downloadUrl = "$baseUrl/Items/${options.id}/File"

        val authHeader = AuthorizationHeaderBuilder.buildHeader(
            clientName = apiClient.clientInfo.name,
            clientVersion = apiClient.clientInfo.version,
            deviceId = apiClient.deviceInfo.id,
            deviceName = apiClient.deviceInfo.name,
            accessToken = apiClient.accessToken,
        )

        val request = Request.Builder()
            .url(downloadUrl)
            .header("Authorization", authHeader)
            .build()

        val tempFile = File(booksCacheDir, "${options.id}.tmp")
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to download book: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty response body")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            onProgress(downloadedBytes.toFloat() / totalBytes.toFloat())
                        }
                    }
                    output.flush()
                }
            }
        }

        if (tempFile.renameTo(cachedFile)) {
            onProgress(1f)
            cachedFile
        } else {
            tempFile
        }
    }

    suspend fun openPublication(file: File): Publication = withContext(Dispatchers.IO) {
        val retrieveResult = assetRetriever.retrieve(file)
        val asset = retrieveResult.getOrNull()
            ?: throw IOException("Failed to retrieve asset from ${file.name}: ${retrieveResult.failureOrNull()}")
        val openResult = publicationOpener.open(asset, allowUserInteraction = false)
        openResult.getOrNull()
            ?: throw IOException("Failed to open publication from ${file.name}: ${openResult.failureOrNull()}")
    }
}
