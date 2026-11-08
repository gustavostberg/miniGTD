package com.gustav.minigtd

import android.database.ContentObserver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LauncherIntegrationProviderTest : MiniGtdTestSupport() {
    @Test fun widgetShowsOnlyUnfinishedNextTasksWithStableRoomIdsAndDeadlines() {
        val provider = Robolectric.buildContentProvider(LauncherIntegrationProvider::class.java).create().get()
        val id = db.taskDao().insert(TaskEntity(title = "Next task", listType = "NEXT", dueMillis = 123456L))
        db.taskDao().insert(TaskEntity(title = "Inbox", listType = "INBOX"))
        db.taskDao().insert(TaskEntity(title = "Done", listType = "NEXT", doneAt = 100))
        provider.query(LauncherIntegrationProvider.TASKS_URI, null, null, null, null).use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(id, cursor.getLong(cursor.getColumnIndexOrThrow("_id")))
            assertEquals("Next task", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertEquals(123456L, cursor.getLong(cursor.getColumnIndexOrThrow("dueMillis")))
            assertEquals(LauncherIntegrationProvider.TASKS_URI, cursor.notificationUri)
        }
    }

    @Test fun widgetSnapshotIsOrderedAndBounded() {
        val provider = Robolectric.buildContentProvider(LauncherIntegrationProvider::class.java).create().get()
        (15 downTo 1).forEach { db.taskDao().insert(TaskEntity(title = "Task $it", listType = "NEXT", createdAt = it.toLong())) }
        provider.query(LauncherIntegrationProvider.TASKS_URI, null, null, null, null).use { cursor ->
            assertEquals(10, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("Task 1", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        }
    }

    @Test fun widgetReceivesChangeNotificationWhenTaskIsCompleted() {
        Robolectric.buildContentProvider(LauncherIntegrationProvider::class.java).create().get()
        val id = db.taskDao().insert(TaskEntity(title = "Next task", listType = "NEXT"))
        db.invalidationTracker.refreshVersionsSync()
        val notified = CountDownLatch(1)
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { notified.countDown() }
        }
        context.contentResolver.registerContentObserver(LauncherIntegrationProvider.TASKS_URI, false, observer)
        try {
            db.taskDao().markDone(id)
            db.invalidationTracker.refreshVersionsSync()
            assertTrue("No content notification after task completion", notified.await(3, TimeUnit.SECONDS))
        } finally { context.contentResolver.unregisterContentObserver(observer) }
    }
}
