package network.zamolxis.app.data.db

import android.app.Application
import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v8 → v9 adds `messages.pqStatus` and `peer_pq_keys.fingerprintMismatchTimestamp`.
 *
 * Both are ALTERs on tables holding data a user would notice losing, so the test
 * checks two separate things: that the result matches Room's derived schema, and
 * that existing rows survive with a null in the new column. Null is the intended
 * value for pre-existing messages — they predate the layer, and writing 'NONE'
 * would assert something about them that was never decided.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration8To9SchemaTest {
    private val databaseName = migrationDbPath("pq-status-schema-migration")

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ZamolxisDatabase::class.java,
        )

    @Test
    fun `migration output matches exported Room version 9 schema`() {
        helper.createDatabase(databaseName, 8).close()
        helper
            .runMigrationsAndValidate(
                databaseName,
                9,
                true,
                ZamolxisDatabase.MIGRATION_8_9,
            ).close()
    }

    @Test
    fun `an existing message survives with a null post-quantum status`() {
        val identityHash = "aa11"
        val peerHash = "bb22"

        helper.createDatabase(databaseName, 8).use { db ->
            db.insert(
                "local_identities",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("identityHash", identityHash)
                    put("displayName", "me")
                    put("destinationHash", "cc33")
                    put("filePath", "identities/aa11")
                    put("keyEncryptionVersion", 1)
                    put("createdTimestamp", 1L)
                    put("lastUsedTimestamp", 1L)
                    put("isActive", 1)
                },
            )
            db.insert(
                "conversations",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("peerHash", peerHash)
                    put("identityHash", identityHash)
                    put("peerName", "them")
                    put("lastMessage", "older than the feature")
                    put("lastMessageTimestamp", 10L)
                    put("unreadCount", 0)
                    put("lastSeenTimestamp", 10L)
                },
            )
            db.insert(
                "messages",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("id", "msg-1")
                    put("conversationHash", peerHash)
                    put("identityHash", identityHash)
                    put("content", "older than the feature")
                    put("timestamp", 10L)
                    put("isFromMe", 0)
                    put("status", "delivered")
                    put("isRead", 0)
                },
            )
        }

        helper
            .runMigrationsAndValidate(databaseName, 9, true, ZamolxisDatabase.MIGRATION_8_9)
            .use { db ->
                db.query("SELECT content, pqStatus FROM messages WHERE id = 'msg-1'").use { cursor ->
                    assertTrue("the pre-existing message was lost", cursor.moveToFirst())
                    assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("pqStatus")))
                }
                db.query("SELECT fingerprintMismatchTimestamp FROM peer_pq_keys").use { cursor ->
                    // No rows is fine; the point is that the column exists and is
                    // queryable, which a wrong ALTER would fail on.
                    assertNull(if (cursor.moveToFirst()) "unexpected row" else null)
                }
            }
    }
}
