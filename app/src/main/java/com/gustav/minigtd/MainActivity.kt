package com.gustav.minigtd

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.DocumentsContract
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.json.JSONObject

// -----------------------
// Room: Entities / DAO / DB
// -----------------------

@Entity(
    tableName = "projects",
    indices = [Index(value = ["name"], unique = true)]
)
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isArchived: Boolean = false
)

@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["listType", "doneAt"]),
        Index(value = ["projectId", "listType", "doneAt"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,

    // "INBOX", "SOMEDAY", "NEXT", "WAITING", "PROJECT"
    val listType: String,

    // null unless listType == "PROJECT"
    val projectId: Long? = null,

    // metadata
    val dueMillis: Long? = null,

    val createdAt: Long = System.currentTimeMillis(),
    val doneAt: Long? = null
)

@Entity(tableName = "done_log", indices = [Index(value = ["tsMillis"])])
data class DoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tsMillis: Long,
    val text: String,
    val origin: String,
    val project: String? = null
)

@Entity(tableName = "review_items", indices = [Index(value = ["createdAt"])])
data class ReviewItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "imported_inbox_tasks")
data class ImportedInboxTaskEntity(
    @PrimaryKey val uuid: String,
    val importedAt: Long = System.currentTimeMillis(),
    val taskId: Long? = null
)

@Dao
interface ReviewItemDao {
    @Query("SELECT * FROM review_items ORDER BY createdAt ASC")
    fun getAll(): List<ReviewItemEntity>

    @Insert
    fun insert(r: ReviewItemEntity): Long

    @Query("DELETE FROM review_items WHERE id = :id")
    fun deleteById(id: Long)
}

@Dao
interface ImportedInboxTaskDao {
    @Query("SELECT COUNT(*) FROM imported_inbox_tasks WHERE uuid = :uuid")
    fun countByUuid(uuid: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(e: ImportedInboxTaskEntity): Long
}

/**
 * Single-row settings table (id=1).
 * We keep it dead simple for v1.
 */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,

    val pageSize: Int = 18,
    val pageSizeLog: Int = 9,

    // "WHITE" or "BLACK"
    val backgroundMode: String = "WHITE",

    // Android color int (ARGB). Default black.
    val textColor: Int = Color.BLACK
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE isArchived = 0 ORDER BY createdAt ASC")
    fun getActiveProjects(): List<ProjectEntity>

    @Query("""
        SELECT DISTINCT p.*
        FROM projects p
        INNER JOIN tasks t ON t.projectId = p.id
        WHERE p.isArchived = 1
          AND t.doneAt IS NULL
          AND t.listType = 'PROJECT'
        ORDER BY p.createdAt ASC
    """)
    fun getArchivedProjectsWithActiveTasks(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    fun getById(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    fun getByNameCaseInsensitive(name: String): ProjectEntity?

    @Insert
    fun insert(p: ProjectEntity): Long

    @Update
    fun update(p: ProjectEntity)

    @Query("UPDATE projects SET isArchived = 0 WHERE id = :id")
    fun unarchiveById(id: Long)

    @Query("UPDATE projects SET isArchived = 1 WHERE id = :id")
    fun archiveById(id: Long)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE doneAt IS NULL AND listType = :listType AND projectId IS NULL ORDER BY createdAt ASC")
    fun getActiveForList(listType: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE doneAt IS NULL AND listType = :listType ORDER BY createdAt ASC")
    fun getActiveForLauncherList(listType: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE doneAt IS NULL AND listType = 'PROJECT' AND projectId = :projectId ORDER BY createdAt ASC")
    fun getActiveForProject(projectId: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    fun getById(id: Long): TaskEntity?

    @Insert
    fun insert(t: TaskEntity): Long

    @Update
    fun update(t: TaskEntity)

    @Query("UPDATE tasks SET listType = :destListType, projectId = NULL WHERE id = :taskId")
    fun moveToList(taskId: Long, destListType: String)

    @Query("UPDATE tasks SET listType = 'PROJECT', projectId = :projectId WHERE id = :taskId")
    fun moveToProject(taskId: Long, projectId: Long)

    @Query("UPDATE tasks SET dueMillis = :dueMillis WHERE id = :taskId")
    fun setDue(taskId: Long, dueMillis: Long?)

    @Query("UPDATE tasks SET doneAt = :doneAt WHERE id = :taskId")
    fun markDone(taskId: Long, doneAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM tasks WHERE id = :taskId")
    fun deleteById(taskId: Long)
}

@Dao
interface DoneDao {
    @Query("SELECT * FROM done_log ORDER BY tsMillis DESC")
    fun getAllAsc(): List<DoneEntity>

    @Insert
    fun insert(e: DoneEntity): Long

    @Query("SELECT COUNT(*) FROM done_log")
    fun count(): Int

    // delete N oldest
    @Query("DELETE FROM done_log WHERE id IN (SELECT id FROM done_log ORDER BY tsMillis ASC LIMIT :n)")
    fun deleteOldest(n: Int)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1 LIMIT 1")
    fun get(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(s: SettingsEntity)

    @Query("DELETE FROM settings")
    fun nuke()
}

@Database(
    entities = [
        ProjectEntity::class,
        TaskEntity::class,
        DoneEntity::class,
        SettingsEntity::class,
        ReviewItemEntity::class,
        ImportedInboxTaskEntity::class
    ],
    version = 4
)
abstract class MiniGtdDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun taskDao(): TaskDao
    abstract fun doneDao(): DoneDao
    abstract fun settingsDao(): SettingsDao
    abstract fun reviewItemDao(): ReviewItemDao
    abstract fun importedInboxTaskDao(): ImportedInboxTaskDao

    companion object {
        @Volatile private var INSTANCE: MiniGtdDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS settings (
                        id INTEGER NOT NULL,
                        pageSize INTEGER NOT NULL,
                        pageSizeLog INTEGER NOT NULL,
                        backgroundMode TEXT NOT NULL,
                        textColor INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """)
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS review_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_review_items_createdAt ON review_items(createdAt)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS imported_inbox_tasks (
                        uuid TEXT NOT NULL,
                        importedAt INTEGER NOT NULL,
                        taskId INTEGER,
                        PRIMARY KEY(uuid)
                    )
                """)
            }
        }

        fun get(context: Context): MiniGtdDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE?.let { return@synchronized it }
                val inst = Room.databaseBuilder(
                    context.applicationContext,
                    MiniGtdDatabase::class.java,
                    "minigtd.db"
                )
                    .allowMainThreadQueries()
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()

                val sdao = inst.settingsDao()
                if (sdao.get() == null) sdao.upsert(SettingsEntity())
                INSTANCE = inst
                inst
            }
        }
    }
}

// -----------------------
// MainActivity
// -----------------------

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_OPEN_NEXT = "com.gustav.minigtd.extra.OPEN_NEXT"
        const val EXTRA_OPEN_INBOX = "com.gustav.minigtd.extra.OPEN_INBOX"
        const val EXTRA_OPEN_CAPTURE = "com.gustav.minigtd.extra.OPEN_CAPTURE"
        private const val CALENDAR_PERMISSION_REQUEST = 2001
        private const val IMPORT_INBOX_TREE_REQUEST = 2002
        private const val PREF_WRITE_CALENDAR_ID = "write_calendar_id"
        private const val PREF_DUE_REMINDER_MINUTES = "due_reminder_minutes"
        private const val PREF_IMPORT_INBOX_TREE_URI = "import_inbox_tree_uri"
    }

    // --- UI ---
    private lateinit var header: TextView
    private lateinit var body: TextView
    private lateinit var helpOverlay: FrameLayout
    private lateinit var helpPanel: LinearLayout
    private lateinit var helpTitle: TextView
    private lateinit var helpBody: TextView
    private lateinit var helpHint: TextView

    // --- DB ---
    private lateinit var db: MiniGtdDatabase
    private lateinit var taskDao: TaskDao
    private lateinit var projectDao: ProjectDao
    private lateinit var doneDao: DoneDao
    private lateinit var settingsDao: SettingsDao
    private lateinit var reviewItemDao: ReviewItemDao
    private lateinit var importedInboxTaskDao: ImportedInboxTaskDao

    // --- Screens / state ---
    private enum class Screen {
        INBOX, SOMEDAY, NEXT, WAITING,
        PROJECTS, PROJECT_DETAIL,
        REVIEW,
        IMPORT_INBOX,
        PROCESS,
        PROJECT_PICKER,
        LOG,
        SETTINGS
    }

    private enum class ListId { INBOX, SOMEDAY, NEXT, WAITING }

    private data class InboxImportItem(
        val uuid: String,
        val title: String,
        val requestedListType: String,
        val projectName: String?,
        val projectId: Long?,
        val dueMillis: Long?,
        val documentUri: Uri,
        val displayName: String,
        val checked: Boolean = true
    )

    private data class ImportInboxFolder(
        val treeUri: Uri,
        val documentId: String,
        val label: String
    )

    private data class ImportChildDocument(
        val documentId: String,
        val displayName: String,
        val mimeType: String
    )

    private data class CalendarTarget(
        val id: Long,
        val name: String,
        val accountName: String
    )

    private data class DueEventFormPrefill(
        val calendarId: Long?,
        val title: String,
        val startDateYYMMDD: String,
        val endDateYYMMDD: String,
        val startTimeHHMM: String,
        val endTimeHHMM: String,
        val location: String,
        val description: String,
        val reminderLabel: String
    )

    private data class CalendarInsertResult(
        val eventSaved: Boolean,
        val reminderSaved: Boolean
    )

    private data class HelpRow(
        val shortcut: String,
        val description: String
    )

    private data class HelpSection(
        val title: String,
        val rows: List<HelpRow>
    )

    private enum class ProjectLookupResult {
        CREATED,
        OPENED,
        RESTORED
    }

    private var screen: Screen = Screen.INBOX
    private var lastKey: String = "—"

    // Paging / selection
    private var page = 0
    private var selectedSlot: Int? = null
    private var numberBuffer: String = "" // accumulates digits until Enter confirms

    // Projects
    private var openProjectId: Long? = null // when in PROJECT_DETAIL

    // Dialog gating
    private var isTextInputActive = false
    private var dialog: AlertDialog? = null
    private var dialogEdit: EditText? = null
    private var pendingDueEditorTaskId: Long? = null
    private var isHelpVisible = false

    // Process (selected item)
    private var processFrom: ListId? = null
    private var processTaskId: Long? = null
    private var processText: String = ""
    private var processDueMillis: Long? = null

    // Pending move-to-project (via picker)
    private var pendingTaskId: Long? = null
    private var pendingTaskTitle: String = ""
    private var pendingFrom: ListId? = null

    // JSON inbox import
    private var importItems: List<InboxImportItem> = emptyList()
    private var importInvalidCount = 0
    private var importDuplicateCount = 0
    private var importLoadError: String? = null
    private var importFolderLabel: String = ""

    // Limits
    private val maxPickerProjects = 30
    private val doneLogMax = 100
    private val prefs by lazy { getSharedPreferences("mini_gtd_prefs", Context.MODE_PRIVATE) }

    // Settings (loaded from DB)
    private var pageSize: Int = 18
    private var pageSizeLog: Int = 9
    private var backgroundMode: String = "WHITE" // WHITE/BLACK
    private var textColor: Int = Color.BLACK

    private val workSansRegular by lazy {
        ResourcesCompat.getFont(this, R.font.work_sans_regular) ?: Typeface.SANS_SERIF
    }
    private val workSansMedium by lazy {
        ResourcesCompat.getFont(this, R.font.work_sans_medium) ?: workSansRegular
    }

    private val dueFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply {
        isLenient = false
    }
    private val importDueFmt = SimpleDateFormat("d MMM", Locale.getDefault()).apply {
        isLenient = false
    }
    private val importAllowedListTypes = setOf("INBOX", "SOMEDAY", "NEXT", "WAITING", "PROJECT")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        header = findViewById(R.id.header)
        body = findViewById(R.id.body)
        helpOverlay = findViewById(R.id.help_overlay)
        helpPanel = findViewById(R.id.help_panel)
        helpTitle = findViewById(R.id.help_title)
        helpBody = findViewById(R.id.help_body)
        helpHint = findViewById(R.id.help_hint)
        body.isFocusableInTouchMode = true
        body.requestFocus()
        helpOverlay.setOnClickListener { toggleHelpOverlay(false) }
        helpPanel.setOnClickListener { }

        db = MiniGtdDatabase.get(this)
        taskDao = db.taskDao()
        projectDao = db.projectDao()
        doneDao = db.doneDao()
        settingsDao = db.settingsDao()
        reviewItemDao = db.reviewItemDao()
        importedInboxTaskDao = db.importedInboxTaskDao()

        repairArchivedProjectsWithActiveTasks()

        loadSettingsApplyTheme()

        screen = Screen.INBOX
        if (!consumeLauncherIntent(intent)) {
            render()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!consumeLauncherIntent(intent)) {
            render()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMPORT_INBOX_TREE_REQUEST) return

        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            lastKey = "import folder cancelled"
            render()
            return
        }

        val flags = if (data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        } else {
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            // Some providers grant temporary access only. Reading may still work now.
        }

        prefs.edit().putString(PREF_IMPORT_INBOX_TREE_URI, uri.toString()).apply()
        openImportInbox()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CALENDAR_PERMISSION_REQUEST) return

        val taskId = pendingDueEditorTaskId
        pendingDueEditorTaskId = null

        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (taskId == null) return

        if (!granted) {
            lastKey = "calendar permission denied"
        }

        openDueEditorForTask(taskId, requestPermissionIfNeeded = false)
    }

    private fun hasCalendarPermissions(): Boolean {
        val readGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        return readGranted && writeGranted
    }

    private fun requestCalendarPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR
            ),
            CALENDAR_PERMISSION_REQUEST
        )
    }

    private fun getWritableCalendars(): List<CalendarTarget> {
        if (!hasCalendarPermissions()) return emptyList()

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )

        return try {
            contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val visible = cursor.getInt(3) == 1
                        val access = cursor.getInt(4)
                        if (!visible || access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                        add(
                            CalendarTarget(
                                id = cursor.getLong(0),
                                name = cursor.getString(1).orEmpty(),
                                accountName = cursor.getString(2).orEmpty()
                            )
                        )
                    }
                }
            }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun selectDefaultCalendarId(calendars: List<CalendarTarget>): Long? {
        if (calendars.isEmpty()) return null
        val saved = prefs.getLong(PREF_WRITE_CALENDAR_ID, -1L)
        if (saved != -1L && calendars.any { it.id == saved }) return saved

        return calendars.firstOrNull {
            it.accountName.contains("@") || it.accountName.contains("google", ignoreCase = true)
        }?.id ?: calendars.first().id
    }

    private fun saveDefaultCalendarId(calendarId: Long) {
        prefs.edit().putLong(PREF_WRITE_CALENDAR_ID, calendarId).apply()
    }

    private fun dueReminderOptions(): List<Pair<String, Int?>> = listOf(
        "None" to null,
        "At time of event" to 0,
        "5 min before" to 5,
        "10 min before" to 10,
        "15 min before" to 15,
        "30 min before" to 30,
        "1 hour before" to 60,
        "1 day before" to 1440
    )

    private fun dueReminderMinutesToLabel(minutes: Int?): String {
        return dueReminderOptions().firstOrNull { it.second == minutes }?.first ?: "None"
    }

    private fun dueReminderLabelToMinutes(label: String): Int? {
        return dueReminderOptions().firstOrNull { it.first == label.trim() }?.second
    }

    private fun loadDefaultDueReminderMinutes(): Int? {
        val saved = prefs.getInt(PREF_DUE_REMINDER_MINUTES, 10)
        return if (saved == -1) null else dueReminderOptions().firstOrNull { it.second == saved }?.second ?: 10
    }

    private fun saveDefaultDueReminderMinutes(minutes: Int?) {
        prefs.edit().putInt(PREF_DUE_REMINDER_MINUTES, minutes ?: -1).apply()
    }

    @Suppress("DEPRECATION")
    private fun appRevisionLabel(): String {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun nextHalfHourStart(): Calendar {
        return Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            val minute = get(Calendar.MINUTE)
            if (minute < 30) {
                set(Calendar.MINUTE, 30)
            } else {
                add(Calendar.HOUR_OF_DAY, 1)
                set(Calendar.MINUTE, 0)
            }
        }
    }

    private fun formatYYMMDD(ms: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(
            Locale.US,
            "%02d%02d%02d",
            cal.get(Calendar.YEAR) % 100,
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun formatHHMM(ms: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(
            Locale.US,
            "%02d%02d",
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE)
        )
    }

    private fun buildDueEventPrefill(task: TaskEntity, calendars: List<CalendarTarget>): DueEventFormPrefill {
        val start = task.dueMillis?.let {
            Calendar.getInstance().apply {
                timeInMillis = it
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        } ?: nextHalfHourStart()
        val end = (start.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, 1) }

        return DueEventFormPrefill(
            calendarId = selectDefaultCalendarId(calendars),
            title = task.title,
            startDateYYMMDD = formatYYMMDD(start.timeInMillis),
            endDateYYMMDD = formatYYMMDD(end.timeInMillis),
            startTimeHHMM = formatHHMM(start.timeInMillis),
            endTimeHHMM = formatHHMM(end.timeInMillis),
            location = "",
            description = "",
            reminderLabel = dueReminderMinutesToLabel(loadDefaultDueReminderMinutes())
        )
    }

    private fun parseYYMMDD(raw: String): Calendar? {
        val clean = raw.trim()
        if (!Regex("""^\d{6}$""").matches(clean)) return null

        return try {
            Calendar.getInstance().apply {
                isLenient = false
                clear()
                set(2000 + clean.substring(0, 2).toInt(), clean.substring(2, 4).toInt() - 1, clean.substring(4, 6).toInt(), 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
                timeInMillis
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseHHMM(raw: String, baseDate: Calendar): Calendar? {
        val clean = raw.trim()
        if (!Regex("""^\d{4}$""").matches(clean)) return null

        return try {
            val hour = clean.substring(0, 2).toInt()
            val minute = clean.substring(2, 4).toInt()
            if (hour !in 0..23 || minute !in 0..59) return null

            (baseDate.clone() as Calendar).apply {
                isLenient = false
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                timeInMillis
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseHHMMPartsOrDefault(
        value: String,
        defaultHour: Int = 9,
        defaultMinute: Int = 0
    ): Pair<Int, Int> {
        val clean = value.trim()
        if (!Regex("""^\d{4}$""").matches(clean)) return defaultHour to defaultMinute
        val hour = clean.substring(0, 2).toIntOrNull() ?: return defaultHour to defaultMinute
        val minute = clean.substring(2, 4).toIntOrNull() ?: return defaultHour to defaultMinute
        return if (hour in 0..23 && minute in 0..59) hour to minute else defaultHour to defaultMinute
    }

    private fun showTimePickerForField(field: EditText) {
        val (hour, minute) = parseHHMMPartsOrDefault(field.text?.toString().orEmpty())
        TimePickerDialog(
            this,
            { _, pickedHour, pickedMinute ->
                field.setText(String.format(Locale.US, "%02d%02d", pickedHour, pickedMinute))
            },
            hour,
            minute,
            true
        ).show()
    }

    private fun showDatePickerForField(
        field: EditText,
        fallbackMillis: Long? = null,
        onPicked: (() -> Unit)? = null
    ) {
        val parsed = parseYYMMDD(field.text?.toString().orEmpty())
            ?: Calendar.getInstance().apply {
                if (fallbackMillis != null) timeInMillis = fallbackMillis
            }

        DatePickerDialog(
            this,
            { _, pickedYear, pickedMonth, pickedDay ->
                field.setText(String.format(Locale.US, "%02d%02d%02d", pickedYear % 100, pickedMonth + 1, pickedDay))
                onPicked?.invoke()
            },
            parsed.get(Calendar.YEAR),
            parsed.get(Calendar.MONTH),
            parsed.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun setFieldFromFieldPlusMinutes(
        sourceDate: EditText,
        sourceTime: EditText,
        targetDate: EditText?,
        targetTime: EditText,
        minutes: Int
    ) {
        val date = parseYYMMDD(sourceDate.text.toString()) ?: return
        val cal = parseHHMM(sourceTime.text.toString(), date) ?: return
        cal.add(Calendar.MINUTE, minutes)
        targetDate?.setText(formatYYMMDD(cal.timeInMillis))
        targetTime.setText(formatHHMM(cal.timeInMillis))
    }

    private fun insertTimedCalendarEvent(
        calendarId: Long,
        title: String,
        startMillis: Long,
        endMillis: Long,
        location: String,
        description: String,
        reminderMinutes: Int?
    ): CalendarInsertResult {
        if (!hasCalendarPermissions()) return CalendarInsertResult(eventSaved = false, reminderSaved = reminderMinutes == null)

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, if (reminderMinutes != null) 1 else 0)
        }

        return try {
            val eventUri = contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val eventId = eventUri?.lastPathSegment?.toLongOrNull()
            val reminderSaved = when {
                reminderMinutes == null -> true
                eventId == null -> false
                else -> insertCalendarReminder(eventId, reminderMinutes)
            }
            CalendarInsertResult(
                eventSaved = eventUri != null,
                reminderSaved = reminderSaved
            )
        } catch (_: Exception) {
            CalendarInsertResult(eventSaved = false, reminderSaved = reminderMinutes == null)
        }
    }

    private fun insertCalendarReminder(eventId: Long, minutes: Int): Boolean {
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }

        return try {
            contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun openDueEditorForTask(taskId: Long, requestPermissionIfNeeded: Boolean = true) {
        val task = taskDao.getById(taskId) ?: run {
            lastKey = "due (missing task)"
            render()
            return
        }

        val hasPermissions = hasCalendarPermissions()
        if (!hasPermissions && requestPermissionIfNeeded) {
            pendingDueEditorTaskId = taskId
            requestCalendarPermissions()
            return
        }

        pendingDueEditorTaskId = null
        openDueEventDialog(
            task = task,
            calendars = if (hasPermissions) getWritableCalendars() else emptyList(),
            permissionMissing = !hasPermissions
        )
    }

    private fun consumeLauncherIntent(intent: Intent?): Boolean {
        val launcherIntent = intent ?: return false
        val openNext = launcherIntent.getBooleanExtra(EXTRA_OPEN_NEXT, false)
        val openInbox = launcherIntent.getBooleanExtra(EXTRA_OPEN_INBOX, false)
        val openCapture = launcherIntent.getBooleanExtra(EXTRA_OPEN_CAPTURE, false)
        if (!openNext && !openInbox && !openCapture) {
            return false
        }

        closeDialog()
        screen = if (openInbox || openCapture) Screen.INBOX else Screen.NEXT
        page = 0
        selectedSlot = null
        numberBuffer = ""
        processFrom = null
        processTaskId = null
        processText = ""
        processDueMillis = null
        pendingTaskId = null
        pendingTaskTitle = ""
        pendingFrom = null
        lastKey =
            when {
                openCapture -> "launcher -> capture"
                openInbox -> "launcher -> inbox"
                else -> "launcher -> next"
            }
        render()

        if (openCapture) {
            openTextDialog("Capture", "to inbox") { captureToInbox(it) }
        }

        launcherIntent.removeExtra(EXTRA_OPEN_NEXT)
        launcherIntent.removeExtra(EXTRA_OPEN_INBOX)
        launcherIntent.removeExtra(EXTRA_OPEN_CAPTURE)
        return true
    }

    // -----------------------
    // JSON inbox import
    // -----------------------

    @Suppress("DEPRECATION")
    private fun pickImportInboxFolder() {
        closeDialog()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }

        try {
            startActivityForResult(intent, IMPORT_INBOX_TREE_REQUEST)
        } catch (_: ActivityNotFoundException) {
            lastKey = "import folder picker failed"
            render()
        }
    }

    private fun getImportInboxTreeUri(): Uri? {
        return prefs.getString(PREF_IMPORT_INBOX_TREE_URI, null)?.let { Uri.parse(it) }
    }

    private fun openImportInbox() {
        val treeUri = getImportInboxTreeUri()
        if (treeUri == null) {
            pickImportInboxFolder()
            return
        }

        importItems = emptyList()
        importInvalidCount = 0
        importDuplicateCount = 0
        importLoadError = null
        importFolderLabel = ""
        try {
            val folder = resolveImportInboxFolder(treeUri)
            if (folder == null) {
                importLoadError = "no _minigtd/inbox"
            } else {
                loadImportInbox(folder)
            }
        } catch (_: SecurityException) {
            importLoadError = "folder permission lost"
        } catch (_: Exception) {
            importLoadError = "folder read failed"
        }
        screen = Screen.IMPORT_INBOX
        page = 0
        selectedSlot = if (importItems.isNotEmpty()) 0 else null
        numberBuffer = ""
        lastKey = when {
            importLoadError != null -> "import read failed"
            importItems.isEmpty() -> "import: no new tasks"
            else -> "import: ${importItems.size} new"
        }
        render()
    }

    private fun resolveImportInboxFolder(treeUri: Uri): ImportInboxFolder? {
        val treeDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            importLoadError = "bad folder"
            return null
        }

        val selectedName = documentDisplayName(treeUri, treeDocumentId)
            ?: documentNameFromId(treeDocumentId)

        if (selectedName.equals("inbox", ignoreCase = true)) {
            return ImportInboxFolder(treeUri, treeDocumentId, "selected inbox")
        }

        val containerNames = listOf("_minigtd", ".minigtd", "miniGTD")
        containerNames.forEach { containerName ->
            val container = findChildDirectory(treeUri, treeDocumentId, containerName)
            if (container != null) {
                val inbox = findChildDirectory(treeUri, container.documentId, "inbox")
                if (inbox != null) {
                    return ImportInboxFolder(treeUri, inbox.documentId, "${container.displayName}/inbox")
                }
            }
        }

        if (
            selectedName.equals(".minigtd", ignoreCase = true) ||
            selectedName.equals("_minigtd", ignoreCase = true) ||
            selectedName.equals("miniGTD", ignoreCase = true)
        ) {
            val inbox = findChildDirectory(treeUri, treeDocumentId, "inbox")
            if (inbox != null) {
                return ImportInboxFolder(treeUri, inbox.documentId, "$selectedName/inbox")
            }
        }

        return null
    }

    private fun documentDisplayName(treeUri: Uri, documentId: String): String? {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return try {
            contentResolver.query(documentUri, projection, null, null, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (nameCol >= 0 && cursor.moveToFirst()) cursor.getString(nameCol) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun documentNameFromId(documentId: String): String {
        return documentId
            .substringAfterLast(':')
            .substringAfterLast('/')
            .ifBlank { documentId }
    }

    private fun findChildDirectory(treeUri: Uri, parentDocumentId: String, name: String): ImportChildDocument? {
        return listChildDocuments(treeUri, parentDocumentId).firstOrNull { child ->
            child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR &&
                    child.displayName.equals(name, ignoreCase = true)
        }
    }

    private fun listChildDocuments(treeUri: Uri, parentDocumentId: String): List<ImportChildDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        return contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            check(idCol >= 0 && nameCol >= 0 && mimeCol >= 0) { "Missing document columns" }

            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ImportChildDocument(
                            documentId = cursor.getString(idCol) ?: continue,
                            displayName = cursor.getString(nameCol).orEmpty(),
                            mimeType = cursor.getString(mimeCol).orEmpty()
                        )
                    )
                }
            }
        } ?: error("Folder query failed")
    }

    private fun loadImportInbox(folder: ImportInboxFolder) {
        importItems = emptyList()
        importInvalidCount = 0
        importDuplicateCount = 0
        importLoadError = null
        importFolderLabel = folder.label

        val loaded = mutableListOf<InboxImportItem>()
        val seenUuids = mutableSetOf<String>()
        try {
            listChildDocuments(folder.treeUri, folder.documentId).forEach { child ->
                if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) return@forEach
                if (!child.displayName.endsWith(".json", ignoreCase = true)) return@forEach

                val documentUri = DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, child.documentId)
                val item = parseImportInboxFile(documentUri, child.displayName)
                when {
                    item == null -> importInvalidCount += 1
                    importedInboxTaskDao.countByUuid(item.uuid) > 0 || !seenUuids.add(item.uuid) -> importDuplicateCount += 1
                    else -> loaded.add(item)
                }
            }
        } catch (_: SecurityException) {
            importLoadError = "folder permission lost"
        } catch (_: Exception) {
            importLoadError = "folder read failed"
        }

        importItems = loaded.sortedWith(
            compareBy<InboxImportItem> { it.displayName.lowercase(Locale.getDefault()) }
                .thenBy { it.title.lowercase(Locale.getDefault()) }
        )
    }

    private fun parseImportInboxFile(documentUri: Uri, displayName: String): InboxImportItem? {
        val raw = contentResolver.openInputStream(documentUri)?.use { stream ->
            stream.bufferedReader().use { it.readText() }
        } ?: error("File read failed")

        val json = try {
            JSONObject(raw.removePrefix("\uFEFF"))
        } catch (_: Exception) {
            return null
        }

        val uuidText = (json.opt("uuid") as? String)?.trim() ?: return null
        val uuid = try {
            UUID.fromString(uuidText).toString().also {
                if (!it.equals(uuidText, ignoreCase = true)) return null
            }
        } catch (_: Exception) {
            return null
        }

        val title = (json.opt("title") as? String)?.trim() ?: return null
        if (title.isEmpty()) return null

        val requestedListType = json
            .optString("listType", "INBOX")
            .trim()
            .uppercase(Locale.US)
            .let { if (it in importAllowedListTypes) it else "INBOX" }

        val projectName = if (json.has("projectName") && !json.isNull("projectName")) {
            val value = json.opt("projectName") as? String ?: return null
            value.trim().ifBlank { null }
        } else {
            null
        }
        val project = projectName?.let { projectDao.getByNameCaseInsensitive(it) }

        val dueMillis = if (json.has("dueMillis") && !json.isNull("dueMillis")) {
            when (val value = json.opt("dueMillis")) {
                is Long -> value
                is Int -> value.toLong()
                else -> return null
            }
        } else {
            null
        }

        return InboxImportItem(
            uuid = uuid,
            title = title,
            requestedListType = requestedListType,
            projectName = projectName,
            projectId = project?.id,
            dueMillis = dueMillis,
            documentUri = documentUri,
            displayName = displayName
        )
    }

    private fun toggleSelectedImportItem() {
        val slot = selectedSlot ?: run {
            lastKey = "x (no selection)"
            render()
            return
        }
        if (slot !in importItems.indices) {
            lastKey = "x (out of range)"
            render()
            return
        }

        importItems = importItems.toMutableList().also { items ->
            val item = items[slot]
            items[slot] = item.copy(checked = !item.checked)
            lastKey = if (items[slot].checked) "selected" else "unselected"
        }
        render()
    }

    private fun cancelImportInbox() {
        importItems = emptyList()
        importInvalidCount = 0
        importDuplicateCount = 0
        importLoadError = null
        goTo(Screen.INBOX, "import cancelled")
    }

    private enum class ImportResult { IMPORTED, DUPLICATE, FAILED }

    private fun importCheckedInboxItems() {
        val selected = importItems.filter { it.checked }
        if (selected.isEmpty()) {
            lastKey = "import (none selected)"
            render()
            return
        }

        var imported = 0
        var duplicates = 0
        var failed = 0
        var marked = 0

        selected.forEach { item ->
            when (importInboxItem(item)) {
                ImportResult.IMPORTED -> {
                    imported += 1
                    if (markImportFileProcessed(item)) marked += 1
                }
                ImportResult.DUPLICATE -> {
                    duplicates += 1
                    if (markImportFileProcessed(item)) marked += 1
                }
                ImportResult.FAILED -> failed += 1
            }
        }

        importItems = emptyList()
        importInvalidCount = 0
        importDuplicateCount = 0
        importLoadError = null
        screen = Screen.INBOX
        page = 0
        selectedSlot = null
        numberBuffer = ""
        lastKey = buildString {
            append("imported $imported")
            if (duplicates > 0) append(", skipped $duplicates")
            if (failed > 0) append(", failed $failed")
            if (marked < imported + duplicates) append(", files kept")
        }
        render()
    }

    private fun importInboxItem(item: InboxImportItem): ImportResult {
        return try {
            var result = ImportResult.DUPLICATE
            db.runInTransaction {
                if (importedInboxTaskDao.countByUuid(item.uuid) > 0) {
                    result = ImportResult.DUPLICATE
                    return@runInTransaction
                }

                val project = item.projectId?.let { projectDao.getById(it) }
                val targetProjectId = project?.id
                if (project?.isArchived == true) {
                    projectDao.unarchiveById(project.id)
                }
                val targetListType = if (targetProjectId != null) {
                    "PROJECT"
                } else if (item.projectName != null || item.requestedListType == "PROJECT") {
                    "INBOX"
                } else {
                    item.requestedListType
                }

                val taskId = taskDao.insert(
                    TaskEntity(
                        title = item.title,
                        listType = targetListType,
                        projectId = targetProjectId,
                        dueMillis = item.dueMillis
                    )
                )
                importedInboxTaskDao.insert(
                    ImportedInboxTaskEntity(
                        uuid = item.uuid,
                        taskId = taskId
                    )
                )
                result = ImportResult.IMPORTED
            }
            result
        } catch (_: Exception) {
            ImportResult.FAILED
        }
    }

    private fun markImportFileProcessed(item: InboxImportItem): Boolean {
        return try {
            DocumentsContract.renameDocument(
                contentResolver,
                item.documentUri,
                "${item.displayName}.imported"
            ) != null
        } catch (_: Exception) {
            false
        }
    }

    // -----------------------
    // Settings: load/apply/save
    // -----------------------

    private fun loadSettingsApplyTheme() {
        val s = settingsDao.get() ?: SettingsEntity().also { settingsDao.upsert(it) }
        pageSize = s.pageSize.coerceIn(5, 99)
        pageSizeLog = s.pageSizeLog.coerceIn(3, 99)
        backgroundMode = if (s.backgroundMode == "BLACK") "BLACK" else "WHITE"
        textColor = if (backgroundMode == "BLACK") Color.WHITE else Color.BLACK

        // Apply colors
        applyThemeColors()
    }

    private fun applyThemeColors() {
        val bg = if (backgroundMode == "BLACK") Color.BLACK else Color.WHITE
        val fg = if (backgroundMode == "BLACK") Color.WHITE else Color.BLACK
        textColor = fg
        val panelBg = if (backgroundMode == "BLACK") Color.parseColor("#121212") else Color.parseColor("#FAFAFA")
        val panelBorder = if (backgroundMode == "BLACK") Color.parseColor("#5F5F5F") else Color.parseColor("#D0D0D0")

        // Background on activity root
        //val root = findViewById<TextView>(android.R.id.content)
        //root.setBackgroundColor(bg)
        // FIXED — use your actual root layout
        val root = findViewById<android.view.View>(R.id.root)
        root.setBackgroundColor(bg)

        // Header/body
        header.setBackgroundColor(bg)
        body.setBackgroundColor(bg)
        header.setTextColor(fg)
        body.setTextColor(fg)
        helpPanel.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28f
            setColor(panelBg)
            setStroke(2, panelBorder)
        }
        helpTitle.setTextColor(fg)
        helpBody.setTextColor(fg)
        helpHint.setTextColor(if (backgroundMode == "BLACK") Color.parseColor("#BDBDBD") else Color.parseColor("#5C5C5C"))
    }

    private fun saveSettings() {
        textColor = if (backgroundMode == "BLACK") Color.WHITE else Color.BLACK
        settingsDao.upsert(
            SettingsEntity(
                id = 1,
                pageSize = pageSize.coerceIn(5, 99),
                pageSizeLog = pageSizeLog.coerceIn(3, 99),
                backgroundMode = backgroundMode,
                textColor = textColor
            )
        )
        applyThemeColors()
    }

    private fun restoreDefaultSettings() {
        pageSize = 18
        pageSizeLog = 9
        backgroundMode = "WHITE"
        saveSettings()
        lastKey = "settings: defaults restored"
        render()
    }

    // Very simple contrast guard: block “almost same” luminance.
    // (Not perfect, but prevents black-on-black / dark-on-black etc.)
    private fun repairArchivedProjectsWithActiveTasks() {
        val archivedWithActiveTasks = projectDao.getArchivedProjectsWithActiveTasks()
        if (archivedWithActiveTasks.isEmpty()) return

        archivedWithActiveTasks.forEach { projectDao.unarchiveById(it.id) }
    }

    private fun getOrCreateProjectByName(name: String): Pair<Long, ProjectLookupResult> {
        val existing = projectDao.getByNameCaseInsensitive(name)
        if (existing == null) {
            return projectDao.insert(ProjectEntity(name = name)) to ProjectLookupResult.CREATED
        }

        return if (existing.isArchived) {
            projectDao.unarchiveById(existing.id)
            existing.id to ProjectLookupResult.RESTORED
        } else {
            existing.id to ProjectLookupResult.OPENED
        }
    }

    // -----------------------
    // Key handling
    // -----------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (isTextInputActive) return super.dispatchKeyEvent(event)
        if (isHelpVisible) {
            val ch = event.unicodeChar.takeIf { it != 0 }?.toChar()?.lowercaseChar()
            if (ch == 'h' || event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                toggleHelpOverlay(false)
            }
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
            // If there's a number buffer, confirm the selection first
            if (numberBuffer.isNotEmpty() && isSelectableListScreen(screen)) {
                val num = numberBuffer.toIntOrNull()
                val maxSlot = currentPageItemCount()
                if (num != null && num >= 1 && num <= maxSlot) {
                    selectedSlot = num - 1
                    lastKey = "selected: $num"
                } else {
                    lastKey = "select: $numberBuffer (out of range)"
                }
                numberBuffer = ""
                render()
                return true
            }
            when (screen) {
                Screen.INBOX, Screen.SOMEDAY, Screen.NEXT, Screen.WAITING -> { openSelectedForProcess(); return true }
                Screen.PROJECTS -> { openSelectedProject(); return true }
                Screen.PROJECT_PICKER -> { commitMovePendingItemToSelectedProject(); return true }
                Screen.PROJECT_DETAIL -> { lastKey = "enter (action noop)"; render(); return true }
                Screen.PROCESS -> { lastKey = "enter (process)"; render(); return true }
                Screen.IMPORT_INBOX -> { importCheckedInboxItems(); return true }
                Screen.REVIEW -> { lastKey = "enter (review noop)"; render(); return true }
                Screen.LOG -> { lastKey = "enter (log noop)"; render(); return true }
                Screen.SETTINGS -> { lastKey = "enter (settings noop)"; render(); return true }
            }
        }

        val uni = event.unicodeChar
        if (uni == 0) return super.dispatchKeyEvent(event)
        val ch = uni.toChar()

        // Digits accumulate in buffer on selectable screens, Enter confirms
        if (isSelectableListScreen(screen) && ch.isDigit()) {
            numberBuffer += ch
            lastKey = "select: $numberBuffer"
            render()
            return true
        }

        val c = ch.lowercaseChar()

        // Any non-digit key clears the number buffer
        if (numberBuffer.isNotEmpty()) {
            numberBuffer = ""
        }

        // SETTINGS keys
        if (screen == Screen.SETTINGS) {
            when (c) {
                'w' -> {
                    backgroundMode = if (backgroundMode == "BLACK") "WHITE" else "BLACK"
                    lastKey = "settings: theme toggled"
                    saveSettings()
                    render()
                    return true
                }
                'p' -> {
                    openTextDialog("Page size", "e.g. 18") { raw ->
                        val v = raw.trim().toIntOrNull()
                        if (v == null) {
                            lastKey = "settings: bad pagesize"
                        } else {
                            pageSize = v.coerceIn(5, 99)
                            saveSettings()
                            lastKey = "settings: pagesize=$pageSize"
                        }
                        render()
                    }
                    return true
                }
                'o' -> {
                    openTextDialog("Log page size", "e.g. 9") { raw ->
                        val v = raw.trim().toIntOrNull()
                        if (v == null) {
                            lastKey = "settings: bad pagesizelog"
                        } else {
                            pageSizeLog = v.coerceIn(3, 99)
                            saveSettings()
                            lastKey = "settings: pagesizelog=$pageSizeLog"
                        }
                        render()
                    }
                    return true
                }
                'i' -> { pickImportInboxFolder(); return true }
                'r' -> { restoreDefaultSettings(); return true }
                'b' -> { goTo(Screen.INBOX, "b (back→inbox)"); return true }
            }
        }

        // IMPORT keys
        if (screen == Screen.IMPORT_INBOX) {
            when (c) {
                'x' -> { toggleSelectedImportItem(); return true }
                'i' -> { pickImportInboxFolder(); return true }
                'r' -> { openImportInbox(); return true }
                'q', 'b' -> { cancelImportInbox(); return true }
            }
        }

        // PROJECT PICKER keys
        if (screen == Screen.PROJECT_PICKER) {
            when (c) {
                'b' -> {
                    screen = Screen.PROCESS
                    page = 0
                    selectedSlot = null
                    lastKey = "b (back→process)"
                    render()
                    return true
                }
                'c' -> { createProjectFromPendingAndMove(); return true }
                'z' -> { changePage(-1); return true }
                'x' -> { changePage(1); return true }
            }
        }

        // PROCESS keys
        if (screen == Screen.PROCESS) {
            when (c) {
                'd' -> { moveProcessedItemToList(ListId.NEXT, "moved → next"); return true }
                'f' -> { moveProcessedItemToList(ListId.WAITING, "moved → waiting"); return true }
                's' -> { moveProcessedItemToList(ListId.SOMEDAY, "moved → someday"); return true }
                't' -> { trashProcessedItem(); return true }
                'p' -> { openProjectPickerForProcessedItem(); return true }
                'g' -> { convertProcessedItemToProject(); return true }
                'e' -> { editProcessedItemTitle(); return true }
                'u' -> { editDueForProcessedItem(); return true }
                'b' -> { backFromProcess(); return true }
            }
        }

        // PROJECT DETAIL keys
        if (screen == Screen.PROJECT_DETAIL) {
            when (c) {
                'c' -> { openTextDialog("Add action", "next action") { addActionToOpenProject(it) }; return true }
                't' -> { deleteSelectedActionFromOpenProject(); return true }
                'e' -> { editProjectDetailSelection(); return true }
                'u' -> { editDueForSelectedProjectAction(); return true }
                'b' -> { goTo(Screen.PROJECTS, "b (back→projects)"); return true }
                'z' -> { changePage(-1); return true }
                'x' -> { changePage(1); return true }
            }
        }

        // PROJECTS list keys
        if (screen == Screen.PROJECTS) {
            when (c) {
                'c' -> { openTextDialog("New project", "project name") { addProject(it) }; return true }
                'e' -> { editSelectedProjectName(); return true }
                't' -> { archiveSelectedProject(); return true }
                'b' -> { goTo(Screen.INBOX, "b (back→inbox)"); return true }
                'z' -> { changePage(-1); return true }
                'x' -> { changePage(1); return true }
            }
        }

        // PROJECT DETAIL: move selected action to a list
        if (screen == Screen.PROJECT_DETAIL) {
            when (c) {
                'd' -> { moveSelectedProjectActionToList(ListId.NEXT, "moved → next"); return true }
                'f' -> { moveSelectedProjectActionToList(ListId.WAITING, "moved → waiting"); return true }
                's' -> { moveSelectedProjectActionToList(ListId.SOMEDAY, "moved → someday"); return true }
                'a' -> { moveSelectedProjectActionToList(ListId.INBOX, "moved → inbox"); return true }
            }
        }

        // REVIEW keys
        if (screen == Screen.REVIEW) {
            when (c) {
                'i' -> {
                    openTextDialog("Add trigger", "e.g. kids, car, health") { text ->
                        val trimmed = text.trim()
                        if (trimmed.isNotEmpty()) {
                            reviewItemDao.insert(ReviewItemEntity(title = trimmed))
                            lastKey = "trigger added: $trimmed"
                        }
                        render()
                    }
                    return true
                }
                'c' -> {
                    openTextDialog("Capture", "to inbox") { text ->
                        val trimmed = text.trim()
                        if (trimmed.isNotEmpty()) {
                            taskDao.insert(TaskEntity(title = trimmed, listType = "INBOX"))
                            lastKey = "captured: $trimmed"
                        }
                        render()
                    }
                    return true
                }
                't' -> {
                    val items = reviewItemDao.getAll()
                    val slot = selectedSlot
                    val index = if (slot != null) page * pageSize + slot else -1
                    if (index in items.indices) {
                        val item = items[index]
                        reviewItemDao.deleteById(item.id)
                        selectedSlot = null
                        lastKey = "trigger removed: ${item.title}"
                    } else {
                        lastKey = "t (no selection)"
                    }
                    render()
                    return true
                }
                'z' -> { changePage(-1); return true }
                'x' -> { changePage(1); return true }
            }
        }

        // List screens: direct move if item selected, else fall through to global nav
        if (screen == Screen.INBOX || screen == Screen.SOMEDAY || screen == Screen.NEXT || screen == Screen.WAITING) {
            when (c) {
                'e' -> { editSelectedListItemTitle(); return true }
                'u' -> { editDueForSelectedListItem(); return true }
                'd', 'f', 's', 'a' -> {
                    if (selectedSlot != null) {
                        val dest = when (c) {
                            'd' -> ListId.NEXT
                            'f' -> ListId.WAITING
                            's' -> ListId.SOMEDAY
                            else -> ListId.INBOX
                        }
                        moveSelectedListItemTo(dest)
                        return true
                    }
                    // no selection → fall through to global nav
                }
            }
        }

        // Global keys (views + capture + paging + back)
        when (c) {
            'h' -> { toggleHelpOverlay(); return true }
            'a' -> { goTo(Screen.INBOX, "a (inbox)"); return true }
            'd' -> { goTo(Screen.NEXT, "d (next)"); return true }
            'f' -> { goTo(Screen.WAITING, "f (waiting)"); return true }
            's' -> { goTo(Screen.SOMEDAY, "s (someday)"); return true }
            'g' -> { goTo(Screen.PROJECTS, "g (projects)"); return true }
            'r' -> { goTo(Screen.REVIEW, "r (review)"); return true }
            'l' -> { goTo(Screen.LOG, "l (log)"); return true }
            'q' -> { goTo(Screen.SETTINGS, "q (settings)"); return true }
            'i' -> { openImportInbox(); return true }

            'c' -> {
                if (screen != Screen.PROJECT_DETAIL && screen != Screen.PROJECTS && screen != Screen.PROJECT_PICKER && screen != Screen.SETTINGS && screen != Screen.REVIEW) {
                    openTextDialog("Capture", "to inbox") { captureToInbox(it) }
                    return true
                }
            }

            'z' -> { changePage(-1); return true }
            'x' -> { changePage(1); return true }

            'b' -> {
                when (screen) {
                    Screen.PROJECT_DETAIL -> goTo(Screen.PROJECTS, "b (back→projects)")
                    Screen.PROJECTS -> goTo(Screen.INBOX, "b (back→inbox)")
                    Screen.PROJECT_PICKER -> {
                        screen = Screen.PROCESS
                        page = 0
                        selectedSlot = null
                        lastKey = "b (back→process)"
                        render()
                    }
                    Screen.SETTINGS -> goTo(Screen.INBOX, "b (back→inbox)")
                    else -> goTo(Screen.INBOX, "b (back→inbox)")
                }
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    // --- Helpers: screen/list types ---

    private fun changePage(delta: Int) {
        page += delta
        selectedSlot = null
        numberBuffer = ""
        lastKey = if (delta < 0) "z (prev page)" else "x (next page)"
        render()
    }

    private fun isSelectableListScreen(s: Screen): Boolean {
        return s == Screen.INBOX || s == Screen.SOMEDAY || s == Screen.NEXT || s == Screen.WAITING ||
                s == Screen.PROJECTS || s == Screen.PROJECT_DETAIL || s == Screen.PROJECT_PICKER ||
                s == Screen.REVIEW || s == Screen.LOG || s == Screen.IMPORT_INBOX
    }

    private fun currentPageItemCount(): Int {
        return when (screen) {
            Screen.INBOX, Screen.SOMEDAY, Screen.NEXT, Screen.WAITING -> {
                val listType = when (screen) {
                    Screen.INBOX -> "INBOX"; Screen.SOMEDAY -> "SOMEDAY"
                    Screen.NEXT -> "NEXT"; Screen.WAITING -> "WAITING"
                    else -> "INBOX"
                }
                val tasks = taskDao.getActiveForList(listType)
                minOf(pageSize, tasks.size - page * pageSize).coerceAtLeast(0)
            }
            Screen.PROJECTS -> {
                val projects = projectDao.getActiveProjects()
                minOf(pageSize, projects.size - page * pageSize).coerceAtLeast(0)
            }
            Screen.PROJECT_DETAIL -> {
                val pid = openProjectId ?: return 0
                val actions = taskDao.getActiveForProject(pid)
                minOf(pageSize, actions.size - page * pageSize).coerceAtLeast(0)
            }
            Screen.PROJECT_PICKER -> {
                val active = projectDao.getActiveProjects().takeLast(maxPickerProjects)
                minOf(pageSize, active.size - page * pageSize).coerceAtLeast(0)
            }
            Screen.REVIEW -> {
                val items = reviewItemDao.getAll()
                minOf(pageSize, items.size - page * pageSize).coerceAtLeast(0)
            }
            Screen.IMPORT_INBOX -> importItems.size
            else -> 0
        }
    }

    // --- Direct move from list screens ---

    private fun moveSelectedListItemTo(dest: ListId) {
        val listId = screenToListId(screen) ?: return
        val slot = selectedSlot ?: run {
            lastKey = "move (no selection)"
            render()
            return
        }
        val tasks = taskDao.getActiveForList(listIdToDbListType(listId))
        val index = page * pageSize + slot
        if (index !in tasks.indices) {
            lastKey = "move (out of range)"
            render()
            return
        }
        val t = tasks[index]
        taskDao.moveToList(t.id, listIdToDbListType(dest))
        selectedSlot = null
        lastKey = "moved → ${dest.name.lowercase()}"
        render()
    }

    // --- Direct move from project detail ---

    private fun moveSelectedProjectActionToList(dest: ListId, note: String) {
        val pid = openProjectId ?: run {
            lastKey = "move (no project)"
            render()
            return
        }
        val slot = selectedSlot ?: run {
            lastKey = "move (no selection)"
            render()
            return
        }
        val actions = taskDao.getActiveForProject(pid)
        val index = page * pageSize + slot
        if (index !in actions.indices) {
            lastKey = "move (out of range)"
            render()
            return
        }
        val t = actions[index]
        taskDao.moveToList(t.id, listIdToDbListType(dest))
        selectedSlot = null
        lastKey = note
        render()
    }

    private fun screenToListId(s: Screen): ListId? = when (s) {
        Screen.INBOX -> ListId.INBOX
        Screen.SOMEDAY -> ListId.SOMEDAY
        Screen.NEXT -> ListId.NEXT
        Screen.WAITING -> ListId.WAITING
        else -> null
    }

    private fun listIdToDbListType(id: ListId): String = when (id) {
        ListId.INBOX -> "INBOX"
        ListId.SOMEDAY -> "SOMEDAY"
        ListId.NEXT -> "NEXT"
        ListId.WAITING -> "WAITING"
    }

    private fun goTo(s: Screen, note: String) {
        screen = s
        lastKey = note
        page = 0
        selectedSlot = null
        numberBuffer = ""
        render()
    }

    // --- Dialog (text input) ---

    private fun openTextDialog(
        title: String,
        hint: String,
        initialValue: String = "",
        onOk: (String) -> Unit
    ) {
        isTextInputActive = true
        val input = EditText(this).apply {
            this.hint = hint
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            typeface = workSansRegular
            setText(initialValue)
            setSelection(text.length)
            requestFocus()
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    val text = text?.toString()?.trim().orEmpty()
                    if (text.isNotEmpty()) onOk(text)
                    closeDialog()
                    true
                } else false
            }
        }
        dialogEdit = input
        dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val text = dialogEdit?.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) onOk(text)
                closeDialog()
            }
            .setNegativeButton("Cancel") { _, _ -> closeDialog() }
            .setOnCancelListener { closeDialog() }
            .create()
        dialog?.show()
    }

    private fun closeDialog() {
        dialog?.dismiss()
        dialog = null
        dialogEdit = null
        isTextInputActive = false
        render()
    }

    // --- Due dialog + Calendar insert ---

    private fun formatDue(ms: Long): String = dueFmt.format(Date(ms))

    private fun openDueEventDialog(
        task: TaskEntity,
        calendars: List<CalendarTarget>,
        permissionMissing: Boolean
    ) {
        isTextInputActive = true

        val canWriteDirectly = calendars.isNotEmpty()
        val prefill = buildDueEventPrefill(task, calendars)
        val dp = resources.displayMetrics.density
        fun dpInt(value: Float): Int = (value * dp).toInt()

        val content = android.widget.ScrollView(this).apply {
            isFillViewport = true
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(20f), dpInt(14f), dpInt(20f), dpInt(10f))
        }
        content.addView(
            form,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        fun labelView(label: String): TextView = TextView(this).apply {
            text = label
            textSize = 11f
            typeface = workSansMedium
        }

        fun noteView(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = workSansRegular
            setPadding(dpInt(8f), dpInt(4f), dpInt(8f), dpInt(8f))
        }

        fun newField(
            value: String,
            hint: String = "",
            multiLine: Boolean = false,
            lines: Int = 1
        ): EditText {
            return EditText(this).apply {
                setText(value)
                this.hint = hint
                inputType = if (multiLine) {
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
                typeface = workSansRegular
                textSize = 12f
                setPadding(dpInt(12f), dpInt(8f), dpInt(12f), dpInt(8f))
                if (multiLine) {
                    minLines = lines
                    maxLines = lines
                    isVerticalScrollBarEnabled = true
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    setHorizontallyScrolling(false)
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                } else {
                    setSingleLine(true)
                }
            }
        }

        fun addField(
            label: String,
            value: String,
            hint: String = "",
            multiLine: Boolean = false,
            lines: Int = 1
        ): EditText {
            form.addView(labelView(label))
            val field = newField(value, hint, multiLine, lines)
            form.addView(
                field,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpInt(4f) }
            )
            return field
        }

        fun addTwoColumnFields(
            leftLabel: String,
            leftValue: String,
            leftHint: String,
            rightLabel: String,
            rightValue: String,
            rightHint: String
        ): Pair<EditText, EditText> {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val leftBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val rightBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dpInt(12f)
                }
            }

            leftBox.addView(labelView(leftLabel))
            val leftField = newField(leftValue, leftHint)
            leftBox.addView(
                leftField,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            rightBox.addView(labelView(rightLabel))
            val rightField = newField(rightValue, rightHint)
            rightBox.addView(
                rightField,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            row.addView(leftBox)
            row.addView(rightBox)
            form.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpInt(4f) }
            )

            return leftField to rightField
        }

        fun makePickerField(field: EditText) {
            field.inputType = InputType.TYPE_NULL
            field.isFocusable = false
            field.isFocusableInTouchMode = false
            field.isClickable = true
            field.isCursorVisible = false
            field.keyListener = null
        }

        val calendarSpinner: Spinner? = if (canWriteDirectly) {
            form.addView(labelView("Calendar"))
            Spinner(this).also { spinner ->
                val labels = calendars.map { "${it.name} - ${it.accountName}" }
                spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                val targetCalendarId = prefill.calendarId ?: calendars.first().id
                val selectedCalendarIndex = calendars.indexOfFirst { it.id == targetCalendarId }
                    .let { if (it >= 0) it else 0 }
                spinner.setSelection(selectedCalendarIndex)
                form.addView(
                    spinner,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dpInt(6f) }
                )
            }
        } else {
            val note = if (permissionMissing) {
                "Calendar permission missing. Save will open your calendar app. Reminder may need to be set there."
            } else {
                "No writable calendars found. Save will open your calendar app. Reminder may need to be set there."
            }
            form.addView(
                noteView(note),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpInt(6f) }
            )
            null
        }

        val titleField = addField("Title", prefill.title, "event title")
        val (startDateField, endDateField) = addTwoColumnFields(
            leftLabel = "Start date",
            leftValue = prefill.startDateYYMMDD,
            leftHint = "YYMMDD",
            rightLabel = "End date",
            rightValue = prefill.endDateYYMMDD,
            rightHint = "YYMMDD"
        )

        var endDateAutoFromStart = true
        listOf(startDateField, endDateField).forEach { field ->
            makePickerField(field)
        }
        endDateField.setOnClickListener {
            showDatePickerForField(endDateField, task.dueMillis) {
                endDateAutoFromStart = false
            }
        }

        val (startTimeField, endTimeField) = addTwoColumnFields(
            leftLabel = "Start time",
            leftValue = prefill.startTimeHHMM,
            leftHint = "HHMM",
            rightLabel = "End time",
            rightValue = prefill.endTimeHHMM,
            rightHint = "HHMM"
        )

        startDateField.setOnClickListener {
            showDatePickerForField(startDateField, task.dueMillis) {
                val startDate = parseYYMMDD(startDateField.text.toString()) ?: return@showDatePickerForField
                val oldEndDate = parseYYMMDD(endDateField.text.toString())
                if (endDateAutoFromStart || (oldEndDate != null && oldEndDate.before(startDate))) {
                    val start = parseHHMM(startTimeField.text.toString(), startDate)
                    val end = parseHHMM(endTimeField.text.toString(), startDate) ?: startDate
                    if (start != null && end.before(start)) end.add(Calendar.DAY_OF_MONTH, 1)
                    endDateField.setText(formatYYMMDD(end.timeInMillis))
                }
            }
        }

        var endTimeAutoFromStart = true
        listOf(startTimeField, endTimeField).forEach { field ->
            makePickerField(field)
        }

        startTimeField.setOnClickListener {
            val (hour, minute) = parseHHMMPartsOrDefault(startTimeField.text?.toString().orEmpty())
            TimePickerDialog(
                this,
                { _, pickedHour, pickedMinute ->
                    startTimeField.setText(String.format(Locale.US, "%02d%02d", pickedHour, pickedMinute))
                    if (endTimeAutoFromStart) {
                        setFieldFromFieldPlusMinutes(
                            startDateField, startTimeField,
                            if (endDateAutoFromStart) endDateField else null,
                            endTimeField, 60
                        )
                    }
                },
                hour,
                minute,
                true
            ).show()
        }

        endTimeField.setOnClickListener {
            val (hour, minute) = parseHHMMPartsOrDefault(endTimeField.text?.toString().orEmpty())
            TimePickerDialog(
                this,
                { _, pickedHour, pickedMinute ->
                    endTimeField.setText(String.format(Locale.US, "%02d%02d", pickedHour, pickedMinute))
                    endTimeAutoFromStart = false
                },
                hour,
                minute,
                true
            ).show()
        }

        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun quickButton(label: String, minutes: Int?) {
            quickRow.addView(
                android.widget.Button(this).apply {
                    text = label
                    textSize = 11f
                    typeface = workSansRegular
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = dpInt(36f)
                    minimumHeight = dpInt(36f)
                    isAllCaps = false
                    setPadding(dpInt(6f), dpInt(6f), dpInt(6f), dpInt(6f))
                    setOnClickListener {
                        if (minutes == null) {
                            endDateField.setText(startDateField.text)
                            endTimeField.setText(startTimeField.text?.toString().orEmpty())
                        } else {
                            setFieldFromFieldPlusMinutes(startDateField, startTimeField, endDateField, endTimeField, minutes)
                        }
                        endDateAutoFromStart = true
                        endTimeAutoFromStart = false
                    }
                },
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    if (quickRow.childCount > 0) marginStart = dpInt(4f)
                }
            )
        }
        quickButton("=start", null)
        quickButton("+15m", 15)
        quickButton("+30m", 30)
        quickButton("+45m", 45)
        quickButton("+1h", 60)
        form.addView(
            quickRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(6f) }
        )

        form.addView(labelView("Reminder"))
        val reminderChoices = dueReminderOptions().map { it.first }
        val reminderSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, reminderChoices).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val reminderIndex = reminderChoices.indexOf(prefill.reminderLabel).let { if (it >= 0) it else 0 }
            setSelection(reminderIndex)
        }
        form.addView(
            reminderSpinner,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(4f) }
        )
        if (!canWriteDirectly) {
            form.addView(
                noteView("Reminder choice is only applied automatically when miniGTD can save directly to a writable calendar."),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpInt(6f) }
            )
        }

        val locationField = addField("Location", prefill.location, "optional")
        val descriptionField = addField("Description", prefill.description, "optional", multiLine = true, lines = 3)

        dialogEdit = titleField
        dialog = AlertDialog.Builder(this)
            .setTitle("Set due + calendar")
            .setView(content)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel") { _, _ -> closeDialog() }
            .setOnCancelListener { closeDialog() }
            .create()

        dialog?.show()
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val title = titleField.text?.toString()?.trim().orEmpty()
            if (title.isBlank()) {
                Toast.makeText(this, "Title required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val startDate = parseYYMMDD(startDateField.text?.toString().orEmpty())
            if (startDate == null) {
                Toast.makeText(this, "Bad start date (YYMMDD)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val endDate = parseYYMMDD(endDateField.text?.toString().orEmpty())
            if (endDate == null) {
                Toast.makeText(this, "Bad end date (YYMMDD)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val startFull = parseHHMM(startTimeField.text?.toString().orEmpty(), startDate)
            if (startFull == null) {
                Toast.makeText(this, "Bad start time (HHMM)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val endFull = parseHHMM(endTimeField.text?.toString().orEmpty(), endDate)
            if (endFull == null) {
                Toast.makeText(this, "Bad end time (HHMM)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (endFull.timeInMillis <= startFull.timeInMillis) {
                Toast.makeText(this, "End must be after start", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val startMillis = startFull.timeInMillis
            val endMillis = endFull.timeInMillis
            val location = locationField.text?.toString()?.trim().orEmpty()
            val description = descriptionField.text?.toString()?.trim().orEmpty()
            val reminderMinutes = dueReminderLabelToMinutes(reminderSpinner.selectedItem?.toString().orEmpty())
            saveDefaultDueReminderMinutes(reminderMinutes)

            taskDao.setDue(task.id, startMillis)
            if (processTaskId == task.id) {
                processDueMillis = startMillis
            }

            val selectedCalendar = calendarSpinner
                ?.let { calendars.getOrNull(it.selectedItemPosition) }
                ?.also { saveDefaultCalendarId(it.id) }

            val insertResult = if (selectedCalendar != null) {
                insertTimedCalendarEvent(
                    calendarId = selectedCalendar.id,
                    title = title,
                    startMillis = startMillis,
                    endMillis = endMillis,
                    location = location,
                    description = description,
                    reminderMinutes = reminderMinutes
                )
            } else {
                CalendarInsertResult(
                    eventSaved = false,
                    reminderSaved = reminderMinutes == null
                )
            }
            val directSaved = insertResult.eventSaved

            val fallbackOpened = if (!directSaved) {
                openCalendarInsert(
                    title = title,
                    startMillis = startMillis,
                    endMillis = endMillis,
                    location = location,
                    description = description
                )
            } else {
                false
            }

            lastKey = when {
                directSaved && reminderMinutes != null && insertResult.reminderSaved -> "due set + calendar + reminder saved"
                directSaved && reminderMinutes != null -> "due set + calendar saved (check reminder)"
                directSaved -> "due set + calendar saved"
                fallbackOpened && reminderMinutes != null -> "due set + calendar opened (set reminder there)"
                fallbackOpened -> "due set + calendar opened"
                permissionMissing -> "due set (calendar permission missing)"
                !canWriteDirectly -> "due set (no writable calendar)"
                else -> "due set (calendar save failed)"
            }

            closeDialog()
        }
    }

    private fun openCalendarInsert(
        title: String,
        startMillis: Long,
        endMillis: Long,
        location: String,
        description: String
    ): Boolean {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            startActivity(Intent.createChooser(intent, "Calendar"))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    // --- Capture ---

    private fun captureToInbox(text: String) {
        taskDao.insert(
            TaskEntity(
                title = text,
                listType = "INBOX",
                projectId = null,
                dueMillis = null
            )
        )
        screen = Screen.INBOX
        page = 0
        selectedSlot = null
        lastKey = "captured: $text"
        render()
    }

    // --- Process ---

    private fun openSelectedForProcess() {
        val listId = screenToListId(screen) ?: run {
            lastKey = "enter (no list)"
            render()
            return
        }

        val slot = selectedSlot ?: run {
            lastKey = "enter (no selection)"
            render()
            return
        }

        val tasks = taskDao.getActiveForList(listIdToDbListType(listId))
        val index = page * pageSize + slot
        if (index !in tasks.indices) {
            lastKey = "enter (out of range)"
            render()
            return
        }

        val t = tasks[index]
        processFrom = listId
        processTaskId = t.id
        processText = t.title
        processDueMillis = t.dueMillis

        screen = Screen.PROCESS
        lastKey = "process open"
        render()
    }

    private fun backFromProcess() {
        screen = when (processFrom) {
            ListId.INBOX -> Screen.INBOX
            ListId.SOMEDAY -> Screen.SOMEDAY
            ListId.NEXT -> Screen.NEXT
            ListId.WAITING -> Screen.WAITING
            else -> Screen.INBOX
        }
        lastKey = "b (back)"
        processFrom = null
        processTaskId = null
        processText = ""
        processDueMillis = null
        render()
    }

    private fun moveProcessedItemToList(dest: ListId, note: String) {
        val id = processTaskId ?: return
        taskDao.moveToList(id, listIdToDbListType(dest))

        val returnScreen = when (processFrom) {
            ListId.INBOX -> Screen.INBOX
            ListId.SOMEDAY -> Screen.SOMEDAY
            ListId.NEXT -> Screen.NEXT
            ListId.WAITING -> Screen.WAITING
            else -> Screen.INBOX
        }

        processFrom = null
        processTaskId = null
        processText = ""
        processDueMillis = null

        screen = returnScreen
        page = 0
        selectedSlot = null
        numberBuffer = ""
        lastKey = note
        render()
    }

    private fun trashProcessedItem() {
        val id = processTaskId ?: return
        val from = processFrom

        taskDao.markDone(id)

        logDone(
            text = processText,
            origin = from?.name?.lowercase() ?: "unknown",
            project = null
        )

        val returnScreen = when (from) {
            ListId.INBOX -> Screen.INBOX
            ListId.SOMEDAY -> Screen.SOMEDAY
            ListId.NEXT -> Screen.NEXT
            ListId.WAITING -> Screen.WAITING
            else -> Screen.INBOX
        }

        processFrom = null
        processTaskId = null
        processText = ""
        processDueMillis = null

        screen = returnScreen
        page = 0
        selectedSlot = null
        numberBuffer = ""
        lastKey = "done"
        render()
    }

    private fun convertProcessedItemToProject() {
        val id = processTaskId ?: return

        val name = processText.trim()
        if (name.isEmpty()) {
            lastKey = "project (empty)"
            render()
            return
        }

        taskDao.deleteById(id)

        val (projectId, lookupResult) = getOrCreateProjectByName(name)

        openProjectId = projectId
        screen = Screen.PROJECT_DETAIL
        page = 0
        selectedSlot = null
        lastKey = when (lookupResult) {
            ProjectLookupResult.CREATED -> "project created"
            ProjectLookupResult.OPENED -> "project open"
            ProjectLookupResult.RESTORED -> "project restored"
        }

        processFrom = null
        processTaskId = null
        processText = ""
        processDueMillis = null
        render()
    }

    private fun editDueForProcessedItem() {
        val id = processTaskId ?: return
        openDueEditorForTask(id)
    }

    private fun renameTaskById(
        taskId: Long,
        dialogTitle: String = "Edit task",
        hint: String = "task name",
        successNote: String = "task renamed",
        onRenamed: ((String) -> Unit)? = null
    ) {
        val task = taskDao.getById(taskId) ?: run {
            lastKey = "e (missing task)"
            render()
            return
        }

        openTextDialog(dialogTitle, hint, initialValue = task.title) { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                lastKey = "edit (empty ignored)"
                render()
                return@openTextDialog
            }
            if (trimmed == task.title) {
                lastKey = "edit unchanged"
                render()
                return@openTextDialog
            }

            taskDao.update(task.copy(title = trimmed))
            onRenamed?.invoke(trimmed)
            lastKey = successNote
            render()
        }
    }

    private fun renameProjectById(
        projectId: Long,
        dialogTitle: String = "Edit project",
        hint: String = "project name",
        successNote: String = "project renamed",
        onRenamed: ((String) -> Unit)? = null
    ) {
        val project = projectDao.getById(projectId) ?: run {
            lastKey = "e (missing project)"
            render()
            return
        }

        openTextDialog(dialogTitle, hint, initialValue = project.name) { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                lastKey = "edit (empty ignored)"
                render()
                return@openTextDialog
            }
            if (trimmed == project.name) {
                lastKey = "edit unchanged"
                render()
                return@openTextDialog
            }

            val existing = projectDao.getByNameCaseInsensitive(trimmed)
            if (existing != null && existing.id != project.id) {
                lastKey = "project exists"
                render()
                return@openTextDialog
            }

            projectDao.update(project.copy(name = trimmed))
            onRenamed?.invoke(trimmed)
            lastKey = successNote
            render()
        }
    }

    private fun editProcessedItemTitle() {
        val id = processTaskId ?: return
        renameTaskById(id, onRenamed = { processText = it })
    }

    // --- Due from list screens (direct) ---

    private fun editSelectedListItemTitle() {
        val listId = screenToListId(screen) ?: return
        val slot = selectedSlot ?: run {
            lastKey = "e (no selection)"
            render()
            return
        }

        val tasks = taskDao.getActiveForList(listIdToDbListType(listId))
        val index = page * pageSize + slot
        if (index !in tasks.indices) {
            lastKey = "e (out of range)"
            render()
            return
        }

        val t = tasks[index]
        renameTaskById(t.id)
    }

    private fun editDueForSelectedListItem() {
        val listId = screenToListId(screen) ?: return
        val slot = selectedSlot ?: run {
            lastKey = "u (no selection)"
            render()
            return
        }

        val tasks = taskDao.getActiveForList(listIdToDbListType(listId))
        val index = page * pageSize + slot
        if (index !in tasks.indices) {
            lastKey = "u (out of range)"
            render()
            return
        }

        val t = tasks[index]
        openDueEditorForTask(t.id)
    }

    // --- Project Picker (from PROCESS) ---

    private fun openProjectPickerForProcessedItem() {
        val id = processTaskId ?: run {
            lastKey = "p (no task)"
            render()
            return
        }

        val projects = projectDao.getActiveProjects()
        if (projects.isEmpty()) {
            lastKey = "p (no projects)"
            render()
            return
        }

        val t = taskDao.getById(id)
        if (t == null) {
            lastKey = "p (missing task)"
            render()
            return
        }

        pendingTaskId = id
        pendingTaskTitle = t.title.trim()
        pendingFrom = processFrom

        screen = Screen.PROJECT_PICKER
        page = 0
        selectedSlot = null
        lastKey = "pick project"
        render()
    }

    private fun commitMovePendingItemToSelectedProject() {
        val taskId = pendingTaskId ?: run {
            lastKey = "enter (no pending)"
            render()
            return
        }

        val slot = selectedSlot ?: run {
            lastKey = "enter (no selection)"
            render()
            return
        }

        val all = projectDao.getActiveProjects()
        val active = all.takeLast(maxPickerProjects)

        val totalPages = if (active.isEmpty()) 1 else ((active.size - 1) / pageSize + 1)
        val p = page.coerceIn(0, totalPages - 1)

        val indexInActive = p * pageSize + slot
        if (indexInActive !in active.indices) {
            lastKey = "enter (out of range)"
            render()
            return
        }

        val project = active[indexInActive]
        val title = pendingTaskTitle.trim()

        if (title.isEmpty()) {
            lastKey = "moved (empty ignored)"
            cleanupPending()
            screen = Screen.INBOX
            render()
            return
        }

        taskDao.moveToProject(taskId, project.id)

        processFrom = null
        processTaskId = null
        processText = ""
        processDueMillis = null
        cleanupPending()

        screen = Screen.INBOX
        page = 0
        selectedSlot = null
        lastKey = "moved → project: ${project.name}"
        render()
    }

    private fun createProjectFromPendingAndMove() {
        val taskId = pendingTaskId ?: run {
            lastKey = "c (no pending)"
            render()
            return
        }

        val text = pendingTaskTitle.trim()
        if (text.isEmpty()) {
            lastKey = "c (empty ignored)"
            cleanupPending()
            screen = Screen.INBOX
            render()
            return
        }

        val (projectId, lookupResult) = getOrCreateProjectByName(text)

        taskDao.moveToProject(taskId, projectId)

        openProjectId = projectId
        screen = Screen.PROJECT_DETAIL
        page = 0
        selectedSlot = null

        processFrom = null
        processTaskId = null
        processText = ""
        processDueMillis = null
        cleanupPending()

        val p = projectDao.getById(projectId)
        lastKey = when (lookupResult) {
            ProjectLookupResult.CREATED -> "created project: ${p?.name ?: text}"
            ProjectLookupResult.OPENED -> "project open"
            ProjectLookupResult.RESTORED -> "project restored: ${p?.name ?: text}"
        }
        render()
    }

    private fun cleanupPending() {
        pendingTaskId = null
        pendingTaskTitle = ""
        pendingFrom = null
    }

    // --- Projects ---

    private fun addProject(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return

        val (id, lookupResult) = getOrCreateProjectByName(trimmed)

        openProjectId = id
        screen = Screen.PROJECT_DETAIL
        lastKey = when (lookupResult) {
            ProjectLookupResult.CREATED -> "project created"
            ProjectLookupResult.OPENED -> "project open"
            ProjectLookupResult.RESTORED -> "project restored"
        }
        page = 0
        selectedSlot = null
        render()
    }

    private fun openSelectedProject() {
        val slot = selectedSlot ?: run {
            lastKey = "enter (no selection)"
            render()
            return
        }

        val projects = projectDao.getActiveProjects()
        val index = page * pageSize + slot
        if (index !in projects.indices) {
            lastKey = "enter (out of range)"
            render()
            return
        }

        openProjectId = projects[index].id
        screen = Screen.PROJECT_DETAIL
        page = 0
        selectedSlot = null
        lastKey = "project open"
        render()
    }

    private fun editSelectedProjectName() {
        val slot = selectedSlot ?: run {
            lastKey = "e (no selection)"
            render()
            return
        }

        val projects = projectDao.getActiveProjects()
        val index = page * pageSize + slot
        if (index !in projects.indices) {
            lastKey = "e (out of range)"
            render()
            return
        }

        renameProjectById(projects[index].id)
    }

    private fun archiveSelectedProject() {
        val slot = selectedSlot ?: run {
            lastKey = "t (no selection)"
            render()
            return
        }

        val projects = projectDao.getActiveProjects()
        val index = page * pageSize + slot
        if (index !in projects.indices) {
            lastKey = "t (out of range)"
            render()
            return
        }

        val p = projects[index]
        val activeActionCount = taskDao.getActiveForProject(p.id).size
        if (activeActionCount > 0) {
            lastKey = "project has $activeActionCount open action${if (activeActionCount == 1) "" else "s"}"
            render()
            return
        }

        projectDao.archiveById(p.id)

        logDone(p.name, origin = "project", project = p.name)
        lastKey = "Done: ${p.name}"
        selectedSlot = null
        render()
    }

    private fun addActionToOpenProject(action: String) {
        val pid = openProjectId ?: return
        val text = action.trim()
        if (text.isEmpty()) return

        taskDao.insert(
            TaskEntity(
                title = text,
                listType = "PROJECT",
                projectId = pid,
                dueMillis = null
            )
        )
        lastKey = "action added"
        render()
    }

    private fun deleteSelectedActionFromOpenProject() {
        val pid = openProjectId ?: run {
            lastKey = "t (no project)"
            render()
            return
        }

        val actions = taskDao.getActiveForProject(pid)
        val slot = selectedSlot ?: run {
            lastKey = "t (no selection)"
            render()
            return
        }

        val index = page * pageSize + slot
        if (index !in actions.indices) {
            lastKey = "t (out of range)"
            render()
            return
        }

        val t = actions[index]
        taskDao.markDone(t.id)

        val projectName = projectDao.getById(pid)?.name
        logDone(t.title, origin = "project", project = projectName)

        lastKey = "done"
        selectedSlot = null
        render()
    }

    private fun editProjectDetailSelection() {
        val pid = openProjectId ?: run {
            lastKey = "e (no project)"
            render()
            return
        }

        val slot = selectedSlot
        if (slot == null) {
            renameProjectById(pid)
            return
        }

        val actions = taskDao.getActiveForProject(pid)
        val index = page * pageSize + slot
        if (index !in actions.indices) {
            lastKey = "e (out of range)"
            render()
            return
        }

        renameTaskById(
            taskId = actions[index].id,
            dialogTitle = "Edit action",
            hint = "action name",
            successNote = "action renamed"
        )
    }

    private fun editDueForSelectedProjectAction() {
        val pid = openProjectId ?: run {
            lastKey = "u (no project)"
            render()
            return
        }

        val actions = taskDao.getActiveForProject(pid)
        val slot = selectedSlot ?: run {
            lastKey = "u (no selection)"
            render()
            return
        }

        val index = page * pageSize + slot
        if (index !in actions.indices) {
            lastKey = "u (out of range)"
            render()
            return
        }

        val t = actions[index]
        openDueEditorForTask(t.id)
    }

    // --- Done log ---

    private fun formatTs(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(ms))
    }

    private fun logDone(text: String, origin: String, project: String? = null) {
        val t = text.trim()
        if (t.isEmpty()) return

        doneDao.insert(
            DoneEntity(
                tsMillis = System.currentTimeMillis(),
                text = t,
                origin = origin,
                project = project
            )
        )

        val count = doneDao.count()
        val overflow = count - doneLogMax
        if (overflow > 0) doneDao.deleteOldest(overflow)
    }

    // --- Render helpers ---

    private fun fmtTaskLine(t: TaskEntity): String {
        val due = t.dueMillis?.let { "  [${formatDue(it)}]" } ?: ""
        return "${t.title}$due"
    }

    private fun importMetaLine(item: InboxImportItem): String {
        val parts = mutableListOf<String>()
        if (item.projectName != null) {
            parts.add(if (item.projectId != null) item.projectName else "? ${item.projectName}")
        } else if (item.requestedListType != "INBOX") {
            parts.add(item.requestedListType.lowercase(Locale.getDefault()))
        }
        item.dueMillis?.let { parts.add(importDueFmt.format(Date(it))) }
        return parts.joinToString(" · ")
    }

    private fun helpSection(title: String, vararg rows: Pair<String, String>): HelpSection {
        return HelpSection(
            title = title,
            rows = rows.map { HelpRow(shortcut = it.first, description = it.second) }
        )
    }

    private fun appendHelpSections(out: SpannableStringBuilder, vararg sections: HelpSection) {
        if (sections.isEmpty()) return

        val maxShortcutWidth = sections
            .flatMap { it.rows }
            .maxOfOrNull { it.shortcut.length }
            ?: 0

        sections.forEachIndexed { index, section ->
            val titleStart = out.length
            out.append(section.title.uppercase(Locale.getDefault()))
            out.append('\n')
            out.setSpan(StyleSpan(Typeface.BOLD), titleStart, out.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(TypefaceSpan("monospace"), titleStart, out.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(0.9f), titleStart, out.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            section.rows.forEach { row ->
                val rowStart = out.length
                out.append(row.shortcut.padEnd(maxShortcutWidth))
                out.append("  ")
                out.append(row.description)
                out.append('\n')
                out.setSpan(TypefaceSpan("monospace"), rowStart, out.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(0.84f), rowStart, out.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (index < sections.lastIndex) {
                out.append('\n')
            }
        }
    }

    private fun helpTitleForCurrentScreen(): String = when (screen) {
        Screen.INBOX -> "Help - Inbox"
        Screen.NEXT -> "Help - Next"
        Screen.WAITING -> "Help - Waiting"
        Screen.SOMEDAY -> "Help - Someday"
        Screen.PROJECTS -> "Help - Projects"
        Screen.PROJECT_DETAIL -> "Help - Project"
        Screen.PROJECT_PICKER -> "Help - Pick Project"
        Screen.PROCESS -> "Help - Process"
        Screen.LOG -> "Help - Log"
        Screen.REVIEW -> "Help - Review"
        Screen.IMPORT_INBOX -> "Help - Import"
        Screen.SETTINGS -> "Help - Settings"
    }

    private fun helpSectionsForCurrentScreen(): List<HelpSection> {
        return when (screen) {
            Screen.SETTINGS -> listOf(
                helpSection("Theme", "w" to "toggle light / dark"),
                helpSection("Layout", "p" to "set page size", "o" to "set log page size"),
                helpSection("Import", "i" to "choose inbox folder/root"),
                helpSection("Navigation", "r" to "restore defaults", "b" to "back")
            )

            Screen.IMPORT_INBOX -> listOf(
                helpSection("Import", "enter" to "import checked tasks", "x" to "toggle selected", "r" to "rescan"),
                helpSection("Folder", "i" to "choose inbox folder/root"),
                helpSection("Navigation", "q/b" to "cancel")
            )

            Screen.PROCESS -> listOf(
                helpSection("Move", "d" to "next", "f" to "waiting", "s" to "someday"),
                helpSection("Project", "p" to "pick project", "g" to "new project"),
                helpSection("Task", "e" to "edit title", "u" to "set due + calendar/reminder", "t" to "done", "b" to "back")
            )

            Screen.PROJECT_PICKER -> listOf(
                helpSection("Selection", "1..${currentPageItemCount()}" to "select project", "enter" to "move item"),
                helpSection("Actions", "c" to "create project from item"),
                helpSection("Navigation", "b" to "back", "z/x" to "page")
            )

            Screen.PROJECTS -> listOf(
                helpSection("Selection", "1..${currentPageItemCount()}" to "select project", "enter" to "open"),
                helpSection("Actions", "c" to "new project", "e" to "rename selected", "t" to "mark done (empty project)"),
                helpSection("Navigation", "b" to "back", "z/x" to "page")
            )

            Screen.LOG -> listOf(
                helpSection("Navigation", "z/x" to "page", "b" to "back")
            )

            Screen.PROJECT_DETAIL -> listOf(
                helpSection("Selection", "1..${currentPageItemCount()}" to "select action"),
                helpSection("Actions", "c" to "add action", "e" to "rename action / project", "u" to "set due + reminder", "t" to "mark done"),
                helpSection("Move Selected", "a" to "inbox", "d" to "next", "f" to "waiting", "s" to "someday"),
                helpSection("Navigation", "b" to "back", "z/x" to "page")
            )

            Screen.INBOX, Screen.SOMEDAY, Screen.NEXT, Screen.WAITING -> listOf(
                helpSection("Selection", "1..${currentPageItemCount()}" to "select item", "enter" to "open"),
                helpSection("Move Selected", "a" to "inbox", "d" to "next", "f" to "waiting", "s" to "someday"),
                helpSection("Actions", "c" to "capture", "e" to "edit selected", "u" to "set due + calendar/reminder"),
                helpSection("Views", "a" to "inbox", "d" to "next", "f" to "waiting", "s" to "someday", "g" to "projects", "r" to "review", "l" to "log", "i" to "import", "q" to "settings"),
                helpSection("Navigation", "z/x" to "page")
            )

            Screen.REVIEW -> listOf(
                helpSection("Actions", "i" to "add trigger", "t" to "remove selected", "c" to "capture to inbox"),
                helpSection("Selection", "1..${currentPageItemCount()}" to "select trigger"),
                helpSection("Views", "a" to "inbox", "d" to "next", "f" to "waiting", "s" to "someday", "g" to "projects", "l" to "log", "q" to "settings"),
                helpSection("Navigation", "z/x" to "page")
            )
        }
    }

    private fun renderHelpOverlay() {
        val helpText = SpannableStringBuilder().apply {
            append("Shortcuts for the current screen")
            append('\n')
            append('\n')
            appendHelpSections(this, *helpSectionsForCurrentScreen().toTypedArray())
        }

        helpTitle.text = helpTitleForCurrentScreen()
        helpBody.text = helpText
        helpOverlay.visibility = if (isHelpVisible) View.VISIBLE else View.GONE
    }

    private fun toggleHelpOverlay(forceVisible: Boolean? = null) {
        isHelpVisible = forceVisible ?: !isHelpVisible
        renderHelpOverlay()
    }

    // --- Render ---

    private fun render() {
        // Ensure theme is applied even after coming back from background etc.
        applyThemeColors()

        header.text = when (screen) {
            Screen.PROCESS -> "miniGTD — process"
            Screen.IMPORT_INBOX -> "miniGTD — import"
            Screen.PROJECT_DETAIL -> "miniGTD — project"
            Screen.PROJECT_PICKER -> "miniGTD — pick project"
            else -> "miniGTD — ${screen.name.lowercase()}"
        }

        val out = SpannableStringBuilder()
        fun appendLine(text: String = "") {
            out.append(text)
            out.append('\n')
        }

        appendLine("last key: $lastKey")
        appendLine()

        when (screen) {

                Screen.SETTINGS -> {
                    val isDark = backgroundMode == "BLACK"
                    appendLine("settings")
                    appendLine()
                    appendLine("revision: ${appRevisionLabel()}")
                    appendLine()
                    appendLine("appearance")
                    appendLine("  ${if (!isDark) "[x]" else "[ ]"} Light mode")
                    appendLine("  ${if (isDark) "[x]" else "[ ]"} Dark mode")
                    appendLine()
                    appendLine("layout")
                    appendLine("  Page size: $pageSize")
                    appendLine("  Log page size: $pageSizeLog")
                    appendLine()
                }

                Screen.PROCESS -> {
                    val dueLine = processDueMillis?.let { "due: ${formatDue(it)}" } ?: "due: —"
                    appendLine("item:")
                    appendLine(processText)
                    appendLine(dueLine)
                    appendLine()
                }

                Screen.IMPORT_INBOX -> {
                    appendLine("${importItems.size} NEW TASKS")
                    if (importFolderLabel.isNotEmpty()) {
                        appendLine("source: $importFolderLabel")
                    }
                    appendLine()

                    importLoadError?.let { error ->
                        appendLine(error)
                        appendLine()
                    }

                    if (importItems.isEmpty()) {
                        appendLine("(empty)")
                    } else {
                        importItems.forEachIndexed { index, item ->
                            val mark = if (selectedSlot == index) ">" else " "
                            val check = if (item.checked) "[x]" else "[ ]"
                            appendLine("$mark$check ${item.title}")
                            val meta = importMetaLine(item)
                            if (meta.isNotEmpty()) {
                                appendLine("    $meta")
                            }
                            appendLine()
                        }
                    }

                    if (importInvalidCount > 0 || importDuplicateCount > 0) {
                        appendLine("ignored: $importInvalidCount invalid, $importDuplicateCount already imported")
                        appendLine()
                    }

                    appendLine("ENTER Import")
                    appendLine("X Toggle")
                    appendLine("Q Cancel")
                    appendLine("I Folder/Root")
                    appendLine("R Rescan")
                }

                Screen.PROJECT_PICKER -> {
                    val all = projectDao.getActiveProjects()
                    val active = all.takeLast(maxPickerProjects)

                    val totalPages = if (active.isEmpty()) 1 else ((active.size - 1) / pageSize + 1)
                    page = page.coerceIn(0, totalPages - 1)

                    appendLine("move item to project")
                    appendLine()
                    appendLine("item:")
                    appendLine(pendingTaskTitle)
                    appendLine()
                    appendLine("${active.size} projects (max $maxPickerProjects) (page ${page + 1}/$totalPages)")
                    appendLine()

                    if (active.isEmpty()) {
                        appendLine("(no projects)")
                    } else {
                        val start = page * pageSize
                        val end = minOf(start + pageSize, active.size)
                        for (i in start until end) {
                            val slot = i - start
                            val num = slot + 1
                            val mark = if (selectedSlot == slot) ">" else " "
                            val p = active[i]
                            val count = taskDao.getActiveForProject(p.id).size
                            appendLine("$mark$num  ${p.name} ($count)")
                        }
                    }

                    val n = minOf(pageSize, active.size - page * pageSize).coerceAtLeast(0)
                    appendLine()
                    appendLine("selectable: 1..$n")
                }

                Screen.PROJECTS -> {
                    val projects = projectDao.getActiveProjects()
                    val totalPages = if (projects.isEmpty()) 1 else ((projects.size - 1) / pageSize + 1)
                    page = page.coerceIn(0, totalPages - 1)

                    appendLine("${projects.size} projects (page ${page + 1}/$totalPages)")
                    appendLine()

                    if (projects.isEmpty()) {
                        appendLine("(empty)")
                    } else {
                        val start = page * pageSize
                        val end = minOf(start + pageSize, projects.size)
                        for (i in start until end) {
                            val slot = i - start
                            val num = slot + 1
                            val mark = if (selectedSlot == slot) ">" else " "
                            val p = projects[i]
                            val cnt = taskDao.getActiveForProject(p.id).size
                            appendLine("$mark$num  ${p.name} ($cnt)")
                        }
                    }

                    val n = minOf(pageSize, projects.size - page * pageSize).coerceAtLeast(0)
                    appendLine()
                    appendLine("selectable: 1..$n")
                }

                Screen.LOG -> {
                    val done = doneDao.getAllAsc()
                    val totalPages = if (done.isEmpty()) 1 else ((done.size - 1) / pageSizeLog + 1)
                    page = page.coerceIn(0, totalPages - 1)

                    appendLine("${done.size} done (keeps last $doneLogMax) (page ${page + 1}/$totalPages)")
                    appendLine()

                    if (done.isEmpty()) {
                        appendLine("(empty)")
                    } else {
                        val start = page * pageSizeLog
                        val end = minOf(start + pageSizeLog, done.size)
                        for (i in start until end) {
                            val slot = i - start
                            val num = slot + 1
                            val mark = if (selectedSlot == slot) ">" else " "
                            val e = done[i]
                            appendLine("$mark$num  ${formatTs(e.tsMillis)}  ${e.origin}${e.project?.let { " / $it" } ?: ""}")
                            appendLine("    ${e.text}")
                        }
                    }

                    appendLine()
                }

                Screen.PROJECT_DETAIL -> {
                    val pid = openProjectId
                    val p = pid?.let { projectDao.getById(it) }
                    if (p == null) {
                        appendLine("no project open")
                        appendLine()
                    } else {
                        appendLine("project: ${p.name}")
                        appendLine()

                        val actions = taskDao.getActiveForProject(p.id)
                        val totalPages = if (actions.isEmpty()) 1 else ((actions.size - 1) / pageSize + 1)
                        page = page.coerceIn(0, totalPages - 1)

                        appendLine("${actions.size} actions (page ${page + 1}/$totalPages)")
                        appendLine()

                        if (actions.isEmpty()) {
                            appendLine("(empty)")
                        } else {
                            val start = page * pageSize
                            val end = minOf(start + pageSize, actions.size)
                            for (i in start until end) {
                                val slot = i - start
                                val num = slot + 1
                                val mark = if (selectedSlot == slot) ">" else " "
                                appendLine("$mark$num  ${fmtTaskLine(actions[i])}")
                            }
                        }

                        val n = minOf(pageSize, actions.size - page * pageSize).coerceAtLeast(0)
                        appendLine()
                        appendLine("selectable: 1..$n")
                    }
                }

                Screen.INBOX, Screen.SOMEDAY, Screen.NEXT, Screen.WAITING -> {
                    val listType = when (screen) {
                        Screen.INBOX -> "INBOX"
                        Screen.SOMEDAY -> "SOMEDAY"
                        Screen.NEXT -> "NEXT"
                        Screen.WAITING -> "WAITING"
                        else -> "INBOX"
                    }

                    val tasks = taskDao.getActiveForList(listType)
                    val totalPages = if (tasks.isEmpty()) 1 else ((tasks.size - 1) / pageSize + 1)
                    page = page.coerceIn(0, totalPages - 1)

                    appendLine("${tasks.size} items (page ${page + 1}/$totalPages)")
                    appendLine()

                    if (tasks.isEmpty()) {
                        appendLine("(empty)")
                    } else {
                        val start = page * pageSize
                        val end = minOf(start + pageSize, tasks.size)
                        for (i in start until end) {
                            val slot = i - start
                            val num = slot + 1
                            val mark = if (selectedSlot == slot) ">" else " "
                            appendLine("$mark$num  ${fmtTaskLine(tasks[i])}")
                        }
                    }

                    val n = minOf(pageSize, tasks.size - page * pageSize).coerceAtLeast(0)
                    appendLine()
                    appendLine("selectable: 1..$n")
                }

                Screen.REVIEW -> {
                    val items = reviewItemDao.getAll()
                    val totalPages = if (items.isEmpty()) 1 else ((items.size - 1) / pageSize + 1)
                    page = page.coerceIn(0, totalPages - 1)

                    appendLine("review — triggers")
                    appendLine()
                    appendLine("${items.size} triggers (page ${page + 1}/$totalPages)")
                    appendLine()

                    if (items.isEmpty()) {
                        appendLine("(no triggers yet — add with i)")
                    } else {
                        val start = page * pageSize
                        val end = minOf(start + pageSize, items.size)
                        for (i in start until end) {
                            val slot = i - start
                            val num = slot + 1
                            val mark = if (selectedSlot == slot) ">" else " "
                            appendLine("$mark$num  ${items[i].title}")
                        }
                    }

                    val n = minOf(pageSize, items.size - page * pageSize).coerceAtLeast(0)
                    appendLine()
                    appendLine("selectable: 1..$n")
                }

            }
        appendLine()
        appendLine("help: h")
        body.text = out
        renderHelpOverlay()
    }
}
