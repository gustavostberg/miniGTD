package com.gustav.minigtd

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.room.InvalidationTracker

class LauncherIntegrationProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.gustav.minigtd.launcher"
        private const val PATH_TASKS = "tasks"
        private const val MATCH_TASKS = 1

        const val COLUMN_ID = "_id"
        const val COLUMN_TITLE = "title"
        const val COLUMN_LIST_TYPE = "listType"
        const val COLUMN_PROJECT_NAME = "projectName"
        const val COLUMN_DUE_MILLIS = "dueMillis"

        val TASKS_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_TASKS")
    }

    private val uriMatcher =
        UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_TASKS, MATCH_TASKS)
        }

    private val taskObserver = object : InvalidationTracker.Observer("tasks") {
        override fun onInvalidated(tables: Set<String>) {
            context?.contentResolver?.notifyChange(TASKS_URI, null)
        }
    }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        MiniGtdDatabase.get(ctx).invalidationTracker.addObserver(taskObserver)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        return when (uriMatcher.match(uri)) {
            MATCH_TASKS -> queryTasks()
            else -> throw IllegalArgumentException("Unknown uri: $uri")
        }
    }

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            MATCH_TASKS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$PATH_TASKS"
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun queryTasks(): Cursor {
        val ctx = context ?: return emptyCursor()
        val database = MiniGtdDatabase.get(ctx)
        val taskDao = database.taskDao()
        val cursor = emptyCursor()

        val tasks = taskDao.getActiveForLauncherList("NEXT").take(10)

        tasks.forEach { task ->
            cursor.addRow(
                arrayOf<Any?>(
                    task.id,
                    task.title,
                    task.listType,
                    null,
                    task.dueMillis,
                ),
            )
        }

        cursor.setNotificationUri(ctx.contentResolver, TASKS_URI)
        return cursor
    }

    private fun emptyCursor(): MatrixCursor {
        return MatrixCursor(
            arrayOf(
                COLUMN_ID,
                COLUMN_TITLE,
                COLUMN_LIST_TYPE,
                COLUMN_PROJECT_NAME,
                COLUMN_DUE_MILLIS,
            ),
        )
    }

}
