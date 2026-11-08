# miniGTD Verification

Run from the project root on Windows:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The Robolectric tests use an isolated database and a fake Android document
provider. They do not connect to a phone or access the real Obsidian vault.

Coverage includes:

- Room upgrades from versions 1, 2 and 3 using the committed schemas, preserving
  existing tasks, projects, deadlines, settings, review triggers and done log.
- JSON review, selection, import and UUID duplicate protection when source files
  cannot be renamed; invalid and partially written JSON; UTF-8 BOM.
- Matching existing/archived projects without creating extra projects, and routing
  unknown project names to Inbox.
- Keyboard paging, moving tasks, completing tasks and protecting active projects
  from archival.
- Launcher capture routing and the task provider's stable IDs, task ordering,
  result limit, deadline metadata and change notifications.
- Deadline dates crossing midnight and readable labels in the system dark theme.

Phone smoke checks after installation:

1. Install as an update using `adb install -r -t app\build\outputs\apk\debug\app-debug.apk`.
2. Confirm existing tasks and settings remain present.
3. On mLauncher, check that the GTD panel lists Next tasks. Its heading opens
   Inbox, its body opens Next and its plus button opens Capture.
4. Cancel Capture and confirm miniGTD returns to Inbox.
5. Confirm the local synced `_minigtd/inbox` is still selected and rescan works.

Generated APKs, reports and device snapshots belong under `app/build` and must
not be committed. The launcher and the phone may be shared with other tasks;
finish their installation/testing work before using either.
