package com.gustav.minigtd

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationTest : MiniGtdTestSupport() {
    private fun checkUpgrade(version: Int) {
        val resource = javaClass.classLoader!!.getResourceAsStream("com.gustav.minigtd.MiniGtdDatabase/$version.json")!!
        val schema = JSONObject(resource.bufferedReader().use { it.readText() }).getJSONObject("database")
        context.openOrCreateDatabase("minigtd.db", Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val tableName = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
                val indices = entity.optJSONArray("indices") ?: continue
                for (i in 0 until indices.length()) {
                    old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", tableName))
                }
            }
            val setupQueries = schema.getJSONArray("setupQueries")
            for (i in 0 until setupQueries.length()) old.execSQL(setupQueries.getString(i))
            old.execSQL("INSERT INTO projects VALUES (41, 'Existing project', 100, 0)")
            old.execSQL("INSERT INTO tasks VALUES (42, 'Existing task', 'PROJECT', 41, 123456, 100, NULL)")
            old.execSQL("INSERT INTO done_log VALUES (43, 100, 'Done before upgrade', 'inbox', NULL)")
            if (version >= 2) old.execSQL("INSERT INTO settings VALUES (1, 7, 5, 'BLACK', -1)")
            if (version >= 3) old.execSQL("INSERT INTO review_items VALUES (44, 'Existing trigger', 100)")
            old.version = version
        }

        val task = db.taskDao().getById(42)
        assertNotNull("Upgrade from $version lost the existing task", task)
        assertEquals(41L, task!!.projectId)
        assertEquals(123456L, task.dueMillis)
        assertEquals("Existing project", db.projectDao().getById(41)!!.name)
        assertEquals("Done before upgrade", db.doneDao().getAllAsc().single().text)
        if (version >= 2) assertEquals(7, db.settingsDao().get()!!.pageSize)
        if (version >= 3) assertEquals("Existing trigger", db.reviewItemDao().getAll().single().title)
        assertEquals(0, db.importedInboxTaskDao().countByUuid("not-imported"))
        assertEquals(4, db.openHelper.readableDatabase.version)
    }

    @Test fun preservesVersion1Data() = checkUpgrade(1)
    @Test fun preservesVersion2Data() = checkUpgrade(2)
    @Test fun preservesVersion3Data() = checkUpgrade(3)
}
