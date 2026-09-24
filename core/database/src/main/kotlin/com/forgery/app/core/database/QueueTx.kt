package com.forgery.app.core.database

import androidx.room.withTransaction
import javax.inject.Inject

/** Test-seam over Room transactions: serializes all queue_state read-modify-write blocks. */
interface QueueTx {
    suspend fun <T> run(block: suspend () -> T): T
}

class RoomQueueTx @Inject constructor(
    private val db: ForgeryDatabase,
) : QueueTx {
    override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction(block)
}
