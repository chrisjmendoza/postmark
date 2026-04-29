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
 * Migration tests for PostmarkDatabase.
 *
 * MIGRATION_1_2: uses full MigrationTestHelper schema validation (1.json + 2.json both exist).
 * MIGRATION_2_3: applies migration SQL directly to a v2 SupportSQLiteDatabase and verifies
 *   the new column, because 3.json is generated only after the first successful KSP build
 *   at version 3 — run `./gradlew assembleDebug` once to produce it, then
 *   runMigrationsAndValidate can replace the direct-SQL approach below.
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
        helper.createDatabase("test_m12a", 1).apply {
            execSQL(
                "INSERT INTO threads (id, displayName, address, lastMessageAt, backupPolicy)" +
                " VALUES (1, 'Alice', '+1555', 1000000, 'GLOBAL')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate("test_m12a", 2, true, PostmarkDatabase.MIGRATION_1_2)
        db.query("SELECT lastMessagePreview FROM threads WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("", c.getString(0))
        }
    }

    @Test
    fun migration1To2_preservesExistingThreadData() {
        helper.createDatabase("test_m12b", 1).apply {
            execSQL(
                "INSERT INTO threads (id, displayName, address, lastMessageAt, backupPolicy)" +
                " VALUES (1, 'Bob', '+1999', 2000000, 'GLOBAL')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate("test_m12b", 2, true, PostmarkDatabase.MIGRATION_1_2)
        db.query("SELECT displayName, address, lastMessageAt FROM threads WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Bob", c.getString(0))
            assertEquals("+1999", c.getString(1))
            assertEquals(2_000_000L, c.getLong(2))
        }
    }

    @Test
    fun migration1To2_preservesExistingMessages() {
        helper.createDatabase("test_m12c", 1).apply {
            execSQL(
                "INSERT INTO threads (id, displayName, address, lastMessageAt, backupPolicy)" +
                " VALUES (1, 'Alice', '+1', 1000000, 'GLOBAL')"
            )
            execSQL(
                "INSERT INTO messages (id, threadId, address, body, timestamp, isSent, type)" +
                " VALUES (42, 1, '+1', 'hello migration', 1000000, 1, 2)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate("test_m12c", 2, true, PostmarkDatabase.MIGRATION_1_2)
        db.query("SELECT body FROM messages WHERE id = 42").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("hello migration", c.getString(0))
        }
    }

    // ── MIGRATION 2 → 3 ───────────────────────────────────────────────────
    // Applies migration SQL to a raw SupportSQLiteDatabase at v2 and verifies
    // the deliveryStatus column appears with default value 0.
    // Once 3.json is generated (after `./gradlew assembleDebug`), replace
    // PostmarkDatabase.MIGRATION_2_3.migrate(db2) with:
    //   helper.runMigrationsAndValidate("test_m23x", 3, true, PostmarkDatabase.MIGRATION_2_3)

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
}
