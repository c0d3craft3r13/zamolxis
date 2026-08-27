package network.zamolxis.app.data.db

import android.app.Application
import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v9 → v10 adds the four group-chat tables (`groups`, `group_members`,
 * `group_messages`, `group_message_status`).
 *
 * The migration is purely additive, so the test checks two things: that the
 * result matches Room's derived v10 schema, and that the new tables accept the
 * shape of rows the feature will write (foreign keys cascade off `groups`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration9To10SchemaTest {
    private val databaseName = migrationDbPath("group-chat-schema-migration")

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ZamolxisDatabase::class.java,
        )

    @Test
    fun `migration output matches exported Room version 10 schema`() {
        helper.createDatabase(databaseName, 9).close()
        helper
            .runMigrationsAndValidate(
                databaseName,
                10,
                true,
                ZamolxisDatabase.MIGRATION_9_10,
            ).close()
    }

    @Test
    fun `group rows can be written after migration and cascade on group delete`() {
        val groupId = "0123456789abcdef0123456789abcdef"

        helper.createDatabase(databaseName, 9).close()

        helper
            .runMigrationsAndValidate(databaseName, 10, true, ZamolxisDatabase.MIGRATION_9_10)
            .use { db ->
                db.execSQL("PRAGMA foreign_keys=ON")
                db.insert(
                    "groups",
                    android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                    ContentValues().apply {
                        put("groupId", groupId)
                        put("identityHash", "aa11")
                        put("name", "test group")
                        put("createdBy", "bb22")
                        put("createdAt", 10L)
                    },
                )
                db.insert(
                    "group_members",
                    android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                    ContentValues().apply {
                        put("groupId", groupId)
                        put("memberHash", "bb22")
                        put("role", "ADMIN")
                        put("addedAt", 10L)
                    },
                )
                db.insert(
                    "group_messages",
                    android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                    ContentValues().apply {
                        put("groupId", groupId)
                        put("msgId", "msg-1")
                        put("senderHash", "bb22")
                        put("content", "hello group")
                        put("timestamp", 11L)
                        put("receivedAt", 12L)
                        put("isFromMe", 0)
                    },
                )
                db.insert(
                    "group_message_status",
                    android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                    ContentValues().apply {
                        put("msgId", "msg-1")
                        put("memberHash", "cc33")
                        put("status", "PENDING")
                        put("lxmfHash", "dd44")
                    },
                )

                // unreadCount defaults to 0 when the writer does not set it.
                db.query("SELECT unreadCount FROM `groups` WHERE groupId = '$groupId'").use { cursor ->
                    assertTrue("the inserted group is missing", cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("unreadCount")))
                }

                // Members and messages hang off the group by CASCADE.
                db.delete("groups", "groupId = ?", arrayOf(groupId))
                db.query("SELECT COUNT(*) FROM group_members WHERE groupId = '$groupId'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                db.query("SELECT COUNT(*) FROM group_messages WHERE groupId = '$groupId'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
    }
}
