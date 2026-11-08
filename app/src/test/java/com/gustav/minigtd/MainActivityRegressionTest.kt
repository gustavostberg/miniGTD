package com.gustav.minigtd

import android.content.Intent
import android.app.DatePickerDialog
import android.graphics.Color
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityRegressionTest : MiniGtdTestSupport() {
    private fun activity(test: (MainActivity) -> Unit) {
        val controller: ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java).setup()
        try { test(controller.get()) } finally { controller.pause().stop().destroy() }
    }

    private fun MainActivity.key(code: Int) {
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
    }

    private fun MainActivity.body(): String = findViewById<TextView>(R.id.body).text.toString()

    private fun taskJson(extra: String = "", uuid: String = UUID.randomUUID().toString()): String =
        """{"uuid":"$uuid","title":"Import test"$extra}"""

    @Test fun importsOnlyCheckedTasksAndCannotImportThemTwiceEvenWhenRenameFails() {
        installInbox(taskJson(), taskJson())
        activity { app ->
            app.key(KeyEvent.KEYCODE_I)
            assertTrue(app.body().contains("2 NEW TASKS"))
            app.key(KeyEvent.KEYCODE_X)
            app.key(KeyEvent.KEYCODE_ENTER)
            assertEquals(1, db.taskDao().getActiveForList("INBOX").size)
            app.key(KeyEvent.KEYCODE_I)
            assertTrue(app.body().contains("1 NEW TASKS"))
            assertTrue(app.body().contains("1 already imported"))
            app.key(KeyEvent.KEYCODE_ENTER)
            assertEquals(2, db.taskDao().getActiveForList("INBOX").size)
            app.key(KeyEvent.KEYCODE_I)
            assertTrue(app.body().contains("0 NEW TASKS"))
        }
    }

    @Test fun unknownProjectAlwaysGoesToInboxEvenWhenNextWasRequested() {
        installInbox(taskJson(", \"projectName\":\"Does not exist\", \"listType\":\"NEXT\""))
        activity { app ->
            app.key(KeyEvent.KEYCODE_I)
            assertTrue(app.body().contains("? Does not exist"))
            app.key(KeyEvent.KEYCODE_ENTER)
            assertEquals(1, db.taskDao().getActiveForList("INBOX").size)
            assertTrue(db.taskDao().getActiveForList("NEXT").isEmpty())
            assertTrue(db.projectDao().getActiveProjects().isEmpty())
        }
    }

    @Test fun matchesExistingProjectAndRestoresItWithoutCreatingAnother() {
        val projectId = db.projectDao().insert(ProjectEntity(name = "S900+", isArchived = true))
        installInbox(taskJson(""", "projectName":"s900+", "dueMillis":1800000000000"""))
        activity { app ->
            app.key(KeyEvent.KEYCODE_I)
            app.key(KeyEvent.KEYCODE_ENTER)
            val task = db.taskDao().getActiveForProject(projectId).single()
            assertEquals(1800000000000L, task.dueMillis)
            assertEquals("PROJECT", task.listType)
            assertEquals(projectId, db.projectDao().getActiveProjects().single().id)
        }
    }

    @Test fun rejectsNullAndNonStringTitlesAndFractionalDeadlines() {
        val uuid = UUID.randomUUID()
        installInbox(
            """{"uuid":"$uuid", "title":null}""",
            """{"uuid":"$uuid", "title":123}""",
            taskJson(""", "dueMillis":1.5"""),
            "{partial file"
        )
        activity { app ->
            app.key(KeyEvent.KEYCODE_I)
            assertTrue(app.body(), app.body().contains("0 NEW TASKS"))
            assertTrue(app.body(), app.body().contains("4 invalid"))
        }
    }

    @Test fun acceptsUtf8BomAndDefaultsUnknownListToInbox() {
        installInbox("\uFEFF" + taskJson(", \"listType\":\"not-a-list\""))
        activity { app ->
            app.key(KeyEvent.KEYCODE_I)
            assertTrue(app.body(), app.body().contains("1 NEW TASKS"))
            app.key(KeyEvent.KEYCODE_ENTER)
            assertEquals(1, db.taskDao().getActiveForList("INBOX").size)
        }
    }

    @Test fun permissionLossIsReportedAsAnErrorInsteadOfEmptyInbox() {
        installInbox(taskJson()).failReads = true
        activity { app ->
            app.key(KeyEvent.KEYCODE_I)
            assertTrue(app.body(), app.body().contains("folder permission lost"))
        }
    }

    @Test fun duplicateFilesWithTheSameUuidAppearOnlyOnceInReview() {
        val raw = taskJson()
        installInbox(raw, raw)
        activity { app ->
            app.key(KeyEvent.KEYCODE_I)
            assertTrue(app.body(), app.body().contains("1 NEW TASKS"))
        }
    }

    @Test fun captureFromLauncherReturnsToInboxAfterCancellation() {
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CAPTURE, true)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        try {
            val app = controller.get()
            app.call("closeDialog")
            assertTrue(app.findViewById<TextView>(R.id.header).text.toString().contains("inbox"))
            assertFalse(app.intent.hasExtra(MainActivity.EXTRA_OPEN_CAPTURE))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun pagingClearsSelectionSoMoveCannotAffectAnotherTask() {
        db.settingsDao().upsert(SettingsEntity(pageSize = 5))
        repeat(6) { db.taskDao().insert(TaskEntity(title = "Task $it", listType = "INBOX", createdAt = it.toLong())) }
        activity { app ->
            app.key(KeyEvent.KEYCODE_1)
            app.key(KeyEvent.KEYCODE_ENTER)
            app.key(KeyEvent.KEYCODE_X)
            app.key(KeyEvent.KEYCODE_D)
            assertTrue(db.taskDao().getActiveForList("NEXT").isEmpty())
        }
    }

    @Test fun processAndDonePreserveDeadlineAndWriteDoneLog() {
        val id = db.taskDao().insert(TaskEntity(title = "Finish this", listType = "INBOX", dueMillis = 123456L))
        activity { app ->
            app.key(KeyEvent.KEYCODE_1)
            app.key(KeyEvent.KEYCODE_ENTER)
            app.key(KeyEvent.KEYCODE_D)
            assertEquals("NEXT", db.taskDao().getById(id)!!.listType)
            assertEquals(123456L, db.taskDao().getById(id)!!.dueMillis)
            app.key(KeyEvent.KEYCODE_D)
            app.key(KeyEvent.KEYCODE_1)
            app.key(KeyEvent.KEYCODE_ENTER)
            app.key(KeyEvent.KEYCODE_ENTER)
            app.key(KeyEvent.KEYCODE_T)
            assertNotNull(db.taskDao().getById(id)!!.doneAt)
            assertEquals("Finish this", db.doneDao().getAllAsc().single().text)
        }
    }

    @Test fun cannotArchiveProjectWithUnfinishedActions() {
        val id = db.projectDao().insert(ProjectEntity(name = "Still active"))
        db.taskDao().insert(TaskEntity(title = "Action", listType = "PROJECT", projectId = id))
        activity { app ->
            app.key(KeyEvent.KEYCODE_G)
            app.key(KeyEvent.KEYCODE_1)
            app.key(KeyEvent.KEYCODE_ENTER)
            app.key(KeyEvent.KEYCODE_T)
            assertFalse(db.projectDao().getById(id)!!.isArchived)
        }
    }

    private fun View.descendants(): List<View> = listOf(this) +
        if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).descendants() } else emptyList()

    private fun MainActivity.dialogViews(): List<View> {
        val field = MainActivity::class.java.getDeclaredField("dialog").apply { isAccessible = true }
        val dialog = field.get(this) as androidx.appcompat.app.AlertDialog
        return dialog.window!!.decorView.descendants()
    }

    @Test fun dueQuickDurationRollsEndDateForwardAtMidnight() {
        activity { app ->
            val date = app.call("parseYYMMDD", "261108")
            val start = app.call("parseHHMM", "2200", date) as java.util.Calendar
            val id = db.taskDao().insert(TaskEntity(title = "Late task", listType = "INBOX", dueMillis = start.timeInMillis))
            app.call("openDueEditorForTask", id, false)
            val views = app.dialogViews()
            val fields = views.filterIsInstance<EditText>()
            fields[3].setText("2330")
            views.filterIsInstance<TextView>().single { it.text.toString() == "+1h" }.performClick()
            assertEquals("261109", fields[2].text.toString())
            assertEquals("0030", fields[4].text.toString())
            app.call("closeDialog")
        }
    }

    @Test
    @Config(qualifiers = "night")
    fun dueLabelsAreReadableInSystemDarkTheme() {
        val id = db.taskDao().insert(TaskEntity(title = "Task", listType = "INBOX"))
        activity { app ->
            app.call("openDueEditorForTask", id, false)
            val views = app.dialogViews()
            val titleLabel = views.filterIsInstance<TextView>().single { it.text.toString() == "Title" }
            assertTrue("Black label on a dark dialog", Color.luminance(titleLabel.currentTextColor) > 0.4)
            app.call("closeDialog")
        }
    }

    @Test fun changingStartDatePreservesAnOvernightEnd() {
        activity { app ->
            val date = app.call("parseYYMMDD", "261108")
            val start = app.call("parseHHMM", "2330", date) as java.util.Calendar
            val id = db.taskDao().insert(TaskEntity(title = "Late task", listType = "INBOX", dueMillis = start.timeInMillis))
            app.call("openDueEditorForTask", id, false)
            val fields = app.dialogViews().filterIsInstance<EditText>()
            fields[1].performClick()
            val picker = ShadowDialog.getLatestDialog() as DatePickerDialog
            picker.datePicker.updateDate(2026, 10, 10)
            picker.getButton(DatePickerDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("261110", fields[1].text.toString())
            assertEquals("261111", fields[2].text.toString())
            app.call("closeDialog")
        }
    }
}
