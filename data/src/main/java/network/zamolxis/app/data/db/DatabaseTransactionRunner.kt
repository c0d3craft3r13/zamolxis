package network.zamolxis.app.data.db

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a block of repository work inside one database transaction.
 *
 * Room's `@Transaction` annotation only does something on a `@Dao` type — put
 * on an ordinary repository class it compiles, reads like a guarantee, and
 * silently does nothing. Repositories that need several writes to land
 * together therefore take this seam instead, which is backed by
 * [androidx.room.withTransaction] in production and by a pass-through in
 * tests.
 */
interface DatabaseTransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/** The production implementation: a real Room transaction. */
@Singleton
class RoomTransactionRunner
    @Inject
    constructor(
        private val database: ZamolxisDatabase,
    ) : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction(block)
    }
