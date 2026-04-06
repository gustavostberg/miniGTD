package com.gustav.minigtd

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

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

    private data class LauncherTask(
        val id: Long,
        val title: String,
        val listType: String,
        val projectName: String?,
        val dueMillis: Long?,
        val createdAt: Long,
        val priority: Int,
    )

    private val uriMatcher =
        UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_TASKS, MATCH_TASKS)
        }

    override fun onCreate(): Boolean = true

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

        val tasks =
            taskDao.getActiveForLauncherList("NEXT")
                .map { task -> task.asLauncherTask(priority = 0, projectName = null) }
                .take(10)

        tasks.forEachIndexed { index, task ->
            cursor.addRow(
                arrayOf(
                    index.toLong(),
                    task.title,
                    task.listType,
                    task.projectName,
                    task.dueMillis,
                ),
            )
        }

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

    private fun TaskEntity.asLauncherTask(priority: Int, projectName: String?): LauncherTask {
        return LauncherTask(
            id = id,
            title = title,
            listType = listType,
            projectName = projectName,
            dueMillis = dueMillis,
            createdAt = createdAt,
            priority = priority,
        )
    }
}
