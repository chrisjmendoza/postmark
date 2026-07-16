package com.plusorminustwo.postmark.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_NONE
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests for PostmarkDatabase — every migration in the chain has at least one
 * test, plus a full 1→15 chain test. Schemas 1.json–3.json were regenerated July 2026
 * by building the historical commits at those versions, so `helper.createDatabase(n)`
 * works for every version.
 *
 * Most tests use the direct-migrate pattern (createDatabase(n) → seed → migrate() →
 * query) rather than runMigrationsAndValidate: the migrations add columns with SQL
 * DEFAULT clauses that the entities deliberately don't declare via @ColumnInfo, and
 * strict schema validation flags that mismatch even though it is harmless at runtime.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PostmarkDatabase::class.java
    )

    // ── MIGRATION 1 → 2 ───────────────────────────────────────────────────

    @Test
    fun migration1To2_addsLastMessagePreviewWithEmptyDefault() {
        val db = helper.createDatabase("test_m12a", 1)
        db.apply {
            execSQL(
                "INSERT INTO threads (id, displayName, address, lastMessageAt, backupPolicy)" +
                " VALUES (1, 'Alice', '+1555', 1000000, 'GLOBAL')"
            )
        }

        PostmarkDatabase.MIGRATION_1_2.migrate(db)
        db.query("SELECT lastMessagePreview FROM threads WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("", c.getString(0))
        }
        db.close()
    }

    @Test
    fun migration1To2_preservesExistingThreadData() {
        val db = helper.createDatabase("test_m12b", 1)
        db.apply {
            execSQL(
                "INSERT INTO threads (id, displayName, address, lastMessageAt, backupPolicy)" +
                " VALUES (1, 'Bob', '+1999', 2000000, 'GLOBAL')"
            )
        }

        PostmarkDatabase.MIGRATION_1_2.migrate(db)
        db.query("SELECT displayName, address, lastMessageAt FROM threads WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Bob", c.getString(0))
            assertEquals("+1999", c.getString(1))
            assertEquals(2_000_000L, c.getLong(2))
        }
        db.close()
    }

    @Test
    fun migration1To2_preservesExistingMessages() {
        val db = helper.createDatabase("test_m12c", 1)
        db.apply {
            execSQL(
                "INSERT INTO threads (id, displayName, address, lastMessageAt, backupPolicy)" +
                " VALUES (1, 'Alice', '+1', 1000000, 'GLOBAL')"
            )
            execSQL(
                "INSERT INTO messages (id, threadId, address, body, timestamp, isSent, type)" +
                " VALUES (42, 1, '+1', 'hello migration', 1000000, 1, 2)"
            )
        }

        PostmarkDatabase.MIGRATION_1_2.migrate(db)
        db.query("SELECT body FROM messages WHERE id = 42").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("hello migration", c.getString(0))
        }
        db.close()
    }

    // ── MIGRATION 2 → 3 ───────────────────────────────────────────────────
    // Applies migration SQL to a v2 SupportSQLiteDatabase and verifies the
    // deliveryStatus column appears with default value 0.

    @Test
    fun migration2To3_addsDeliveryStatusColumnWithDefaultZero() {
        val db2 = helper.createDatabase("test_m23a", 2)
        db2.execSQL(
            "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy)" +
            " VALUES (1, 'Alice', '+1', 1000000, '', 'GLOBAL')"
        )
        db2.execSQL(
            "INSERT INTO messages (id, threadId, address, body, timestamp, isSent, type)" +
            " VALUES (1, 1, '+1', 'hello', 1000000, 1, 2)"
        )

        PostmarkDatabase.MIGRATION_2_3.migrate(db2)

        db2.query("SELECT deliveryStatus FROM messages WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(DELIVERY_STATUS_NONE, c.getInt(0))
        }
        db2.close()
    }

    @Test
    fun migration2To3_allExistingMessagesGetDefaultStatus() {
        val db2 = helper.createDatabase("test_m23b", 2)
        db2.execSQL(
            "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy)" +
            " VALUES (1, 'Test', '+1', 1000000, '', 'GLOBAL')"
        )
        for (i in 1..5) {
            db2.execSQL(
                "INSERT INTO messages (id, threadId, address, body, timestamp, isSent, type)" +
                " VALUES ($i, 1, '+1', 'msg$i', ${i * 1000L}, 0, 1)"
            )
        }

        PostmarkDatabase.MIGRATION_2_3.migrate(db2)

        db2.query("SELECT deliveryStatus FROM messages ORDER BY id").use { c ->
            var count = 0
            while (c.moveToNext()) {
                assertEquals("Existing message $count should default to 0", 0, c.getInt(0))
                count++
            }
            assertEquals(5, count)
        }
        db2.close()
    }

    @Test
    fun migration2To3_doesNotAffectOtherColumns() {
        val db2 = helper.createDatabase("test_m23c", 2)
        db2.execSQL(
            "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy)" +
            " VALUES (1, 'Alice', '+1', 1000000, '', 'GLOBAL')"
        )
        db2.execSQL(
            "INSERT INTO messages (id, threadId, address, body, timestamp, isSent, type)" +
            " VALUES (99, 1, '+1', 'content check', 9999999, 1, 2)"
        )

        PostmarkDatabase.MIGRATION_2_3.migrate(db2)

        db2.query("SELECT id, body, timestamp, isSent FROM messages WHERE id = 99").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(99L, c.getLong(0))
            assertEquals("content check", c.getString(1))
            assertEquals(9999999L, c.getLong(2))
            assertEquals(1, c.getInt(3))
        }
        db2.close()
    }

    // ── MIGRATION 4 → 5 ───────────────────────────────────────────────────
    // Adds isMuted to threads and topReactionEmojisJson to thread_stats + global_stats.
    // Uses direct SQL approach (same pattern as 2→3) since 5.json is not yet generated.

    @Test
    fun migration4To5_addsMutedColumnWithDefaultFalse() {
        val db4 = helper.createDatabase("test_m45a", 4)
        db4.execSQL(
            "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy)" +
            " VALUES (1, 'Alice', '+1', 1000000, '', 'GLOBAL')"
        )

        PostmarkDatabase.MIGRATION_4_5.migrate(db4)

        db4.query("SELECT isMuted FROM threads WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db4.close()
    }

    @Test
    fun migration4To5_preservesExistingThreadData() {
        val db4 = helper.createDatabase("test_m45b", 4)
        db4.execSQL(
            "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy)" +
            " VALUES (99, 'Bob', '+2', 2000000, 'hi', 'ALWAYS_INCLUDE')"
        )

        PostmarkDatabase.MIGRATION_4_5.migrate(db4)

        db4.query("SELECT displayName, address, backupPolicy FROM threads WHERE id = 99").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Bob", c.getString(0))
            assertEquals("+2", c.getString(1))
            assertEquals("ALWAYS_INCLUDE", c.getString(2))
        }
        db4.close()
    }

    // ── MIGRATION 11 → 12 ─────────────────────────────────────────────────
    // Adds the nullable attachmentsJson column (multi-attachment MMS). Uses the
    // direct-SQL approach (same pattern as 2→3) since 12.json is generated only
    // after the first successful KSP build at version 12.

    @Test
    fun migration11To12_addsNullAttachmentsJsonAndPreservesSingularColumns() {
        val db11 = helper.createDatabase("test_m1112a", 11)
        // Room-created tables carry no SQL defaults, so every NOT NULL column at v11
        // must be supplied explicitly (isMuted/isPinned/notificationsEnabled on threads;
        // deliveryStatus/isRead on messages).
        db11.execSQL(
            "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy, isMuted, isPinned, notificationsEnabled)" +
            " VALUES (1, 'Alice', '+1', 1000000, '', 'GLOBAL', 0, 0, 1)"
        )
        db11.execSQL(
            "INSERT INTO messages (id, threadId, address, body, timestamp, isSent, type, deliveryStatus, isMms, isRead, attachmentUri, mimeType)" +
            " VALUES (7, 1, '+1', '', 1000000, 0, 1, 0, 1, 1, 'content://mms/part/99', 'image/jpeg')"
        )

        PostmarkDatabase.MIGRATION_11_12.migrate(db11)

        db11.query("SELECT attachmentsJson, attachmentUri, mimeType FROM messages WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            // New column defaults to NULL — pre-v12 rows fall back to the singular pair.
            assertTrue(c.isNull(0))
            assertEquals("content://mms/part/99", c.getString(1))
            assertEquals("image/jpeg", c.getString(2))
        }
        db11.close()
    }

    @Test
    fun migration11To12_acceptsAttachmentsJsonWrites() {
        val db11 = helper.createDatabase("test_m1112b", 11)
        db11.execSQL(
            "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy, isMuted, isPinned, notificationsEnabled)" +
            " VALUES (1, 'Alice', '+1', 1000000, '', 'GLOBAL', 0, 0, 1)"
        )

        PostmarkDatabase.MIGRATION_11_12.migrate(db11)

        val json = """[{"uri":"content://mms/part/1","mimeType":"image/jpeg"},{"uri":"content://mms/part/2","mimeType":"video/mp4"}]"""
        db11.execSQL(
            "INSERT INTO messages (id, threadId, address, body, timestamp, isSent, type, deliveryStatus, isMms, isRead, attachmentUri, mimeType, attachmentsJson)" +
            " VALUES (8, 1, '+1', '', 2000000, 0, 1, 0, 1, 1, 'content://mms/part/1', 'image/jpeg', '$json')"
        )
        db11.query("SELECT attachmentsJson FROM messages WHERE id = 8").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(json, c.getString(0))
        }
        db11.close()
    }

    @Test
    fun migration4To5_addsTopReactionEmojisJsonToThreadStats() {
        val db4 = helper.createDatabase("test_m45c", 4)
        db4.execSQL(
            "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy)" +
            " VALUES (1, 'Test', '+1', 1000000, '', 'GLOBAL')"
        )
        db4.execSQL(
            "INSERT INTO thread_stats (threadId, totalMessages, sentCount, receivedCount, firstMessageAt, " +
            "lastMessageAt, activeDayCount, longestStreakDays, avgResponseTimeMs, topEmojisJson, " +
            "byDayOfWeekJson, byMonthJson, lastUpdatedAt) VALUES (1, 5, 2, 3, 0, 1000000, 1, 1, 0, '[]', '{}', '{}', 0)"
        )

        PostmarkDatabase.MIGRATION_4_5.migrate(db4)

        db4.query("SELECT topReactionEmojisJson FROM thread_stats WHERE threadId = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("[]", c.getString(0))
        }
        db4.close()
    }

    // ── MIGRATIONS 3→4 through 13→14 ──────────────────────────────────────
    // Direct-migrate pattern (same as 2→3 / 4→5): create the versioned schema from
    // its committed JSON, seed rows naming every NOT NULL column at that version
    // (Room-created tables carry no SQL defaults), apply the migration, verify the
    // new column's default and that existing data survived.

    @Test
    fun migration3To4_createsGlobalStatsTable() {
        val db3 = helper.createDatabase("test_m34", 3)

        PostmarkDatabase.MIGRATION_3_4.migrate(db3)

        db3.query("SELECT name FROM sqlite_master WHERE type='table' AND name='global_stats'").use { c ->
            assertTrue(c.moveToFirst())
        }
        // Single-row table is writable with just the id — every column has a default.
        db3.execSQL("INSERT INTO global_stats (id) VALUES (1)")
        db3.query("SELECT totalMessages FROM global_stats WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db3.close()
    }

    @Test
    fun migration5To6_addsIsPinnedWithDefaultFalse() {
        val db5 = helper.createDatabase("test_m56", 5)
        db5.execSQL(
            "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy, isMuted)" +
            " VALUES (1, 'Alice', '+1', 1000000, '', 'GLOBAL', 1)"
        )

        PostmarkDatabase.MIGRATION_5_6.migrate(db5)

        db5.query("SELECT isPinned, isMuted FROM threads WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            assertEquals(1, c.getInt(1))
        }
        db5.close()
    }

    @Test
    fun migration6To7_addsIsMmsWithDefaultFalse() {
        val db6 = helper.createDatabase("test_m67", 6)
        db6.execSQL(
            "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy, isMuted, isPinned)" +
            " VALUES (1, 'Alice', '+1', 1000000, '', 'GLOBAL', 0, 0)"
        )
        db6.execSQL(
            "INSERT INTO messages (id, threadId, address, body, timestamp, isSent, type, deliveryStatus)" +
            " VALUES (5, 1, '+1', 'plain sms', 1000000, 1, 2, 0)"
        )

        PostmarkDatabase.MIGRATION_6_7.migrate(db6)

        db6.query("SELECT isMms, body FROM messages WHERE id = 5").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            assertEquals("plain sms", c.getString(1))
        }
        db6.close()
    }

    @Test
    fun migration7To8_addsNotificationsEnabledWithDefaultTrue() {
        val db7 = helper.createDatabase("test_m78", 7)
        db7.execSQL(
            "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy, isMuted, isPinned)" +
            " VALUES (1, 'Alice', '+1', 1000000, '', 'GLOBAL', 0, 0)"
        )

        PostmarkDatabase.MIGRATION_7_8.migrate(db7)

        db7.query("SELECT notificationsEnabled FROM threads WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db7.close()
    }

    @Test
    fun migration8To9_addsNullableAttachmentColumns() {
        val db8 = helper.createDatabase("test_m89", 8)
        db8.execSQL(
            "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy, isMuted, isPinned, notificationsEnabled)" +
            " VALUES (1, 'Alice', '+1', 1000000, '', 'GLOBAL', 0, 0, 1)"
        )
        db8.execSQL(
            "INSERT INTO messages (id, threadId, address, body, timestamp, isSent, type, deliveryStatus, isMms)" +
            " VALUES (5, 1, '+1', 'sms', 1000000, 1, 2, 0, 0)"
        )

        PostmarkDatabase.MIGRATION_8_9.migrate(db8)

        db8.query("SELECT attachmentUri, mimeType FROM messages WHERE id = 5").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertTrue(c.isNull(1))
        }
        db8.close()
    }

    @Test
    fun migration9To10_addsIsReadWithDefaultTrue() {
        val db9 = helper.createDatabase("test_m910", 9)
        db9.execSQL(
            "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy, isMuted, isPinned, notificationsEnabled)" +
            " VALUES (1, 'Alice', '+1', 1000000, '', 'GLOBAL', 0, 0, 1)"
        )
        db9.execSQL(
            "INSERT INTO messages (id, threadId, address, body, timestamp, isSent, type, deliveryStatus, isMms)" +
            " VALUES (5, 1, '+1', 'sms', 1000000, 0, 1, 0, 0)"
        )

        PostmarkDatabase.MIGRATION_9_10.migrate(db9)

        // Existing synced rows are treated as already read — no unread-badge flood.
        db9.query("SELECT isRead FROM messages WHERE id = 5").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db9.close()
    }

    @Test
    fun migration10To11_addsNullableNickname() {
        val db10 = helper.createDatabase("test_m1011", 10)
        db10.execSQL(
            "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy, isMuted, isPinned, notificationsEnabled)" +
            " VALUES (1, 'Alice', '+1', 1000000, '', 'GLOBAL', 0, 0, 1)"
        )

        PostmarkDatabase.MIGRATION_10_11.migrate(db10)

        db10.query("SELECT nickname FROM threads WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
        }
        db10.execSQL("UPDATE threads SET nickname = 'Ally' WHERE id = 1")
        db10.query("SELECT nickname FROM threads WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Ally", c.getString(0))
        }
        db10.close()
    }

    @Test
    fun migration12To13_addsNullableParticipantsJson() {
        val db12 = helper.createDatabase("test_m1213", 12)
        db12.execSQL(
            "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy, isMuted, isPinned, notificationsEnabled)" +
            " VALUES (1, 'Group', '+1', 1000000, '', 'GLOBAL', 0, 0, 1)"
        )

        PostmarkDatabase.MIGRATION_12_13.migrate(db12)

        // Ordinary 1:1 threads keep NULL; only group MMS sync populates the roster.
        db12.query("SELECT participantsJson FROM threads WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
        }
        db12.close()
    }

    @Test
    fun migration13To14_addsIsStarredWithDefaultFalse() {
        val db13 = helper.createDatabase("test_m1314", 13)
        db13.execSQL(
            "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy, isMuted, isPinned, notificationsEnabled)" +
            " VALUES (1, 'Alice', '+1', 1000000, '', 'GLOBAL', 0, 0, 1)"
        )
        db13.execSQL(
            "INSERT INTO messages (id, threadId, address, body, timestamp, isSent, type, deliveryStatus, isMms, isRead)" +
            " VALUES (5, 1, '+1', 'sms', 1000000, 0, 1, 0, 0, 1)"
        )

        PostmarkDatabase.MIGRATION_13_14.migrate(db13)

        db13.query("SELECT isStarred FROM messages WHERE id = 5").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db13.close()
    }

    // ── Full chain 1 → 16 ─────────────────────────────────────────────────

    @Test
    fun fullMigrationChain_v1DataSurvivesToV16() {
        val db = helper.createDatabase("test_chain", 1)
        db.execSQL(
            "INSERT INTO threads (id, displayName, address, lastMessageAt, backupPolicy)" +
            " VALUES (1, 'Alice', '+1555', 1000000, 'GLOBAL')"
        )
        db.execSQL(
            "INSERT INTO messages (id, threadId, address, body, timestamp, isSent, type)" +
            " VALUES (42, 1, '+1555', 'oldest message', 1000000, 1, 2)"
        )

        listOf(
            PostmarkDatabase.MIGRATION_1_2, PostmarkDatabase.MIGRATION_2_3,
            PostmarkDatabase.MIGRATION_3_4, PostmarkDatabase.MIGRATION_4_5,
            PostmarkDatabase.MIGRATION_5_6, PostmarkDatabase.MIGRATION_6_7,
            PostmarkDatabase.MIGRATION_7_8, PostmarkDatabase.MIGRATION_8_9,
            PostmarkDatabase.MIGRATION_9_10, PostmarkDatabase.MIGRATION_10_11,
            PostmarkDatabase.MIGRATION_11_12, PostmarkDatabase.MIGRATION_12_13,
            PostmarkDatabase.MIGRATION_13_14, PostmarkDatabase.MIGRATION_14_15,
            PostmarkDatabase.MIGRATION_15_16
        ).forEach { it.migrate(db) }

        db.query(
            "SELECT m.body, m.deliveryStatus, m.isMms, m.isRead, m.isStarred, t.isPinned, t.notificationsEnabled" +
            " FROM messages m JOIN threads t ON t.id = m.threadId WHERE m.id = 42"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("oldest message", c.getString(0))
            assertEquals(0, c.getInt(1))  // deliveryStatus NONE
            assertEquals(0, c.getInt(2))  // not MMS
            assertEquals(1, c.getInt(3))  // read
            assertEquals(0, c.getInt(4))  // not starred
            assertEquals(0, c.getInt(5))  // not pinned
            assertEquals(1, c.getInt(6))  // notifications on
        }
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('thread_stats','global_stats')").use { c ->
            assertFalse(c.moveToFirst())
        }
        db.close()
    }

    // ── MIGRATION 14 → 15 ─────────────────────────────────────────────────

    @Test
    fun migration14To15_dropsStatsTablesAndPreservesUserData() {
        helper.createDatabase("test_m1415", 14).apply {
            execSQL(
                "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy)" +
                " VALUES (1, 'Alice', '+1', 1000000, '', 'GLOBAL')"
            )
            execSQL(
                "INSERT INTO messages (id, threadId, address, body, timestamp, isSent, type)" +
                " VALUES (42, 1, '+1', 'survives the drop', 1000000, 1, 2)"
            )
            execSQL(
                "INSERT INTO thread_stats (threadId, totalMessages, sentCount, receivedCount, firstMessageAt, " +
                "lastMessageAt, activeDayCount, longestStreakDays, avgResponseTimeMs, topEmojisJson, " +
                "topReactionEmojisJson, byDayOfWeekJson, byMonthJson, lastUpdatedAt)" +
                " VALUES (1, 5, 2, 3, 0, 1000000, 1, 1, 0, '[]', '[]', '{}', '{}', 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate("test_m1415", 15, true, PostmarkDatabase.MIGRATION_14_15)
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('thread_stats','global_stats')").use { c ->
            assertFalse(c.moveToFirst())
        }
        db.query("SELECT body FROM messages WHERE id = 42").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("survives the drop", c.getString(0))
        }
    }

    // ── MIGRATION 15 → 16 ─────────────────────────────────────────────────

    @Test
    fun migration15To16_replacesThreadIdIndexWithPerfIndexes() {
        helper.createDatabase("test_m1516", 15).apply {
            execSQL(
                "INSERT INTO threads (id, displayName, address, lastMessageAt, lastMessagePreview, backupPolicy)" +
                " VALUES (1, 'Alice', '+1', 1000000, '', 'GLOBAL')"
            )
            execSQL(
                "INSERT INTO messages (id, threadId, address, body, timestamp, isSent, type)" +
                " VALUES (42, 1, '+1', 'indexed message', 1000000, 1, 2)"
            )
            close()
        }

        // runMigrationsAndValidate checks the live schema (including the full index set)
        // against the exported 16.json, so a drift between the entity annotations and
        // the migration DDL fails here rather than at runtime on a user's device.
        val db = helper.runMigrationsAndValidate("test_m1516", 16, true, PostmarkDatabase.MIGRATION_15_16)
        val indexNames = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='messages'").use { c ->
            while (c.moveToNext()) indexNames += c.getString(0)
        }
        assertTrue("composite (threadId, timestamp) index missing",
            "index_messages_threadId_timestamp" in indexNames)
        assertTrue("(isRead, threadId) index missing", "index_messages_isRead_threadId" in indexNames)
        assertTrue("isMms index missing", "index_messages_isMms" in indexNames)
        assertTrue("isStarred index missing", "index_messages_isStarred" in indexNames)
        assertFalse("superseded plain threadId index should be dropped",
            "index_messages_threadId" in indexNames)

        db.query("SELECT body FROM messages WHERE id = 42").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("indexed message", c.getString(0))
        }
    }
}
