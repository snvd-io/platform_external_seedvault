/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import com.stevesoltys.seedvault.proto.Snapshot

sealed class CheckerResult {
    data class Success(
        val snapshots: List<Snapshot>,
        val percent: Int,
        val size: Long,
    ) : CheckerResult()

    data class Error(
        /**
         * This number is greater than the size of [snapshots],
         * if we could not read/decrypt one or more snapshots.
         */
        val existingSnapshots: Int,
        val snapshots: List<Snapshot>,
        /**
         * The list of chunkIDs that had errors.
         */
        val errorChunkIds: Set<String>,
    ) : CheckerResult() {
        val goodSnapshots: List<Snapshot>
        val badSnapshots: List<Snapshot>

        init {
            val good = mutableListOf<Snapshot>()
            val bad = mutableListOf<Snapshot>()
            snapshots.forEach { snapshot ->
                val isGood = snapshot.blobsMap.keys.intersect(errorChunkIds).isEmpty()
                if (isGood) good.add(snapshot) else bad.add(snapshot)
            }
            goodSnapshots = good
            badSnapshots = bad
        }
    }

    data class GeneralError(val e: Exception) : CheckerResult()
}
