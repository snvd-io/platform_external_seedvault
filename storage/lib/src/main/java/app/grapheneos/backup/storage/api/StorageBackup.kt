/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package app.grapheneos.backup.storage.api

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract.isTreeUri
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Settings.Secure.ANDROID_ID
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.room.Room
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import app.grapheneos.backup.storage.SnapshotRetriever
import app.grapheneos.backup.storage.backup.Backup
import app.grapheneos.backup.storage.backup.BackupSnapshot
import app.grapheneos.backup.storage.backup.ChunksCacheRepopulater
import app.grapheneos.backup.storage.db.Db
import app.grapheneos.backup.storage.getCurrentBackupSnapshots
import app.grapheneos.backup.storage.getMediaType
import app.grapheneos.backup.storage.prune.Pruner
import app.grapheneos.backup.storage.prune.RetentionManager
import app.grapheneos.backup.storage.restore.FileRestore
import app.grapheneos.backup.storage.restore.Restore
import app.grapheneos.backup.storage.scanner.DocumentScanner
import app.grapheneos.backup.storage.scanner.FileScanner
import app.grapheneos.backup.storage.scanner.MediaScanner
import app.grapheneos.backup.storage.toStoredUri
import app.grapheneos.seedvault.core.backends.Backend
import app.grapheneos.seedvault.core.backends.FileBackupFileType
import app.grapheneos.seedvault.core.backends.saf.getDocumentPath
import app.grapheneos.seedvault.core.crypto.KeyManager
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "StorageBackup"

public class StorageBackup(
    private val context: Context,
    private val pluginGetter: () -> Backend,
    private val keyManager: KeyManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val db: Db by lazy {
        Room.databaseBuilder(context, Db::class.java, "seedvault-storage-local-cache")
            .build()
    }
    private val uriStore by lazy { db.getUriStore() }

    @SuppressLint("HardwareIds")
    private val androidId = Settings.Secure.getString(context.contentResolver, ANDROID_ID)

    private val mediaScanner by lazy { MediaScanner(context) }
    private val snapshotRetriever = SnapshotRetriever(pluginGetter)
    private val chunksCacheRepopulater = ChunksCacheRepopulater(
        db = db,
        storagePlugin = pluginGetter,
        androidId = androidId,
        snapshotRetriever = snapshotRetriever,
    )
    private val backup by lazy {
        val documentScanner = DocumentScanner(context)
        val fileScanner = FileScanner(uriStore, mediaScanner, documentScanner)
        Backup(
            context = context,
            db = db,
            fileScanner = fileScanner,
            backendGetter = pluginGetter,
            androidId = androidId,
            keyManager = keyManager,
            cacheRepopulater = chunksCacheRepopulater
        )
    }
    private val restore by lazy {
        val fileRestore = FileRestore(context, mediaScanner)
        Restore(context, pluginGetter, keyManager, snapshotRetriever, fileRestore)
    }
    private val retention = RetentionManager(context)
    private val pruner by lazy {
        Pruner(db, retention, pluginGetter, androidId, keyManager, snapshotRetriever)
    }

    private val backupRunning = AtomicBoolean(false)
    private val restoreRunning = AtomicBoolean(false)

    public val uris: Set<Uri>
        @WorkerThread
        get() {
            return uriStore.getStoredUris().map { it.uri }.toSet()
        }

    @Throws(IllegalArgumentException::class)
    public suspend fun addUri(uri: Uri): Unit = withContext(dispatcher) {
        if (uri.authority == MediaStore.AUTHORITY) {
            if (uri !in mediaUris) throw IllegalArgumentException("Not a supported MediaStore URI")
        } else if (uri.authority == EXTERNAL_STORAGE_PROVIDER_AUTHORITY) {
            if (!isTreeUri(uri)) throw IllegalArgumentException("Not a tree URI")
        } else {
            throw IllegalArgumentException()
        }
        Log.e(TAG, "Adding URI $uri")
        uriStore.addStoredUri(uri.toStoredUri())
    }

    public suspend fun removeUri(uri: Uri): Unit = withContext(dispatcher) {
        Log.e(TAG, "Removing URI $uri")
        uriStore.removeStoredUri(uri.toStoredUri())
    }

    public suspend fun getUriSummaryString(): String = withContext(dispatcher) {
        val uris = uris.sortedDescending()
        val list = ArrayList<String>()
        for (uri in uris) {
            val nameRes = uri.getMediaType()?.nameRes
            if (nameRes == null) {
                uri.getDocumentPath()?.let { list.add(it) }
            } else {
                list.add(context.getString(nameRes))
            }
        }
        list.joinToString(", ", limit = 5)
    }

    /**
     * Ensures the storage is set-up to receive backups and deletes all snapshots
     * (see [deleteAllSnapshots]) as well as clears local cache (see [clearCache]).
     */
    public suspend fun init() {
        deleteAllSnapshots()
        clearCache()
    }

    /**
     * Run this on a new storage location to ensure that there are no old snapshots
     * (potentially encrypted with an old key) laying around.
     * Using a storage location with existing data is not supported.
     * Using the same root folder for storage on different devices or user profiles is fine though
     * as the [Backend] should isolate storage per [StoredSnapshot.userId].
     */
    public suspend fun deleteAllSnapshots(): Unit = withContext(dispatcher) {
        try {
            pluginGetter().getCurrentBackupSnapshots(androidId).forEach {
                val handle = FileBackupFileType.Snapshot(androidId, it.timestamp)
                try {
                    pluginGetter().remove(handle)
                } catch (e: IOException) {
                    Log.e(TAG, "Error deleting snapshot $it", e)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error deleting all snapshots", e)
        }
    }

    /**
     * It is advised to clear existing cache when selecting a new storage location.
     */
    public suspend fun clearCache(): Unit = withContext(dispatcher) {
        db.getChunksCache().clear()
        db.getFilesCache().clear()
    }

    public suspend fun runBackup(backupObserver: BackupObserver?): Boolean =
        withContext(dispatcher) {
            if (backupRunning.getAndSet(true)) {
                Log.w(TAG, "Backup already running, not starting a new one")
                return@withContext false
            }
            try {
                backup.runBackup(backupObserver)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error during backup", e)
                false
            } finally {
                backupRunning.set(false)
            }
        }

    /**
     * Sets how many backup snapshots to keep in storage when running [pruneOldBackups].
     *
     * @throws IllegalArgumentException if all retention values are set to 0.
     */
    public fun setSnapshotRetention(snapshotRetention: SnapshotRetention) {
        retention.setSnapshotRetention(snapshotRetention)
    }

    /**
     * Gets the current snapshot retention policy.
     */
    @WorkerThread
    public fun getSnapshotRetention(): SnapshotRetention = retention.getSnapshotRetention()

    /**
     * Prunes old backup snapshots according to the parameters set via [setSnapshotRetention].
     * This will delete backed up data. Use with care!
     *
     * Run this only after [runBackup] returns true to ensure
     * that no chunks from partial backups get removed and need to be re-uploaded.
     */
    public suspend fun pruneOldBackups(backupObserver: BackupObserver?): Boolean =
        withContext(dispatcher) {
            backupObserver?.onPruneStartScanning()
            try {
                pruner.prune(backupObserver)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error during pruning backups", e)
                backupObserver?.onPruneError(null, e)
                false
            }
        }

    public fun getBackupSnapshots(): Flow<SnapshotResult> {
        return restore.getBackupSnapshots()
    }

    public suspend fun restoreBackupSnapshot(
        storedSnapshot: StoredSnapshot,
        snapshot: BackupSnapshot,
        restoreObserver: RestoreObserver? = null,
    ): Boolean = withContext(dispatcher) {
        if (restoreRunning.getAndSet(true)) {
            Log.w(TAG, "Restore already running, not starting a new one")
            return@withContext false
        }
        try {
            restore.restoreBackupSnapshot(storedSnapshot, snapshot, restoreObserver)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error during restore", e)
            false
        } finally {
            restoreRunning.set(false)
        }
    }

}