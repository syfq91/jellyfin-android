package org.jellyfin.mobile.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.data.dao.ServerDao
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.data.entity.ServerUser
import org.jellyfin.mobile.data.entity.UserEntity
import org.jellyfin.mobile.utils.TokenEncryptionHelper
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.DeviceInfo
import java.util.UUID

class ApiClientController(
    private val appPreferences: AppPreferences,
    private val jellyfin: Jellyfin,
    private val apiClient: ApiClient,
    private val serverDao: ServerDao,
    private val userDao: UserDao,
) {
    private val baseDeviceInfo: DeviceInfo
        get() = jellyfin.options.deviceInfo!!

    /**
     * Store server with [hostname] in the database.
     */
    suspend fun setupServer(hostname: String) {
        appPreferences.currentServerId = withContext(Dispatchers.IO) {
            serverDao.getServerByHostname(hostname)?.id ?: serverDao.insert(hostname)
        }
        apiClient.update(baseUrl = hostname)
    }

    suspend fun setupUser(serverId: Long, userId: UUID, accessToken: String) {
        val encryptedToken = TokenEncryptionHelper.encrypt(accessToken)
        appPreferences.currentUserId = withContext(Dispatchers.IO) {
            userDao.upsert(serverId, userId, encryptedToken)
        }
        configureApiClientUser(userId, accessToken)
    }

    suspend fun loadSavedServer(): ServerEntity? {
        val server = withContext(Dispatchers.IO) {
            val serverId = appPreferences.currentServerId ?: return@withContext null
            serverDao.getServer(serverId)
        }
        configureApiClientServer(server)
        return server
    }

    suspend fun loadSavedUser(): UserEntity? = withContext(Dispatchers.IO) {
        val userId = appPreferences.currentUserId ?: return@withContext null
        val user = userDao.getUser(userId) ?: return@withContext null
        user.copy(accessToken = TokenEncryptionHelper.decrypt(user.accessToken))
    }

    suspend fun loadSavedServerUser(): ServerUser? {
        val serverUser = withContext(Dispatchers.IO) {
            val serverId = appPreferences.currentServerId ?: return@withContext null
            val userId = appPreferences.currentUserId ?: return@withContext null
            userDao.getServerUser(serverId, userId)
        }

        configureApiClientServer(serverUser?.server)

        val decryptedToken = TokenEncryptionHelper.decrypt(serverUser?.user?.accessToken)
        if (decryptedToken != null && serverUser?.user != null) {
            configureApiClientUser(serverUser.user.userId, decryptedToken)
        } else {
            resetApiClientUser()
        }

        return serverUser?.let { it.copy(user = it.user.copy(accessToken = decryptedToken)) }
    }

    suspend fun loadPreviouslyUsedServers(): List<ServerEntity> = withContext(Dispatchers.IO) {
        serverDao.getAllServers().filterNot { server ->
            server.id == appPreferences.currentServerId
        }
    }

    private fun configureApiClientServer(server: ServerEntity?) {
        apiClient.update(baseUrl = server?.hostname)
    }

    private fun configureApiClientUser(userId: UUID, accessToken: String) {
        apiClient.update(
            accessToken = accessToken,
            // Append user id to device id to ensure uniqueness across sessions
            deviceInfo = baseDeviceInfo.copy(id = baseDeviceInfo.id + userId),
        )
    }

    private fun resetApiClientUser() {
        apiClient.update(
            accessToken = null,
            deviceInfo = baseDeviceInfo,
        )
    }

    fun getApiClient(server: Long, user: Long): ApiClient {
        val serverUser = userDao.getServerUser(server, user) ?: error("Invalid server user combination (server=$server, user=$user)")
        val decryptedToken = TokenEncryptionHelper.decrypt(serverUser.user.accessToken)

        return jellyfin.createApi(
            baseUrl = serverUser.server.hostname,
            accessToken = decryptedToken,
            deviceInfo = baseDeviceInfo.copy(id = baseDeviceInfo.id + serverUser.user.userId),
        )
    }
}
