/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package de.grobox.storagebackuptester

import android.content.Context
import app.grapheneos.backup.storage.api.BackupObserver
import app.grapheneos.backup.storage.api.RestoreObserver
import app.grapheneos.backup.storage.api.StorageBackup
import app.grapheneos.backup.storage.backup.BackupJobService
import app.grapheneos.backup.storage.backup.BackupService
import app.grapheneos.backup.storage.backup.NotificationBackupObserver
import app.grapheneos.backup.storage.restore.NotificationRestoreObserver
import app.grapheneos.backup.storage.restore.RestoreService
import app.grapheneos.backup.storage.ui.restore.FileSelectionManager
import java.util.concurrent.TimeUnit.HOURS

// debug with:
// adb shell dumpsys jobscheduler | grep -B 4 -A 24 "Service: de.grobox.storagebackuptester/.DemoBackupJobService"

class DemoBackupJobService : BackupJobService(DemoBackupService::class.java) {
    companion object {
        fun scheduleJob(context: Context) {
            scheduleJob(
                context = context,
                jobServiceClass = DemoBackupJobService::class.java,
//                periodMillis = JobInfo.getMinPeriodMillis(), // for testing
                periodMillis = HOURS.toMillis(12), // less than 15min won't work
                deviceIdle = false,
                charging = false,
            )
        }
    }
}

class DemoBackupService : BackupService() {
    // use lazy delegate because context isn't available during construction time
    override val storageBackup: StorageBackup by lazy { (application as App).storageBackup }
    override val backupObserver: BackupObserver by lazy {
        NotificationBackupObserver(applicationContext)
    }
}

class DemoRestoreService : RestoreService() {
    // use lazy delegate because context isn't available during construction time
    override val storageBackup: StorageBackup by lazy { (application as App).storageBackup }
    override val fileSelectionManager: FileSelectionManager
        get() = (application as App).fileSelectionManager
    override val restoreObserver: RestoreObserver by lazy {
        NotificationRestoreObserver(applicationContext)
    }
}
