package com.gustav.minigtd

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import org.junit.After
import org.junit.Before
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import java.io.File

abstract class MiniGtdTestSupport {
    protected val context: Context get() = RuntimeEnvironment.getApplication()
    protected val db: MiniGtdDatabase get() = MiniGtdDatabase.get(context)

    @Before
    fun resetDatabase() {
        closeDatabase()
        context.deleteDatabase("minigtd.db")
        context.getSharedPreferences("mini_gtd_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun closeDatabase() {
        val field = MiniGtdDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        (field.get(null) as? MiniGtdDatabase)?.close()
        field.set(null, null)
    }

    protected fun MainActivity.call(name: String, vararg args: Any?): Any? {
        val method = MainActivity::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == args.size
        }.apply { isAccessible = true }
        return method.invoke(this, *args)
    }

    protected fun installInbox(vararg json: String): FakeInboxProvider {
        val provider = Robolectric.buildContentProvider(FakeInboxProvider::class.java)
            .create(FakeInboxProvider.AUTHORITY).get()
        json.forEachIndexed { index, raw ->
            provider.files["task-$index.json"] = File(context.cacheDir, "task-$index.json").apply {
                writeText(raw)
            }
        }
        context.getSharedPreferences("mini_gtd_prefs", Context.MODE_PRIVATE).edit()
            .putString("import_inbox_tree_uri", FakeInboxProvider.TREE_URI.toString()).commit()
        return provider
    }
}

class FakeInboxProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.gustav.minigtd.test.documents"
        val TREE_URI: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "vault")
    }

    val files = linkedMapOf<String, File>()
    var failReads = false

    override fun onCreate() = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        if (failReads) throw SecurityException("permission revoked")
        val columns = projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val parent = DocumentsContract.getDocumentId(uri)
        val names = if (uri.lastPathSegment == "children") {
            when (parent) {
                "vault" -> listOf("_minigtd")
                "_minigtd" -> listOf("inbox")
                "inbox" -> files.keys.toList()
                else -> emptyList()
            }
        } else {
            listOf(parent)
        }
        return MatrixCursor(columns).apply {
            names.forEach { name ->
                addRow(columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> name
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> name
                        DocumentsContract.Document.COLUMN_MIME_TYPE ->
                            if (name in files) "application/json" else DocumentsContract.Document.MIME_TYPE_DIR
                        else -> null
                    }
                })
            }
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(files.getValue(DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri): String = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
