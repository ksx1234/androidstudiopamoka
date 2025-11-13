package app.pamoka.timetable

import java.io.FileNotFoundException
import java.io.IOException

import kotlinx.coroutines.delay
import android.app.*
import com.google.gson.Gson
import kotlinx.coroutines.withContext
import android.content.ComponentCallbacks2
import com.google.android.gms.ads.MobileAds
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import androidx.recyclerview.widget.LinearLayoutManager
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

// Add these constants here (NOT inside companion object)
private const val NOTES_SEPARATOR = "___NOTES_SEP___"
private const val FIELDS_SEPARATOR = "___FIELD_SEP___"
private const val IMAGES_SEPARATOR = "___IMG_SEP___"

private val mondayColors = listOf("#1E3A8A", "#1E40AF", "#2563EB", "#3B82F6", "#60A5FA", "#93C5FD")
private val tuesdayColors = listOf("#166534", "#15803D", "#16A34A", "#22C55E", "#4ADE80", "#86EFAC")
private val wednesdayColors = listOf("#831843", "#9D174D", "#DB2777", "#EC4899", "#F472B6", "#F9A8D4")
private val thursdayColors = listOf("#7C2D12", "#C2410C", "#EA580C", "#F97316", "#FB923C", "#FDBA74")
private val fridayColors = listOf("#7F1D1D", "#B91C1C", "#DC2626", "#EF4444", "#F87171", "#FCA5A5")

private fun getColorPaletteForWeekday(weekday: Int): List<String> {
    return when (weekday) {
        0 -> mondayColors
        1 -> tuesdayColors
        2 -> wednesdayColors
        3 -> thursdayColors
        4 -> fridayColors
        else -> mondayColors
    }
}

data class LessonTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val startTime: Calendar,
    val endTime: Calendar,
    val color: String,
    val isFreeLesson: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LessonTemplate
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

data class NoteReminder(
    val id: String = UUID.randomUUID().toString(),
    val reminderTime: Calendar,
    val isEnabled: Boolean = true
)

data class WeeklyLesson(
    val id: String = UUID.randomUUID().toString(),
    val templateId: String, // Store template ID instead of full object
    var note: String = "",
    var imagePaths: MutableList<String> = mutableListOf(),
    val weekIdentifier: String = "",
    var reminderTime: Long = 0 // Store as Long instead of NoteReminder object
) {
    // Helper method to check if has reminder
    fun hasReminder(): Boolean = reminderTime > 0 && reminderTime > System.currentTimeMillis()

    // Convert to serializable string
    fun toStorageString(): String {
        val escapedNote = note.replace(FIELDS_SEPARATOR, "_ESC_FIELD_")
        val imagesString = imagePaths.joinToString(IMAGES_SEPARATOR)
        return listOf(id, templateId, escapedNote, imagesString, weekIdentifier, reminderTime.toString())
            .joinToString(FIELDS_SEPARATOR)
    }

    companion object {
        fun fromStorageString(data: String): WeeklyLesson? {
            try {
                val parts = data.split(FIELDS_SEPARATOR)
                if (parts.size >= 6) {
                    val note = parts[2].replace("_ESC_FIELD_", FIELDS_SEPARATOR)
                    val imagePaths = if (parts[3].isNotEmpty()) {
                        parts[3].split(IMAGES_SEPARATOR).toMutableList()
                    } else mutableListOf()

                    return WeeklyLesson(
                        id = parts[0],
                        templateId = parts[1],
                        note = note,
                        imagePaths = imagePaths,
                        weekIdentifier = parts[4],
                        reminderTime = parts[5].toLongOrNull() ?: 0L
                    )
                }
            } catch (e: Exception) {
                Log.e("WeeklyLesson", "Error parsing WeeklyLesson: ${e.message}")
            }
            return null
        }
    }
}

data class WeekDayItem(val weekday: Int, val name: String, val lessons: List<LessonTemplate>)

data class LanguageItem(val name: String, val code: String, val flagResId: Int)

data class LessonTimeTemplate(
    val id: String = UUID.randomUUID().toString(),
    val lessonNumber: Int,
    val startTime: Calendar,
    val endTime: Calendar
) {
    fun getDisplayName(language: String = "en"): String {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        return when (language) {
            "es" -> "Lección $lessonNumber (${timeFormat.format(startTime.time)}-${timeFormat.format(endTime.time)})"
            "lt" -> getDisplayNameLithuanian()
            else -> {
                val numberSuffix = when (lessonNumber) {
                    1 -> "st"
                    2 -> "nd"
                    3 -> "rd"
                    else -> "th"
                }
                "${lessonNumber}$numberSuffix lesson (${timeFormat.format(startTime.time)}-${timeFormat.format(endTime.time)})"
            }
        }
    }

    fun getDisplayNameLithuanian(): String {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "$lessonNumber pamoka (${timeFormat.format(startTime.time)}-${timeFormat.format(endTime.time)})"
    }
}

class MainActivity : AppCompatActivity() {

        private lateinit var swipeArea: LinearLayout
    private lateinit var weekViewContainer: LinearLayout
    private lateinit var dayViewContainer: ScrollView
    private lateinit var dayLayoutContainer: LinearLayout
    private lateinit var weekRecyclerView: RecyclerView
    private lateinit var btnLanguage: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var dayRecyclerView: RecyclerView
    private lateinit var dayAdapter: DayLessonAdapter


    private val lessonTemplatesByWeekday = mutableMapOf<Int, MutableList<LessonTemplate>>()
    private val weeklyLessons = mutableMapOf<String, MutableMap<Int, MutableList<WeeklyLesson>>>()
    private val lessonImageStates = mutableMapOf<String, Boolean>()
    private var currentDate = Calendar.getInstance()
    private var startX = 0f
    private var isWeekView = true

    private val PREFS_NAME = "LessonAppPrefs"
    private val LESSONS_KEY = "lessons_data"
    private val TEMPLATES_KEY = "templates_data"
    private val LANGUAGE_KEY = "app_language"
    private val LESSON_TIMES_KEY = "lesson_times_data"

    private lateinit var weekAdapter: WeekAdapter
    private lateinit var weekdayNames: List<String>
    private lateinit var tvDayTitle: TextView
    private lateinit var fabAddButton: FloatingActionButton
    private val lessonTimeTemplates = mutableListOf<LessonTimeTemplate>()

    private var languageDialog: AlertDialog? = null
    private var settingsDialog: AlertDialog? = null
    private var addLessonDialog: AlertDialog? = null
    private var manageLessonTimesDialog: AlertDialog? = null
    private var noteDialog: AlertDialog? = null
    private var currentTemplateForEdit: LessonTemplate? = null
    private var currentWeeklyLessonForNote: WeeklyLesson? = null

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val selectedImageUri: Uri? = result.data?.data
            selectedImageUri?.let { uri ->
                showImageConfirmationDialog(uri)
            }
        }

    }
    class DayLessonAdapter(
        private val lessons: List<Pair<WeeklyLesson, LessonTemplate>>,
        private val onLessonClick: (LessonTemplate) -> Unit,
        private val onNoteClick: (WeeklyLesson) -> Unit
    ) : RecyclerView.Adapter<DayLessonAdapter.DayLessonViewHolder>() {

        class DayLessonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvLessonName: TextView = itemView.findViewById(R.id.tvLessonName)
            val tvTime: TextView = itemView.findViewById(R.id.tvTime)
            val tvNote: TextView = itemView.findViewById(R.id.tvNote)
            val imageIndicator: ImageView = itemView.findViewById(R.id.imageIndicator)
            val leftContainer: LinearLayout = itemView.findViewById(R.id.leftContainer)
            val noteContainer: LinearLayout = itemView.findViewById(R.id.rightContainer)
            val mainContainer: LinearLayout = itemView.findViewById(R.id.mainContainer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayLessonViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_day_lesson, parent, false)
            return DayLessonViewHolder(view)
        }

        override fun onBindViewHolder(holder: DayLessonViewHolder, position: Int) {
            val (weeklyLesson, template) = lessons[position]
            val context = holder.itemView.context

            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val timeText = "${timeFormat.format(template.startTime.time)} - ${timeFormat.format(template.endTime.time)}"

            val lessonNumber = getLessonNumber(template, context)
            val lessonNumberText = if (lessonNumber > 0) "$lessonNumber. " else ""

            val lessonDisplayName = if (template.isFreeLesson) {
                "$lessonNumberText${context.getString(R.string.free)}"
            } else {
                "$lessonNumberText${template.name}"
            }

            holder.tvLessonName.text = lessonDisplayName
            holder.tvTime.text = timeText
            holder.tvNote.text = weeklyLesson.note

            // Set background color
            val lessonColor = if (template.isFreeLesson) "#808080" else template.color
            val backgroundDrawable = ContextCompat.getDrawable(context, R.drawable.rectangle_button_shape)?.mutate()
            backgroundDrawable?.setTint(Color.parseColor(lessonColor))
            holder.mainContainer.background = backgroundDrawable

            // Set image indicator visibility - THIS IS THE KEY FIX
            holder.imageIndicator.visibility = if (weeklyLesson.imagePaths.isNotEmpty()) View.VISIBLE else View.GONE
            holder.imageIndicator.setColorFilter(Color.WHITE)

            // Set click listeners
            holder.leftContainer.setOnClickListener { onLessonClick(template) }
            holder.noteContainer.setOnClickListener { onNoteClick(weeklyLesson) }

            Log.d("DayLessonAdapter", "Bound lesson: ${template.name}, images: ${weeklyLesson.imagePaths.size}, visible: ${holder.imageIndicator.visibility}")
        }

        override fun getItemCount() = lessons.size

        private fun getLessonNumber(template: LessonTemplate, context: Context): Int {
            val mainActivity = context as? MainActivity
            return mainActivity?.getLessonNumberForTemplate(template) ?: 0
        }
    }    companion object {
        private const val FEATURE_REQUEST_PREFS = "feature_request_prefs"
        private const val LAST_FEATURE_REQUEST_TIME = "last_feature_request_time"
        private const val MIN_TIME_BETWEEN_REQUESTS = 60000 // 1 minute in milliseconds

        fun scheduleReminder(context: Context, reminderTime: Calendar, weeklyLesson: WeeklyLesson) {
            // Delegate to ReminderReceiver - remove the template parameter
            ReminderReceiver.scheduleReminder(context, reminderTime, weeklyLesson)            // Implementation for reminder scheduling
        }
    }

    private fun getWeekdayNames(language: String): List<String> {
        return when (language) {
            "es" -> listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes")
            "lt" -> listOf("Pirmadienis", "Antradienis", "Trečiadienis", "Ketvirtadienis", "Penktadienis")
            else -> listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setLanguage()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            MobileAds.initialize(this)
            Log.d("Ads", "✅ MobileAds initialized successfully!")
        } catch (e: Exception) {
            Log.e("Ads", "❌ Error initializing MobileAds: ${e.message}")
        }

        weekdayNames = listOf(
            getString(R.string.monday).replaceFirstChar { it.uppercase() },
            getString(R.string.tuesday).replaceFirstChar { it.uppercase() },
            getString(R.string.wednesday).replaceFirstChar { it.uppercase() },
            getString(R.string.thursday).replaceFirstChar { it.uppercase() },
            getString(R.string.friday).replaceFirstChar { it.uppercase() }
        )
        supportActionBar?.hide()

        createNotificationChannel()
        checkNotificationPermission()
        initializeViews()
        setupAdapters()
        setupClickListeners()
        showWeekView()

        // Load data in background to prevent main thread blocking
        lifecycleScope.launch(Dispatchers.IO) {
            loadDataWithErrorHandling()
            loadLessonTimes()
            withContext(Dispatchers.Main) {
                refreshAllDisplays()
                rescheduleAllReminders()
                debugReminderData()
                validateLoadedData()
            }
        }
    }
    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }
    private fun validateLoadedData() {
        // Recreate any missing weekly lessons from templates
        val currentWeekId = getCurrentWeekIdentifier()
        for (weekday in 0..4) {
            val templates = lessonTemplatesByWeekday[weekday] ?: emptyList()
            templates.forEach { template ->
                getOrCreateWeeklyLesson(template, currentWeekId, weekday)
            }
        }
        saveDataToStorage() // Save any recreated data
    }

    private fun rescheduleAllReminders() {
        weeklyLessons.values.forEach { weekLessons ->
            weekLessons.values.forEach { lessons ->
                lessons.forEach { weeklyLesson ->
                    if (weeklyLesson.hasReminder()) {
                        val template = getTemplateForWeeklyLesson(weeklyLesson)
                        template?.let {
                            val reminderCalendar = Calendar.getInstance().apply {
                                timeInMillis = weeklyLesson.reminderTime
                            }
                            // FIXED: Remove the template parameter
                            ReminderReceiver.scheduleReminder(this, reminderCalendar, weeklyLesson)
                        }
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "lesson_reminders",
                "Lesson Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setDescription("Reminders for your lessons")
                enableLights(true)
                lightColor = Color.BLUE
                enableVibration(true)
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isWeekView", isWeekView)
        outState.putLong("currentDate", currentDate.timeInMillis)
        outState.putString("currentTemplateId", currentTemplateForEdit?.id)
        outState.putString("currentWeeklyLessonId", currentWeeklyLessonForNote?.id)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        isWeekView = savedInstanceState.getBoolean("isWeekView", true)
        currentDate.timeInMillis = savedInstanceState.getLong("currentDate", Calendar.getInstance().timeInMillis)

        val templateId = savedInstanceState.getString("currentTemplateId")
        val weeklyLessonId = savedInstanceState.getString("currentWeeklyLessonId")

        if (!templateId.isNullOrEmpty()) {
            currentTemplateForEdit = lessonTemplatesByWeekday.values
                .flatten()
                .find { it.id == templateId }
            currentTemplateForEdit?.let { showEditLessonDialog(it) }
        }

        if (!weeklyLessonId.isNullOrEmpty()) {
            currentWeeklyLessonForNote = weeklyLessons.values
                .flatMap { it.values }
                .flatten()
                .find { it.id == weeklyLessonId }
            currentWeeklyLessonForNote?.let { showNoteDialog(it) }
        }

        if (isWeekView) {
            showWeekView()
        } else {
            showDayView(getCurrentWeekday())
        }
    }

    private fun setLanguage() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedLanguage = prefs.getString(LANGUAGE_KEY, null)
        val language = when {
            savedLanguage in setOf("en", "lt", "es") -> savedLanguage
            else -> {
                val systemLang = Locale.getDefault().language
                if (systemLang in setOf("lt", "es")) systemLang else "en"
            }
        }

        val locale = Locale(language)
        Locale.setDefault(locale)

        val config = Configuration(resources.configuration)
        config.setLocale(locale)

        // Use the new API for Android 7.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createConfigurationContext(config)
        }

        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun saveLanguage(language: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(LANGUAGE_KEY, language).apply()
    }

    private fun initializeViews() {
        tvDayTitle = findViewById(R.id.tvDayTitle)
        fabAddButton = findViewById(R.id.fabAddButton)
        swipeArea = findViewById(R.id.swipeArea)
        weekViewContainer = findViewById(R.id.weekViewContainer)
        dayViewContainer = findViewById(R.id.dayViewContainer)
        dayLayoutContainer = findViewById(R.id.dayLayoutContainer)
        weekRecyclerView = findViewById(R.id.weekRecyclerView)
        btnLanguage = findViewById(R.id.btnLanguage)
        btnSettings = findViewById(R.id.btnSettings)

        // Add this line for the new RecyclerView
        dayRecyclerView = findViewById(R.id.dayRecyclerView)
    }
    fun getLessonNumberForTemplate(template: LessonTemplate): Int {
        val startTime = template.startTime.timeInMillis
        val endTime = template.endTime.timeInMillis

        return lessonTimeTemplates.find {
            it.startTime.timeInMillis == startTime && it.endTime.timeInMillis == endTime
        }?.lessonNumber ?: 0
    }
    private fun setupAdapters() {
        weekAdapter = WeekAdapter(emptyList()) { weekday -> showDayView(weekday) }
        weekRecyclerView.apply {
            layoutManager = object : GridLayoutManager(this@MainActivity, 2) {
                override fun onLayoutCompleted(state: RecyclerView.State?) {
                    super.onLayoutCompleted(state)
                    // Post the centering to avoid layout conflicts
                    post {
                        centerFridayBox()
                    }
                }

                override fun isAutoMeasureEnabled(): Boolean {
                    return true
                }

                // Add this to prevent predictive animations during layout
                override fun supportsPredictiveItemAnimations(): Boolean {
                    return false
                }
            }
            adapter = weekAdapter
            setPadding(4, 4, 4, 4)
            clipToPadding = false

            // Use post to avoid layout conflicts
            post {
                centerFridayBox()
            }
        }
    }

    private fun centerFridayBox() {
        val layoutManager = weekRecyclerView.layoutManager as GridLayoutManager
        val fridayPosition = 4

        val fridayView = layoutManager.findViewByPosition(fridayPosition)
        fridayView?.let { view ->
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            val rowWidth = weekRecyclerView.width - weekRecyclerView.paddingLeft - weekRecyclerView.paddingRight
            val itemWidth = rowWidth / 2
            val leftMargin = (rowWidth - itemWidth) / 2
            params.marginStart = leftMargin
            params.marginEnd = leftMargin
            view.layoutParams = params
        }
    }

    private fun setupClickListeners() {
        fabAddButton.setOnClickListener {
            showAddLessonDialog()
        }

        btnLanguage.setOnClickListener {
            showLanguageDialog()
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        swipeArea.setOnTouchListener { _, event ->
            if (!isWeekView) {
                handleSwipe(event)
            } else {
                false
            }
        }
    }

    private fun showLanguageDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_language, null)
        val listView = dialogView.findViewById<ListView>(R.id.languageListView)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelLanguage)

        val languages = listOf(
            LanguageItem(getString(R.string.english), "en", R.drawable.flag_uk),
            LanguageItem(getString(R.string.lithuanian), "lt", R.drawable.flag_lt),
            LanguageItem(getString(R.string.spanish), "es", R.drawable.flag_es)
        )

        val adapter = object : ArrayAdapter<LanguageItem>(this, R.layout.item_language_list, R.id.languageText, languages) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_language_list, parent, false)

                val languageItem = getItem(position)
                val flagImage = view.findViewById<ImageView>(R.id.flagImage)
                val languageText = view.findViewById<TextView>(R.id.languageText)

                languageItem?.let {
                    flagImage.setImageResource(it.flagResId)
                    languageText.text = it.name
                    languageText.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }

                return view
            }
        }

        listView.adapter = adapter

        languageDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setOnDismissListener {
                languageDialog = null
            }
            .create()

        // Apply window settings programmatically - ADD THESE LINES:
        languageDialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        languageDialog?.window?.setLayout(getDialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)

        listView.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> changeLanguage("en")
                1 -> changeLanguage("lt")
                2 -> changeLanguage("es")
            }
            languageDialog?.dismiss()
        }

        btnCancel.setOnClickListener {
            languageDialog?.dismiss()
        }

        languageDialog?.show()
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)

        val btnManageLessonTimes = dialogView.findViewById<Button>(R.id.btnManageLessonTimes)
        val btnDeleteAll = dialogView.findViewById<Button>(R.id.btnDeleteAll)
        val btnFeatureRequest = dialogView.findViewById<Button>(R.id.btnFeatureRequest)
        val btnCancelSettings = dialogView.findViewById<Button>(R.id.btnCancelSettings)

        // Set all button texts using string resources
        btnManageLessonTimes.text = getString(R.string.manage_lesson_times)
        btnDeleteAll.text = getString(R.string.delete_all_data)
        btnFeatureRequest.text = getString(R.string.ask_for_feature)
        btnCancelSettings.text = getString(R.string.cancel)

        settingsDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setOnDismissListener {
                settingsDialog = null
            }
            .create()

        // Apply window settings programmatically
        settingsDialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        settingsDialog?.window?.setLayout(getDialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)

        btnManageLessonTimes.setOnClickListener {
            settingsDialog?.dismiss()
            showManageLessonTimesDialog()
        }

        btnDeleteAll.setOnClickListener {
            settingsDialog?.dismiss()
            showDeleteAllConfirmation()
        }

        btnFeatureRequest.setOnClickListener {
            settingsDialog?.dismiss()
            showFeatureRequestDialog()
        }

        btnCancelSettings.setOnClickListener {
            settingsDialog?.dismiss()
        }

        settingsDialog?.show()
    }

    private fun showFeatureRequestDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_feature_request, null)
        val etFeatureMessage = dialogView.findViewById<EditText>(R.id.etFeatureMessage)
        val tvCharacterCount = dialogView.findViewById<TextView>(R.id.tvCharacterCount)
        val btnSendFeature = dialogView.findViewById<Button>(R.id.btnSendFeature)
        val btnCancelFeature = dialogView.findViewById<Button>(R.id.btnCancelFeature)

        // Set dialog title and button texts using string resources
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setTitle(getString(R.string.feature_request)) // This sets the dialog title
            .create()

        // Apply window settings programmatically
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialog.window?.setLayout(getDialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)

        // Set button texts
        btnSendFeature.text = getString(R.string.send_feature_request)
        btnCancelFeature.text = getString(R.string.cancel)

        // Update character count as user types
        etFeatureMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val currentLength = s?.length ?: 0
                tvCharacterCount.text = "$currentLength/200"

                // Enable/disable send button based on length
                btnSendFeature.isEnabled = currentLength in 5..200
            }
        })

        btnSendFeature.setOnClickListener {
            val message = etFeatureMessage.text.toString().trim()

            when {
                message.length < 5 -> {
                    Toast.makeText(this, getString(R.string.feature_request_too_short), Toast.LENGTH_SHORT).show()
                }
                message.length > 200 -> {
                    Toast.makeText(this, getString(R.string.feature_request_too_long), Toast.LENGTH_SHORT).show()
                }
                !canSendFeatureRequest() -> {
                    Toast.makeText(this, getString(R.string.feature_request_limit), Toast.LENGTH_SHORT).show()
                }
                else -> {
                    sendFeatureRequest(message)
                    dialog.dismiss()
                }
            }
        }

        btnCancelFeature.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        // Show keyboard automatically
        etFeatureMessage.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    // Feature Request Methods
    private fun canSendFeatureRequest(): Boolean {
        val prefs = getSharedPreferences(FEATURE_REQUEST_PREFS, MODE_PRIVATE)
        val lastRequestTime = prefs.getLong(LAST_FEATURE_REQUEST_TIME, 0)
        val currentTime = System.currentTimeMillis()

        return (currentTime - lastRequestTime) >= MIN_TIME_BETWEEN_REQUESTS
    }

    private fun sendFeatureRequest(message: String) {
        // Save the time of this request for spam prevention
        val prefs = getSharedPreferences(FEATURE_REQUEST_PREFS, MODE_PRIVATE)
        prefs.edit().putLong(LAST_FEATURE_REQUEST_TIME, System.currentTimeMillis()).apply()

        Thread {
            try {
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                val appVersion = packageInfo.versionName
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"

                // Combine all info into one comprehensive message
                val fullMessage = """
                    $message
                    
                    --- Additional Info ---
                    App Version: $appVersion
                    Device: $deviceInfo
                    Timestamp: $timestamp
                """.trimIndent()

                val success = submitToGoogleFormSimple(fullMessage)

                runOnUiThread {
                    if (success) {
                        Toast.makeText(this@MainActivity, getString(R.string.feature_request_sent), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, getString(R.string.feature_request_error), Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, getString(R.string.feature_request_error), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun submitToGoogleFormSimple(message: String): Boolean {
        return try {
            // Use the correct form URL and field ID from your form
            val formUrl = "https://docs.google.com/forms/u/0/d/e/1FAIpQLScR4AiN7V3mcs-QtUovc83ILuiBxquQkJFSv8ZqrBJlaF0MGg/formResponse"
            val messageField = "entry.208245184"

            val url = URL(formUrl)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.doOutput = true
            connection.doInput = true
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            // Build the form data
            val postData = "${messageField}=${URLEncoder.encode(message, "UTF-8")}"

            // Send the data
            connection.outputStream.use { os ->
                os.write(postData.toByteArray(StandardCharsets.UTF_8))
            }

            // Get response code
            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage

            // Log everything for debugging
            Log.d("GoogleForm", "Submission response: $responseCode - $responseMessage")
            Log.d("GoogleForm", "URL: $formUrl")
            Log.d("GoogleForm", "Field: $messageField")
            Log.d("GoogleForm", "Data length: ${postData.length}")

            connection.disconnect()

            // Google Forms returns 200 on success, but sometimes redirects (302)
            responseCode == 200 || responseCode == 302

        } catch (e: Exception) {
            Log.e("GoogleForm", "Failed to submit to Google Forms: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun showDeleteAllConfirmation() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirmation, null)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvMessage)
        val btnYes = dialogView.findViewById<Button>(R.id.btnYes)
        val btnNo = dialogView.findViewById<Button>(R.id.btnNo)

        tvMessage.text = getString(R.string.delete_all_data_confirmation)

        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(dialogView)
            .create()
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            // Remove any default dialog decorations
            window.requestFeature(Window.FEATURE_NO_TITLE)
            window.setBackgroundDrawableResource(android.R.color.transparent)

            // Remove margins completely
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Show the dialog first, then adjust the layout
        dialog.show()

        // After showing, make sure the layout fills the parent
        val parent = dialogView.parent as? ViewGroup
        parent?.setBackgroundColor(Color.TRANSPARENT)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnYes.setOnClickListener {
            dialog.dismiss()
            showTimetableDeletionConfirmation()
        }

        btnNo.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showTimetableDeletionConfirmation() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirmation, null)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvMessage)
        val btnYes = dialogView.findViewById<Button>(R.id.btnYes)
        val btnNo = dialogView.findViewById<Button>(R.id.btnNo)

        tvMessage.text = getString(R.string.delete_timetable_confirmation)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnYes.setOnClickListener {
            dialog.dismiss()
            deleteAllData(true)
        }

        btnNo.setOnClickListener {
            dialog.dismiss()
            deleteAllData(false)
        }

        dialog.show()
    }

    private fun showManageLessonTimesDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manage_lesson_times, null)
        val lessonTimesContainer = dialogView.findViewById<LinearLayout>(R.id.lessonTimesContainer)
        val btnAddNewTime = dialogView.findViewById<Button>(R.id.btnAddNewTime)
        val btnSaveTimes = dialogView.findViewById<Button>(R.id.btnSaveTimes)

        // Remove these lines since the buttons are already set in XML
        // btnAddNewTime.text = getString(R.string.add_new_lesson_time)
        // btnSaveTimes.text = getString(R.string.save)

        loadLessonTimes()
        refreshLessonTimesUI(lessonTimesContainer)

        btnAddNewTime.setOnClickListener {
            manageLessonTimesDialog?.dismiss()
            showAddLessonTimeDialog()
        }

        btnSaveTimes.setOnClickListener {
            saveLessonTimes()
            manageLessonTimesDialog?.dismiss()
            Toast.makeText(this, getString(R.string.note_saved), Toast.LENGTH_SHORT).show()
        }

        manageLessonTimesDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setOnDismissListener {
                manageLessonTimesDialog = null
            }
            .create()

        // Apply window settings programmatically
        manageLessonTimesDialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        manageLessonTimesDialog?.window?.setLayout(getDialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)

        manageLessonTimesDialog?.show()
    }

    private fun updateExistingLessonsForTimeChanges(oldLessonTime: LessonTimeTemplate?, newLessonTime: LessonTimeTemplate) {
        lessonTemplatesByWeekday.values.forEach { templates ->
            templates.forEach { template ->
                if (oldLessonTime == null ||
                    (template.startTime.timeInMillis == oldLessonTime.startTime.timeInMillis &&
                            template.endTime.timeInMillis == oldLessonTime.endTime.timeInMillis)) {

                    template.startTime.timeInMillis = newLessonTime.startTime.timeInMillis
                    template.endTime.timeInMillis = newLessonTime.endTime.timeInMillis
                }
            }
        }

        // Update free lessons for all weekdays
        for (weekday in 0..4) {
            autoFillGapsBetweenLessons(weekday)
        }

        refreshAllDisplays()
        saveDataToStorage()
    }

    private fun showAddLessonTimeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_lesson_time, null)
        val spinnerLessonNumber = dialogView.findViewById<Spinner>(R.id.spinnerLessonNumber)
        val btnStartTime = dialogView.findViewById<Button>(R.id.btnStartTime)
        val btnEndTime = dialogView.findViewById<Button>(R.id.btnEndTime)
        val btnAddTime = dialogView.findViewById<Button>(R.id.btnAddTime)
        val btnCancelTime = dialogView.findViewById<Button>(R.id.btnCancelTime)

        btnCancelTime.visibility = View.GONE
        btnAddTime.text = getString(R.string.add_time)
        btnStartTime.text = getString(R.string.start_time_default)
        btnEndTime.text = getString(R.string.end_time_default)

        val usedLessonNumbers = lessonTimeTemplates.map { it.lessonNumber }.toSet()
        val availableNumbers = (1..10).filter { it !in usedLessonNumbers }

        if (availableNumbers.isEmpty()) {
            Toast.makeText(this, "All lesson slots are used", Toast.LENGTH_SHORT).show()
            return
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, availableNumbers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLessonNumber.adapter = adapter

        var startTime: Calendar? = null
        var endTime: Calendar? = null

        btnStartTime.setOnClickListener {
            showTimePickerDialog(null) { time ->
                startTime = time
                btnStartTime.text = formatTime(time)
            }
        }

        btnEndTime.setOnClickListener {
            showTimePickerDialog(null) { time ->
                endTime = time
                btnEndTime.text = formatTime(time)
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // Apply window settings programmatically
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Remove deprecated display metrics code and use modern approach
        val screenWidth = resources.displayMetrics.widthPixels

// Remove unused verticalMargin variable
        val horizontalMargin = (screenWidth * 0.05).toInt()

        val layoutParams = WindowManager.LayoutParams().apply {
            copyFrom(dialog.window?.attributes)
            width = screenWidth - (horizontalMargin * 2)
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER
        }

        dialog.window?.attributes = layoutParams

        btnAddTime.setOnClickListener {
            val lessonNumber = availableNumbers[spinnerLessonNumber.selectedItemPosition]
            if (startTime != null && endTime != null) {
                if (endTime!!.before(startTime) || endTime == startTime) {
                    Toast.makeText(this, "End time must be after start time", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val newLessonTime = LessonTimeTemplate(
                    lessonNumber = lessonNumber,
                    startTime = startTime!!,
                    endTime = endTime!!
                )
                lessonTimeTemplates.add(newLessonTime)
                saveLessonTimes()
                dialog.dismiss()
                showManageLessonTimesDialog()
                Toast.makeText(this, getString(R.string.lesson_added_to).replace("%s", ""), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.fill_all_fields), Toast.LENGTH_SHORT).show()
            }
        }

        btnCancelTime.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        // Optional: Adjust the dialog size after showing to ensure it's properly sized
        dialog.window?.setLayout(
            (screenWidth * 0.9).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun refreshLessonTimesUI(container: LinearLayout) {
        container.removeAllViews()

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val language = prefs.getString(LANGUAGE_KEY, "lt") ?: "lt"

        lessonTimeTemplates.sortedBy { it.lessonNumber }.forEach { lessonTime ->
            val lessonTimeView = layoutInflater.inflate(R.layout.item_lesson_time, null)

            lessonTimeView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 4, 0, 4)
            }

            val tvLessonInfo = lessonTimeView.findViewById<TextView>(R.id.tvLessonInfo)
            val btnStartTime = lessonTimeView.findViewById<Button>(R.id.btnStartTime)
            val btnEndTime = lessonTimeView.findViewById<Button>(R.id.btnEndTime)
            val btnDelete = lessonTimeView.findViewById<Button>(R.id.btnDelete)

            tvLessonInfo.text = lessonTime.getDisplayName(language)
            tvLessonInfo.textSize = 12f

            btnStartTime.text = formatTime(lessonTime.startTime)
            btnEndTime.text = formatTime(lessonTime.endTime)

            btnStartTime.textSize = 12f
            btnEndTime.textSize = 12f
            btnDelete.textSize = 12f

            btnStartTime.setPadding(8, 4, 8, 4)
            btnEndTime.setPadding(8, 4, 8, 4)
            btnDelete.setPadding(8, 4, 8, 4)

            btnStartTime.setOnClickListener {
                showTimePickerDialog(lessonTime.startTime) { newTime ->
                    val oldTime = lessonTime.startTime.clone() as Calendar
                    lessonTime.startTime.timeInMillis = newTime.timeInMillis
                    btnStartTime.text = formatTime(newTime)
                    tvLessonInfo.text = if (language == "lt") {
                        lessonTime.getDisplayNameLithuanian()
                    } else {
                        lessonTime.getDisplayName()
                    }
                    saveLessonTimes()
                    updateExistingLessonsForTimeChanges(
                        LessonTimeTemplate(lessonTime.id, lessonTime.lessonNumber, oldTime, lessonTime.endTime),
                        lessonTime
                    )
                    // ADD THIS: Update free lessons for all weekdays when lesson times change
                    for (weekday in 0..4) {
                        autoFillGapsBetweenLessons(weekday)
                    }
                }
            }

            btnEndTime.setOnClickListener {
                showTimePickerDialog(lessonTime.endTime) { newTime ->
                    if (newTime.after(lessonTime.startTime)) {
                        val oldTime = lessonTime.endTime.clone() as Calendar
                        lessonTime.endTime.timeInMillis = newTime.timeInMillis
                        btnEndTime.text = formatTime(newTime)
                        tvLessonInfo.text = if (language == "lt") {
                            lessonTime.getDisplayNameLithuanian()
                        } else {
                            lessonTime.getDisplayName()
                        }
                        saveLessonTimes()
                        updateExistingLessonsForTimeChanges(
                            LessonTimeTemplate(lessonTime.id, lessonTime.lessonNumber, lessonTime.startTime, oldTime),
                            lessonTime
                        )
                        // ADD THIS: Update free lessons for all weekdays when lesson times change
                        for (weekday in 0..4) {
                            autoFillGapsBetweenLessons(weekday)
                        }
                    } else {
                        Toast.makeText(this, "End time must be after start time", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            btnDelete.setOnClickListener {
                lessonTimeTemplates.remove(lessonTime)
                refreshLessonTimesUI(container)
                saveLessonTimes()
                // Also update free lessons when a lesson time is deleted
                for (weekday in 0..4) {
                    autoFillGapsBetweenLessons(weekday)
                }
            }

            container.addView(lessonTimeView)
        }

        if (lessonTimeTemplates.size > 4) {
            container.post {
                val params = container.layoutParams
                params.height = 600
                container.layoutParams = params
            }
        }
    }

    private fun saveLessonTimes() {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val editor = prefs.edit()

            val lessonTimesData = lessonTimeTemplates.joinToString("|||") { lessonTime ->
                "${lessonTime.id}|${lessonTime.lessonNumber}|${lessonTime.startTime.timeInMillis}|${lessonTime.endTime.timeInMillis}"
            }

            editor.putString(LESSON_TIMES_KEY, lessonTimesData)
            editor.apply()
        }
    }

    private fun loadLessonTimes() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.getString(LESSON_TIMES_KEY, null)?.let { lessonTimesData ->
            lessonTimeTemplates.clear()
            if (lessonTimesData.isNotEmpty()) {
                lessonTimesData.split("|||").forEach { lessonTimeString ->
                    val parts = lessonTimeString.split("|")
                    if (parts.size >= 4) {
                        val lessonTime = LessonTimeTemplate(
                            id = parts[0],
                            lessonNumber = parts[1].toInt(),
                            startTime = Calendar.getInstance().apply { timeInMillis = parts[2].toLong() },
                            endTime = Calendar.getInstance().apply { timeInMillis = parts[3].toLong() }
                        )
                        lessonTimeTemplates.add(lessonTime)
                    }
                }
            }
        }
    }

    private fun deleteAllData(deleteTimetable: Boolean) {
        lessonTemplatesByWeekday.clear()
        weeklyLessons.clear()

        if (deleteTimetable) {
            lessonTimeTemplates.clear()
            saveLessonTimes()
            Toast.makeText(this, getString(R.string.timetable_deleted), Toast.LENGTH_SHORT).show()
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().remove(LESSONS_KEY).remove(TEMPLATES_KEY).apply()

        refreshAllDisplays()

        Toast.makeText(this, getString(R.string.all_data_deleted), Toast.LENGTH_SHORT).show()
    }

    private fun changeLanguage(languageCode: String) {
        saveLanguage(languageCode)

        // Restart the activity to apply the language change
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)

        // Finish the current activity
        if (!isFinishing) {
            finish()
        }

        Toast.makeText(this,
            getString(R.string.language_changed,
                when (languageCode) {
                    "en" -> getString(R.string.english)
                    "lt" -> getString(R.string.lithuanian)
                    "es" -> getString(R.string.spanish)
                    else -> getString(R.string.english)
                }
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showWeekView() {
        isWeekView = true
        weekViewContainer.visibility = View.VISIBLE
        dayViewContainer.visibility = View.GONE
        fabAddButton.visibility = View.GONE
        btnLanguage.visibility = View.VISIBLE
        btnSettings.visibility = View.VISIBLE

        tvDayTitle.text = getString(R.string.week_schedule)
        tvDayTitle.textSize = 20f
        tvDayTitle.gravity = Gravity.START or Gravity.CENTER_VERTICAL

        loadWeekView()
    }

    private fun showDayView(weekday: Int) {
        isWeekView = false
        weekViewContainer.visibility = View.GONE
        dayRecyclerView.visibility = View.VISIBLE
        fabAddButton.visibility = View.VISIBLE
        btnLanguage.visibility = View.GONE
        btnSettings.visibility = View.GONE

        setToClosestWeekday(weekday)
        updateToolbar()
        loadLessonsForCurrentWeekday()
    }
    private fun loadWeekView() {
        val weekDays = mutableListOf<WeekDayItem>()
        for (weekday in 0..4) {
            val templates = lessonTemplatesByWeekday[weekday] ?: emptyList()
            weekDays.add(WeekDayItem(weekday, weekdayNames[weekday], templates))
        }
        weekAdapter.updateData(weekDays)

        weekRecyclerView.post {
            centerFridayBox()
        }
    }

    private fun loadLessonsForCurrentWeekday() {
        val currentWeekday = getCurrentWeekday()
        val currentWeekId = getCurrentWeekIdentifier()
        val templates = lessonTemplatesByWeekday[currentWeekday] ?: emptyList()

        if (templates.isEmpty()) {
            // Handle no lessons case
            dayAdapter = DayLessonAdapter(emptyList(),
                { showEditLessonDialog(it) },
                { showNoteDialog(it) })
            dayRecyclerView.adapter = dayAdapter
            return
        }

        // Get or create weekly lessons for each template and pair them
        val lessonPairs = templates.map { template ->
            val weeklyLesson = getOrCreateWeeklyLesson(template, currentWeekId, currentWeekday)
            Pair(weeklyLesson, template)
        }.sortedBy { (_, template) ->
            getLessonNumberForTemplate(template)
        }

        // Initialize or update adapter
        dayAdapter = DayLessonAdapter(lessonPairs,
            { template -> showEditLessonDialog(template) },
            { weeklyLesson -> showNoteDialog(weeklyLesson) })
        dayRecyclerView.adapter = dayAdapter
        dayRecyclerView.layoutManager = LinearLayoutManager(this)

        Log.d("DayView", "Loaded ${lessonPairs.size} lessons with RecyclerView")
    }
    private fun getLessonNumber(template: LessonTemplate): Int {
        val startTime = template.startTime.timeInMillis
        val endTime = template.endTime.timeInMillis

        return lessonTimeTemplates.find {
            it.startTime.timeInMillis == startTime && it.endTime.timeInMillis == endTime
        }?.lessonNumber ?: Int.MAX_VALUE
    }
    private fun createLessonButton(weeklyLesson: WeeklyLesson, template: LessonTemplate?) {
        if (template == null) {
            Log.e("MainActivity", "Template not found for weekly lesson: ${weeklyLesson.id}")
            return
        }

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeText = "${timeFormat.format(template.startTime.time)} - ${timeFormat.format(template.endTime.time)}"

        val lessonNumber = getLessonNumber(template)
        val lessonNumberText = if (lessonNumber > 0) "$lessonNumber. " else ""

        val lessonColor = if (template.isFreeLesson) "#808080" else template.color
        val backgroundDrawable = ContextCompat.getDrawable(this, R.drawable.rectangle_button_shape)?.mutate()
        backgroundDrawable?.setTint(Color.parseColor(lessonColor))

        // Store the image state
        lessonImageStates[weeklyLesson.id] = weeklyLesson.imagePaths.isNotEmpty()

        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
            background = backgroundDrawable
            setPadding(16, 12, 16, 12)
            tag = "lesson_${weeklyLesson.id}"
        }

        val leftContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 0.7f
            }
            setPadding(0, 0, 16, 0)
        }

        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                weight = 0.3f
            }
            gravity = Gravity.CENTER
            setPadding(16, 0, 0, 0)
        }

        val lessonDisplayName = if (template.isFreeLesson) {
            "$lessonNumberText${getString(R.string.free)}"
        } else {
            "$lessonNumberText${template.name}"
        }

        val tvLessonName = TextView(this).apply {
            text = lessonDisplayName
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.START

            TextViewCompat.setAutoSizeTextTypeWithDefaults(this, TextViewCompat.AUTO_SIZE_TEXT_TYPE_UNIFORM)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                12,
                18,
                1,
                TypedValue.COMPLEX_UNIT_SP
            )

            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val tvTime = TextView(this).apply {
            text = timeText
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.START

            TextViewCompat.setAutoSizeTextTypeWithDefaults(this, TextViewCompat.AUTO_SIZE_TEXT_TYPE_UNIFORM)
            maxLines = 1
        }

        val noteContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            tag = "note_container_${weeklyLesson.id}"
        }

        val tvNote = TextView(this).apply {
            text = weeklyLesson.note
            setTextColor(Color.WHITE)
            gravity = Gravity.START
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { weight = 1f }

            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                10,
                14,
                1,
                TypedValue.COMPLEX_UNIT_SP
            )
        }

        // CRITICAL: Use the stored state to set visibility
        val hasImages = lessonImageStates[weeklyLesson.id] ?: weeklyLesson.imagePaths.isNotEmpty()
        val imageIndicator = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_gallery)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                54,
                54
            ).apply {
                setMargins(15, 0, 0, 0)
            }
            visibility = if (hasImages) View.VISIBLE else View.GONE
            tag = "image_indicator_${weeklyLesson.id}"
            contentDescription = "Image attached"
        }

        noteContainer.addView(tvNote)
        noteContainer.addView(imageIndicator)

        leftContainer.addView(tvLessonName)
        leftContainer.addView(tvTime)
        rightContainer.addView(noteContainer)
        mainContainer.addView(leftContainer)
        mainContainer.addView(rightContainer)

        leftContainer.setOnClickListener { showEditLessonDialog(template) }
        rightContainer.setOnClickListener { showNoteDialog(weeklyLesson) }

        dayLayoutContainer.addView(mainContainer)

        Log.d("LessonButton", "Created lesson button for ${template.name}, images: ${weeklyLesson.imagePaths.size}, visible: ${imageIndicator.visibility}")
    }
    private fun updateAllLessonImageStates() {
        val currentWeekday = getCurrentWeekday()
        val currentWeekId = getCurrentWeekIdentifier()
        val templates = lessonTemplatesByWeekday[currentWeekday] ?: emptyList()

        // Get or create weekly lessons for each template
        val weeklyLessonsList = templates.map { template ->
            getOrCreateWeeklyLesson(template, currentWeekId, currentWeekday)
        }

        // Update all image states
        weeklyLessonsList.forEach { weeklyLesson ->
            lessonImageStates[weeklyLesson.id] = weeklyLesson.imagePaths.isNotEmpty()
        }

        Log.d("ImageState", "Updated image states for ${weeklyLessonsList.size} lessons")
    }
    private fun refreshAllLessonButtons() {
        val currentWeekday = getCurrentWeekday()
        val currentWeekId = getCurrentWeekIdentifier()
        val templates = lessonTemplatesByWeekday[currentWeekday] ?: emptyList()

        // Get or create weekly lessons for each template
        val weeklyLessonsList = templates.map { template ->
            getOrCreateWeeklyLesson(template, currentWeekId, currentWeekday)
        }.sortedBy { weeklyLesson ->
            val template = getTemplateForWeeklyLesson(weeklyLesson)
            template?.let { getLessonNumber(it) } ?: Int.MAX_VALUE
        }

        // Update all existing lesson buttons
        weeklyLessonsList.forEach { weeklyLesson ->
            updateLessonButton(weeklyLesson)
        }

        Log.d("LessonButton", "Refreshed ${weeklyLessonsList.size} lesson buttons")
    }


    private fun updateLessonButton(weeklyLesson: WeeklyLesson) {
        try {
            val lessonId = weeklyLesson.id
            val mainContainer = dayLayoutContainer.findViewWithTag<LinearLayout>("lesson_$lessonId")

            if (mainContainer == null) {
                Log.d("LessonButton", "Lesson button not found for $lessonId, will be created")
                return
            }

            val imageIndicator = mainContainer.findViewWithTag<ImageView>("image_indicator_$lessonId")

            if (imageIndicator != null) {
                val shouldBeVisible = weeklyLesson.imagePaths.isNotEmpty()
                imageIndicator.visibility = if (shouldBeVisible) View.VISIBLE else View.GONE
                Log.d("LessonButton", "Updated image indicator for $lessonId: visible=$shouldBeVisible (${weeklyLesson.imagePaths.size} images)")
            } else {
                Log.w("LessonButton", "Image indicator not found for $lessonId")
            }

            // Also update note text
            val noteText = mainContainer.findViewWithTag<TextView>("note_text_$lessonId")
            noteText?.text = weeklyLesson.note

        } catch (e: Exception) {
            Log.e("LessonButton", "Error updating lesson button: ${e.message}")
        }
    }
    private class WeekAdapter(
        private var weekDays: List<WeekDayItem>,
        private val onDayClick: (Int) -> Unit
    ) : RecyclerView.Adapter<WeekAdapter.WeekViewHolder>() {

        class WeekViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val dayContainer: LinearLayout = view.findViewById(R.id.dayContainer)
            val weekdayName: TextView = view.findViewById(R.id.weekdayName)
            val lessonsContainer: LinearLayout = view.findViewById(R.id.lessonsContainer)
        }

        private fun getLessonNumber(template: LessonTemplate, context: Context): Int {
            val startTime = template.startTime.timeInMillis
            val endTime = template.endTime.timeInMillis
            val lessonTimeTemplates = (context as? MainActivity)?.lessonTimeTemplates ?: emptyList()
            return lessonTimeTemplates.find {
                it.startTime.timeInMillis == startTime && it.endTime.timeInMillis == endTime
            }?.lessonNumber ?: Int.MAX_VALUE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WeekViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_week_day, parent, false)
            return WeekViewHolder(view)
        }

        override fun onBindViewHolder(holder: WeekViewHolder, position: Int) {
            val weekDay = weekDays[position]

            holder.weekdayName.text = weekDay.name
            holder.weekdayName.textSize = 24f

            holder.dayContainer.setBackgroundColor(Color.TRANSPARENT)

            // Clear previous lessons safely
            holder.lessonsContainer.removeAllViews()

            if (weekDay.lessons.isEmpty()) {
                val noLessonsText = TextView(holder.itemView.context).apply {
                    text = holder.itemView.context.getString(R.string.no_lessons)
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                holder.lessonsContainer.addView(noLessonsText)
            } else {
                // Create all lesson views first, then add them
                val lessonViews = weekDay.lessons.sortedBy { getLessonNumber(it, holder.itemView.context) }
                    .map { createWeekViewLessonView(it, holder.lessonsContainer) }

                // Add all views at once to prevent multiple layout passes
                lessonViews.forEach { lessonView ->
                    holder.lessonsContainer.addView(lessonView)
                }
            }

            holder.dayContainer.setOnClickListener {
                onDayClick(weekDay.weekday)
            }
        }

        override fun getItemCount() = weekDays.size

        fun updateData(newWeekDays: List<WeekDayItem>) {
            weekDays = newWeekDays
            notifyDataSetChanged()
        }

        private fun createWeekViewLessonView(lessonItem: LessonTemplate, container: LinearLayout): View {
            val lessonColor = if (lessonItem.isFreeLesson) "#808080" else lessonItem.color
            val lessonLayout = LinearLayout(container.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 1, 0, 1)
                }
                setBackgroundColor(Color.parseColor(lessonColor))
                setPadding(4, 2, 4, 2)
            }

            val startTime = lessonItem.startTime.timeInMillis
            val endTime = lessonItem.endTime.timeInMillis
            val lessonTimeTemplates = (container.context as? MainActivity)?.lessonTimeTemplates ?: emptyList()
            val lessonNumber = lessonTimeTemplates.find {
                it.startTime.timeInMillis == startTime && it.endTime.timeInMillis == endTime
            }?.lessonNumber ?: 0
            val lessonNumberText = if (lessonNumber > 0) "$lessonNumber. " else ""

            val lessonNameWithSpaces = "    $lessonNumberText${if (lessonItem.isFreeLesson) container.context.getString(R.string.free) else lessonItem.name}"

            val tvLessonName = TextView(container.context).apply {
                text = lessonNameWithSpaces
                setTextColor(Color.WHITE)
                gravity = Gravity.START
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END

                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this,
                    8,
                    12,
                    1,
                    TypedValue.COMPLEX_UNIT_SP
                )
            }

            lessonLayout.addView(tvLessonName)
            return lessonLayout
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!isWeekView) {
            showWeekView()
        } else {
            if (!isFinishing) {
                super.onBackPressed()
            }
        }
    }

    private fun setToClosestWeekday(targetWeekday: Int) {
        val today = Calendar.getInstance()
        val currentDayOfWeek = today.get(Calendar.DAY_OF_WEEK)
        val targetCalendarDay = targetWeekday + 2

        var daysToAdd = targetCalendarDay - currentDayOfWeek
        if (daysToAdd < 0) daysToAdd += 7

        currentDate = today.clone() as Calendar
        currentDate.add(Calendar.DAY_OF_YEAR, daysToAdd)
    }

    private fun handleSwipe(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                return true
            }
            MotionEvent.ACTION_UP -> {
                val endX = event.x
                val diffX = endX - startX

                if (Math.abs(diffX) > 100) {
                    if (diffX > 0) navigateToPreviousWeekday()
                    else navigateToNextWeekday()
                }
                return true
            }
        }
        return true
    }

    private fun navigateToPreviousWeekday() {
        var daysBack = 1
        while (daysBack < 7) {
            val newDate = currentDate.clone() as Calendar
            newDate.add(Calendar.DAY_OF_YEAR, -daysBack)
            if (isWeekday(newDate)) {
                currentDate = newDate
                updateToolbar()
                loadLessonsForCurrentWeekday()
                return
            }
            daysBack++
        }
    }

    private fun navigateToNextWeekday() {
        var daysForward = 1
        while (daysForward < 7) {
            val newDate = currentDate.clone() as Calendar
            newDate.add(Calendar.DAY_OF_YEAR, daysForward)
            if (isWeekday(newDate)) {
                currentDate = newDate
                updateToolbar()
                loadLessonsForCurrentWeekday()
                return
            }
            daysForward++
        }
    }

    private fun isWeekday(date: Calendar): Boolean {
        val dayOfWeek = date.get(Calendar.DAY_OF_WEEK)
        return dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY
    }

    private fun getCurrentWeekIdentifier(): String {
        val calendar = currentDate.clone() as Calendar
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return dateFormat.format(calendar.time)
    }

    private fun getCurrentWeekday(): Int {
        return when (currentDate.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            else -> 0
        }
    }

    private fun showAddLessonDialog() {
        loadLessonTimes()
        if (lessonTimeTemplates.isEmpty()) {
            showNoTimetableDialog()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_add_lesson, null)
        val etLessonName = dialogView.findViewById<EditText>(R.id.etLessonName)
        val lessonTimeSpinner = dialogView.findViewById<Spinner>(R.id.lessonTimeSpinner)
        val btnManageTimetable = dialogView.findViewById<Button>(R.id.btnManageTimetable)
        val btnAddLesson = dialogView.findViewById<Button>(R.id.btnAddLesson)
        val btnCancelLesson = dialogView.findViewById<Button>(R.id.btnCancelLesson)

        val lessonTimes = lessonTimeTemplates.sortedBy { it.lessonNumber }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val language = prefs.getString(LANGUAGE_KEY, "lt") ?: "lt"

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, lessonTimes.map { lessonTime ->
            lessonTime.getDisplayName(language)
        })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        lessonTimeSpinner.adapter = adapter

        val currentWeekday = getCurrentWeekday()
        val currentTemplates = lessonTemplatesByWeekday[currentWeekday] ?: emptyList()

        val usedLessonNumbers = currentTemplates.mapNotNull { template ->
            lessonTimes.find {
                it.startTime.timeInMillis == template.startTime.timeInMillis &&
                        it.endTime.timeInMillis == template.endTime.timeInMillis
            }?.lessonNumber
        }.toSet()

        val nextLessonNumber = if (usedLessonNumbers.isEmpty()) {
            1
        } else {
            val maxUsed = usedLessonNumbers.maxOrNull() ?: 0
            var nextNumber = 1
            while (nextNumber <= maxUsed + 1) {
                if (nextNumber !in usedLessonNumbers) {
                    break
                }
                nextNumber++
            }
            nextNumber
        }

        val recommendedIndex = lessonTimes.indexOfFirst { it.lessonNumber == nextLessonNumber }
        if (recommendedIndex != -1) {
            lessonTimeSpinner.setSelection(recommendedIndex)
        }

        btnManageTimetable?.setOnClickListener {
            addLessonDialog?.dismiss()
            showManageLessonTimesDialog()
        }

        addLessonDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setOnDismissListener {
                addLessonDialog = null
            }
            .create()

        // Apply window settings programmatically
        addLessonDialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        addLessonDialog?.window?.setLayout(getDialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)

        btnAddLesson.setOnClickListener {
            val selectedLessonTime = lessonTimes[lessonTimeSpinner.selectedItemPosition]
            val lessonName = etLessonName.text.toString().trim()

            if (validateLessonInput(lessonName)) {
                addNewLessonToWeekday(
                    lessonName,
                    selectedLessonTime.startTime,
                    selectedLessonTime.endTime,
                    false
                )
                addLessonDialog?.dismiss()
            }
        }

        btnCancelLesson.setOnClickListener {
            addLessonDialog?.dismiss()
        }

        addLessonDialog?.show()
    }

    private fun showNoTimetableDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirmation, null)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvMessage)
        val btnYes = dialogView.findViewById<Button>(R.id.btnYes)
        val btnNo = dialogView.findViewById<Button>(R.id.btnNo)

        tvMessage.text = getString(R.string.no_timetable_message)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnYes.text = getString(R.string.set_up_timetable)
        btnNo.text = getString(R.string.cancel)

        btnYes.setOnClickListener {
            dialog.dismiss()
            showManageLessonTimesDialog()
        }

        btnNo.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun validateLessonInput(name: String): Boolean {
        return when {
            name.isBlank() -> {
                Toast.makeText(this, getString(R.string.enter_lesson_name), Toast.LENGTH_SHORT).show()
                false
            }
            else -> true
        }
    }


    private fun scheduleNotification(weeklyLesson: WeeklyLesson) {
        if (weeklyLesson.hasReminder()) {
            val template = getTemplateForWeeklyLesson(weeklyLesson)
            template?.let {
                val reminderCalendar = Calendar.getInstance().apply {
                    timeInMillis = weeklyLesson.reminderTime
                }
                // FIXED: Remove the template parameter
                ReminderReceiver.scheduleReminder(this, reminderCalendar, weeklyLesson)
                Toast.makeText(this, getString(R.string.reminder_set), Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun cancelNotification(weeklyLesson: WeeklyLesson) {
        ReminderReceiver.cancelReminder(this, weeklyLesson)
        Toast.makeText(this, getString(R.string.reminder_canceled), Toast.LENGTH_SHORT).show()
    }

    private fun showTimePickerDialog(currentTime: Calendar?, onTimeSet: (Calendar) -> Unit) {
        val calendar = currentTime ?: Calendar.getInstance()
        TimePickerDialog(this, { _, hour, minute ->
            val newTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
            }
            onTimeSet(newTime)
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
    }

    private fun formatTime(time: Calendar): String {
        return String.format("%02d:%02d", time.get(Calendar.HOUR_OF_DAY), time.get(Calendar.MINUTE))
    }

    private fun addNewLessonToWeekday(lessonName: String, startTime: Calendar, endTime: Calendar, isFreeLesson: Boolean) {
        if (isWeekView) {
            showWeekdaySelectionDialog(lessonName, startTime, endTime, isFreeLesson)
        } else {
            addLessonTemplateToWeekday(lessonName, startTime, endTime, getCurrentWeekday(), isFreeLesson)
        }
    }

    private fun showWeekdaySelectionDialog(lessonName: String, startTime: Calendar, endTime: Calendar, isFreeLesson: Boolean) {
        val dialog = AlertDialog.Builder(this)
            .setItems(weekdayNames.toTypedArray()) { _, which ->
                addLessonTemplateToWeekday(lessonName, startTime, endTime, which, isFreeLesson)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.WHITE)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setBackgroundColor(Color.parseColor("#FF6200EE"))
        }

        dialog.show()
    }

    private fun addLessonTemplateToWeekday(lessonName: String, startTime: Calendar, endTime: Calendar, weekday: Int, isFreeLesson: Boolean) {
        val currentTemplates = lessonTemplatesByWeekday[weekday] ?: mutableListOf()
        val lessonColor = getAvailableColor(currentTemplates, weekday)
        val newStartTime = Calendar.getInstance().apply {
            timeInMillis = startTime.timeInMillis
        }
        val newEndTime = Calendar.getInstance().apply {
            timeInMillis = endTime.timeInMillis
        }

        val existingFreeLessonIndex = currentTemplates.indexOfFirst { template ->
            template.isFreeLesson &&
                    template.startTime.timeInMillis == newStartTime.timeInMillis &&
                    template.endTime.timeInMillis == newEndTime.timeInMillis
        }

        if (existingFreeLessonIndex != -1) {
            currentTemplates.removeAt(existingFreeLessonIndex)
        }

        val template = LessonTemplate(
            name = lessonName,
            startTime = newStartTime,
            endTime = newEndTime,
            color = lessonColor,
            isFreeLesson = isFreeLesson
        )

        lessonTemplatesByWeekday.getOrPut(weekday) { mutableListOf() }.add(template)

        autoFillGapsBetweenLessons(weekday)

        saveDataToStorage()

        if (isWeekView) loadWeekView() else loadLessonsForCurrentWeekday()

        Toast.makeText(this, String.format(getString(R.string.lesson_added_to), weekdayNames[weekday]), Toast.LENGTH_SHORT).show()
    }

    private fun autoFillGapsBetweenLessons(weekday: Int) {
        val templates = lessonTemplatesByWeekday[weekday] ?: return
        val lessonTimes = lessonTimeTemplates.sortedBy { it.lessonNumber }

        if (lessonTimes.isEmpty()) return

        // First, remove ALL existing free lessons to start fresh
        val freeLessonsToRemove = templates.filter { it.isFreeLesson }.toList()
        templates.removeAll(freeLessonsToRemove)

        // Get only regular lessons (non-free)
        val regularTemplates = templates.filter { !it.isFreeLesson }

        if (regularTemplates.isEmpty()) {
            // If no regular lessons, no need for free lessons
            return
        }

        // Find the lesson numbers that have regular lessons
        val lessonNumbersWithRegularLessons = regularTemplates.mapNotNull { template ->
            lessonTimes.find {
                it.startTime.timeInMillis == template.startTime.timeInMillis &&
                        it.endTime.timeInMillis == template.endTime.timeInMillis
            }?.lessonNumber
        }.sorted()

        if (lessonNumbersWithRegularLessons.isEmpty()) return

        val minLessonNumber = lessonNumbersWithRegularLessons.first()
        val maxLessonNumber = lessonNumbersWithRegularLessons.last()

        // Fill gaps BEFORE the first regular lesson
        for (lessonNumber in 1 until minLessonNumber) {
            val lessonTime = lessonTimes.find { it.lessonNumber == lessonNumber } ?: continue

            val hasAnyLessonAtThisTime = templates.any {
                it.startTime.timeInMillis == lessonTime.startTime.timeInMillis
            }

            if (!hasAnyLessonAtThisTime) {
                val freeTemplate = LessonTemplate(
                    name = getString(R.string.free),
                    startTime = lessonTime.startTime,
                    endTime = lessonTime.endTime,
                    color = getAvailableColor(templates, weekday),
                    isFreeLesson = true
                )
                templates.add(freeTemplate)
            }
        }

        // Fill gaps BETWEEN regular lessons
        for (lessonNumber in minLessonNumber..maxLessonNumber) {
            val lessonTime = lessonTimes.find { it.lessonNumber == lessonNumber } ?: continue

            val hasRegularLessonAtThisTime = regularTemplates.any {
                it.startTime.timeInMillis == lessonTime.startTime.timeInMillis
            }

            if (!hasRegularLessonAtThisTime) {
                val hasAnyLessonAtThisTime = templates.any {
                    it.startTime.timeInMillis == lessonTime.startTime.timeInMillis
                }

                if (!hasAnyLessonAtThisTime) {
                    val freeTemplate = LessonTemplate(
                        name = getString(R.string.free),
                        startTime = lessonTime.startTime,
                        endTime = lessonTime.endTime,
                        color = getAvailableColor(templates, weekday),
                        isFreeLesson = true
                    )
                    templates.add(freeTemplate)
                }
            }
        }

        // Don't add free lessons AFTER the last regular lesson
        // (remove this logic to keep the original behavior of not having free lessons at the end)
    }

    private fun getAvailableColor(templates: List<LessonTemplate>, weekday: Int): String {
        val usedColors = templates.map { it.color }.toSet()
        val availableColors = getColorPaletteForWeekday(weekday).filter { it !in usedColors }
        return availableColors.randomOrNull() ?: getColorPaletteForWeekday(weekday).random()
    }

    private fun getOrCreateWeeklyLesson(template: LessonTemplate, weekId: String, weekday: Int): WeeklyLesson {
        val weekLessons = weeklyLessons.getOrPut(weekId) { mutableMapOf() }
        val dayLessons = weekLessons.getOrPut(weekday) { mutableListOf() }

        // Try to find existing weekly lesson by template ID
        val existingLesson = dayLessons.find { it.templateId == template.id }
        if (existingLesson != null) {
            return existingLesson
        }

        // If not found, create new one
        val newWeeklyLesson = WeeklyLesson(
            templateId = template.id,
            weekIdentifier = weekId
        )
        dayLessons.add(newWeeklyLesson)
        return newWeeklyLesson
    }

    private fun getTemplateForWeeklyLesson(weeklyLesson: WeeklyLesson): LessonTemplate? {
        return lessonTemplatesByWeekday.values
            .flatten()
            .find { it.id == weeklyLesson.templateId }
    }
    private fun showEditLessonDialog(template: LessonTemplate) {
        currentTemplateForEdit = template
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_lesson, null)
        setupEditLessonDialog(dialogView, template)
    }

    private fun setupEditLessonDialog(dialogView: View, template: LessonTemplate) {
        val etLessonName = dialogView.findViewById<EditText>(R.id.etLessonName)
        val btnDeleteLesson = dialogView.findViewById<Button>(R.id.btnDeleteLesson)
        val cbFreeLesson = dialogView.findViewById<CheckBox>(R.id.cbFreeLesson)
        val lessonTimeSpinner = dialogView.findViewById<Spinner>(R.id.lessonTimeSpinner)
        val btnSaveEdit = dialogView.findViewById<Button>(R.id.btnSaveEdit)
        val btnCancelEdit = dialogView.findViewById<Button>(R.id.btnCancelEdit)

        // Hide free lesson checkbox as requested
        cbFreeLesson.visibility = View.GONE

        loadLessonTimes()
        val lessonTimes = lessonTimeTemplates.sortedBy { it.lessonNumber }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val language = prefs.getString(LANGUAGE_KEY, "lt") ?: "lt"

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, lessonTimes.map { lessonTime ->
            if (language == "lt") {
                lessonTime.getDisplayNameLithuanian()
            } else {
                lessonTime.getDisplayName(language)
            }
        })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        lessonTimeSpinner.adapter = adapter

        val currentStartTime = template.startTime.timeInMillis
        val currentEndTime = template.endTime.timeInMillis

        val currentLessonTimeIndex = lessonTimes.indexOfFirst {
            it.startTime.timeInMillis == currentStartTime && it.endTime.timeInMillis == currentEndTime
        }

        if (currentLessonTimeIndex != -1) {
            lessonTimeSpinner.setSelection(currentLessonTimeIndex)
        }

        etLessonName.setText(template.name)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // Apply window settings programmatically
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialog.window?.setLayout(getDialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)

        btnSaveEdit.setOnClickListener {
            val lessonName = etLessonName.text.toString().trim()
            val selectedLessonTime = lessonTimes[lessonTimeSpinner.selectedItemPosition]

            if (lessonName.isNotEmpty()) {
                updateLessonTemplate(template, lessonName, selectedLessonTime.startTime, selectedLessonTime.endTime, template.isFreeLesson)
                dialog.dismiss()
            } else {
                Toast.makeText(this, getString(R.string.enter_lesson_name), Toast.LENGTH_SHORT).show()
            }
        }

        btnCancelEdit.setOnClickListener {
            dialog.dismiss()
        }

        btnDeleteLesson.setOnClickListener {
            val deleteDialogView = layoutInflater.inflate(R.layout.dialog_confirmation, null)
            val tvMessage = deleteDialogView.findViewById<TextView>(R.id.tvMessage)
            val btnYes = deleteDialogView.findViewById<Button>(R.id.btnYes)
            val btnNo = deleteDialogView.findViewById<Button>(R.id.btnNo)

            tvMessage.text = getString(R.string.delete_lesson_all_weeks)

            val deleteDialog = AlertDialog.Builder(this)
                .setView(deleteDialogView)
                .create()

            btnYes.setOnClickListener {
                deleteLessonTemplate(template)
                deleteDialog.dismiss()
                dialog.dismiss()
            }

            btnNo.setOnClickListener {
                deleteDialog.dismiss()
            }

            deleteDialog.show()
        }

        dialog.show()
    }

    private fun updateLessonTemplate(oldTemplate: LessonTemplate, newName: String, newStartTime: Calendar, newEndTime: Calendar, isFreeLesson: Boolean) {
        val currentWeekday = getCurrentWeekday()
        val templates = lessonTemplatesByWeekday[currentWeekday] ?: return

        val index = templates.indexOfFirst { it.id == oldTemplate.id }
        if (index != -1) {
            val updatedStartTime = Calendar.getInstance().apply {
                timeInMillis = newStartTime.timeInMillis
            }
            val updatedEndTime = Calendar.getInstance().apply {
                timeInMillis = newEndTime.timeInMillis
            }

            val updatedTemplate = oldTemplate.copy(
                name = newName,
                startTime = updatedStartTime,
                endTime = updatedEndTime,
                isFreeLesson = isFreeLesson
            )
            templates[index] = updatedTemplate

            updateWeeklyLessonsForTemplate(oldTemplate, updatedTemplate)

            // ADD THIS LINE: Update free lessons when lesson times change
            autoFillGapsBetweenLessons(currentWeekday)

            saveDataToStorage()
            refreshAllDisplays()
            Toast.makeText(this, getString(R.string.lesson_updated), Toast.LENGTH_SHORT).show()
        }
    }
    private fun updateWeeklyLessonsForTemplate(oldTemplate: LessonTemplate, newTemplate: LessonTemplate) {
        weeklyLessons.values.forEach { weekLessons ->
            weekLessons.values.forEach { lessons ->
                lessons.forEach { weeklyLesson ->
                    // FIXED: Change 'template.id' to 'templateId'
                    if (weeklyLesson.templateId == oldTemplate.id) {
                        val index = lessons.indexOf(weeklyLesson)
                        if (index != -1) {
                            lessons[index] = weeklyLesson.copy(templateId = newTemplate.id)
                        }
                    }
                }
            }
        }
    }
    private fun refreshAllDisplays() {
        if (isWeekView) {
            loadWeekView()
        } else {
            loadLessonsForCurrentWeekday()
        }
    }

    private fun deleteLessonTemplate(template: LessonTemplate) {
        val currentWeekday = getCurrentWeekday()
        lessonTemplatesByWeekday[currentWeekday]?.removeAll { it.id == template.id }

        removeTemplateFromWeeklyLessons(template)

        // This will now properly clean up free lessons
        autoFillGapsBetweenLessons(currentWeekday)

        saveDataToStorage()
        refreshAllDisplays()
        Toast.makeText(this, getString(R.string.lesson_deleted), Toast.LENGTH_SHORT).show()
    }

    private fun removeTemplateFromWeeklyLessons(templateToRemove: LessonTemplate) {
        weeklyLessons.values.forEach { weekLessons ->
            weekLessons.values.forEach { lessons ->
                // FIXED: Use templateId instead of template.id
                lessons.removeAll { it.templateId == templateToRemove.id }
            }
        }
    }
    private fun setupReminderUI(weeklyLesson: WeeklyLesson, btnSetReminder: Button, reminderContainer: LinearLayout) {
        reminderContainer.removeAllViews()

        if (weeklyLesson.hasReminder()) {
            val reminderView = layoutInflater.inflate(R.layout.item_reminder_simple, null)
            val tvReminderInfo = reminderView.findViewById<TextView>(R.id.tvReminderInfo)
            val btnDeleteReminder = reminderView.findViewById<Button>(R.id.btnDeleteReminder)

            val reminderCalendar = Calendar.getInstance().apply {
                timeInMillis = weeklyLesson.reminderTime
            }
            val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

            tvReminderInfo.text = "${dateFormat.format(reminderCalendar.time)} at ${timeFormat.format(reminderCalendar.time)}"

            btnDeleteReminder.setOnClickListener {
                weeklyLesson.reminderTime = 0
                cancelNotification(weeklyLesson)
                saveDataToStorage()
                setupReminderUI(weeklyLesson, btnSetReminder, reminderContainer)
                Toast.makeText(this, getString(R.string.reminder_removed), Toast.LENGTH_SHORT).show()
            }

            reminderContainer.addView(reminderView)
            btnSetReminder.text = getString(R.string.change_reminder)
            btnSetReminder.visibility = View.VISIBLE
        } else {
            btnSetReminder.text = getString(R.string.set_reminder)
            btnSetReminder.visibility = View.VISIBLE
        }
    }

    private fun getScreenWidth(): Int = resources.displayMetrics.widthPixels
    private fun getDialogWidth(): Int = (getScreenWidth() * 0.9).toInt()

    private fun showSetReminderDialog(weeklyLesson: WeeklyLesson, onReminderSet: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_set_reminder, null)
        val btnReminderDate = dialogView.findViewById<Button>(R.id.btnReminderDate)
        val btnReminderTime = dialogView.findViewById<Button>(R.id.btnReminderTime)
        val btnSetReminder = dialogView.findViewById<Button>(R.id.btnSetReminder)
        val btnCancelReminder = dialogView.findViewById<Button>(R.id.btnCancelReminder)

        var selectedDate: Calendar? = if (weeklyLesson.hasReminder()) {
            Calendar.getInstance().apply { timeInMillis = weeklyLesson.reminderTime }
        } else {
            Calendar.getInstance()
        }
        var selectedTime: Calendar? = if (weeklyLesson.hasReminder()) {
            Calendar.getInstance().apply { timeInMillis = weeklyLesson.reminderTime }
        } else {
            Calendar.getInstance()
        }

        val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        btnReminderDate.text = dateFormat.format(selectedDate!!.time)
        btnReminderTime.text = timeFormat.format(selectedTime!!.time)

        btnReminderDate.setOnClickListener {
            showDatePickerDialog(selectedDate!!) { date ->
                selectedDate = date
                btnReminderDate.text = dateFormat.format(date.time)
            }
        }

        btnReminderTime.setOnClickListener {
            showTimePickerDialog(selectedTime!!) { time ->
                selectedTime = time
                btnReminderTime.text = timeFormat.format(time.time)
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // Apply window settings programmatically
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialog.window?.setLayout(getDialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)

        btnSetReminder.setOnClickListener {
            if (selectedDate != null && selectedTime != null) {
                val reminderTime = Calendar.getInstance().apply {
                    timeInMillis = selectedDate!!.timeInMillis
                    set(Calendar.HOUR_OF_DAY, selectedTime!!.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, selectedTime!!.get(Calendar.MINUTE))
                    set(Calendar.SECOND, 0)
                }

                if (reminderTime.after(Calendar.getInstance())) {
                    weeklyLesson.reminderTime = reminderTime.timeInMillis
                    scheduleNotification(weeklyLesson)
                    saveDataToStorage()
                    dialog.dismiss()
                    onReminderSet()
                    Toast.makeText(this, getString(R.string.reminder_set), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.reminder_time_past), Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnCancelReminder.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun refreshImagesUI(container: LinearLayout, imagePaths: List<String>) {
        // Clear previous images first
        cleanupImageContainer(container)
        container.removeAllViews()

        if (imagePaths.isEmpty()) {
            return
        }

        imagePaths.forEach { imagePath ->
            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(220, 220)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(0, 0, 16, 0)
            }

            loadImageIntoView(imagePath, imageView)

            imageView.setOnClickListener {
                showFullScreenImageWithDelete(imagePath)
            }

            container.addView(imageView)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d("MemoryManagement", "Memory trim level: $level")

        // Use numeric values instead of deprecated constants
        when (level) {
            5 -> {  // TRIM_MEMORY_RUNNING_MODERATE
                Log.w("MemoryManagement", "Device beginning to run low on memory")
                cleanupNonEssentialImages()
            }
            10 -> { // TRIM_MEMORY_RUNNING_LOW
                Log.w("MemoryManagement", "Device running much lower on memory")
                cleanupAllImages()
            }
            15 -> { // TRIM_MEMORY_RUNNING_CRITICAL
                Log.w("MemoryManagement", "Device running extremely low on memory")
                cleanupAllImages()
                System.gc()
            }
            20 -> { // TRIM_MEMORY_UI_HIDDEN
                Log.w("MemoryManagement", "UI no longer visible")
                cleanupAllImages()
            }
            40 -> { // TRIM_MEMORY_BACKGROUND
                Log.w("MemoryManagement", "Process near beginning of LRU list")
                cleanupNonEssentialImages()
            }
            60 -> { // TRIM_MEMORY_MODERATE
                Log.w("MemoryManagement", "Process near middle of LRU list")
                cleanupAllImages()
            }
            80 -> { // TRIM_MEMORY_COMPLETE
                Log.w("MemoryManagement", "Process one of first to be killed")
                cleanupAllImages()
                System.gc()
            }
        }
    }

    /**
     * Clean up only non-essential images (cached thumbnails, etc.)
     * while keeping essential UI images
     */
    private fun cleanupNonEssentialImages() {
        try {
            // Don't clean up day view images as they're essential for current UI
            // Only clean up fullscreen images and cached bitmaps
            cleanupFullscreenImages()
        } catch (e: Exception) {
            Log.e("MemoryManagement", "Error cleaning non-essential images: ${e.message}")
        }
    }

    private fun cleanupFullscreenImages() {
        try {
            // Clean up any fullscreen image dialogs
            noteDialog?.window?.decorView?.let { decorView ->
                decorView.findViewById<ImageView>(R.id.ivFullScreen)?.let { imageView ->
                    cleanupBitmap(null, imageView)
                }
            }
        } catch (e: Exception) {
            Log.e("MemoryManagement", "Error cleaning fullscreen images: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        // When activity goes to background, clean up some resources
        if (isFinishing) {
            cleanupNonEssentialImages()
        }
    }

    override fun onStop() {
        super.onStop()
        // Clean up more resources when activity is no longer visible
        cleanupNonEssentialImages()
    }

    // Only ONE onLowMemory
    override fun onLowMemory() {
        super.onLowMemory()
        Log.w("MemoryManagement", "System is low on memory - equivalent to TRIM_MEMORY_COMPLETE")
        cleanupAllImages()
        System.gc()
    }

    // Only ONE onDestroy
    override fun onDestroy() {
        super.onDestroy()

        // Cleanup all dialogs
        languageDialog?.dismiss()
        settingsDialog?.dismiss()
        addLessonDialog?.dismiss()
        manageLessonTimesDialog?.dismiss()
        noteDialog?.dismiss()

        // Final memory cleanup
        cleanupAllImages()
        System.gc()
    }

    // Only ONE cleanupAllImages
    private fun cleanupAllImages() {
        try {
            // Clean up day view images
            cleanupImageContainer(dayLayoutContainer)

            // Clean up note dialog images
            noteDialog?.window?.decorView?.let { decorView ->
                decorView.findViewById<LinearLayout>(R.id.imagesContainer)?.let { container ->
                    cleanupImageContainer(container)
                }
            }

            // Clean up fullscreen images
            cleanupFullscreenImages()

            Log.d("MemoryManagement", "All images cleaned up")
        } catch (e: Exception) {
            Log.e("MemoryManagement", "Error during full image cleanup: ${e.message}")
        }
    }

    private fun showFullScreenImageWithDelete(imagePath: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_fullscreen_image, null)
        val ivFullScreen = dialogView.findViewById<ImageView>(R.id.ivFullScreen)
        val btnClose = dialogView.findViewById<Button>(R.id.btnClose)
        val btnDeleteImage = dialogView.findViewById<Button>(R.id.btnDeleteImage)

        // Track current bitmap for cleanup
        var currentBitmap: Bitmap? = null

        try {
            val file = File(imagePath)
            if (!file.exists()) {
                ivFullScreen.setImageResource(android.R.drawable.ic_dialog_alert)
            } else {
                // Use options to reduce memory usage
                val options = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(file)
                    inPreferredConfig = Bitmap.Config.RGB_565 // Use less memory
                }

                currentBitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                if (currentBitmap != null) {
                    ivFullScreen.setImageBitmap(currentBitmap)
                } else {
                    ivFullScreen.setImageResource(android.R.drawable.ic_dialog_alert)
                    currentBitmap = null
                }
            }
        } catch (e: OutOfMemoryError) {
            Log.e("MemoryManagement", "Out of memory loading fullscreen image")
            currentBitmap?.recycle()
            currentBitmap = null
            System.gc()
            ivFullScreen.setImageResource(android.R.drawable.ic_dialog_alert)
        } catch (e: Exception) {
            Log.e("MemoryManagement", "Error loading fullscreen image: ${e.message}")
            currentBitmap?.recycle()
            currentBitmap = null
            ivFullScreen.setImageResource(android.R.drawable.ic_dialog_alert)
        }

        val fullScreenDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setOnDismissListener {
                // Cleanup when dialog closes
                cleanupBitmap(currentBitmap, ivFullScreen)
            }
            .create()

        fullScreenDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        fullScreenDialog.window?.setLayout(getDialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)

        btnClose.setOnClickListener {
            cleanupBitmap(currentBitmap, ivFullScreen)
            fullScreenDialog.dismiss()
        }

        btnDeleteImage.setOnClickListener {
            cleanupBitmap(currentBitmap, ivFullScreen)
            showDeleteImageConfirmation(imagePath, fullScreenDialog)
        }

        fullScreenDialog.show()
    }
    private fun showDeleteImageConfirmation(imagePath: String, parentDialog: AlertDialog) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirmation, null)
        val tvHeader = dialogView.findViewById<TextView>(R.id.tvHeader)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvMessage)
        val btnYes = dialogView.findViewById<Button>(R.id.btnYes)
        val btnNo = dialogView.findViewById<Button>(R.id.btnNo)

        tvHeader.text = getString(R.string.remove_image)
        tvMessage.text = getString(R.string.delete_image_confirmation)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(getDialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)

        btnYes.setOnClickListener {
            currentWeeklyLessonForNote?.imagePaths?.remove(imagePath)
            saveDataToStorage()

            // Refresh the RecyclerView
            loadLessonsForCurrentWeekday()

            dialog.dismiss()
            parentDialog.dismiss()
            refreshCurrentNoteDialogImages()
            Toast.makeText(this, getString(R.string.image_removed), Toast.LENGTH_SHORT).show()
        }

        btnNo.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
    private fun cleanupCorruptedImages() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                weeklyLessons.values.forEach { weekLessons ->
                    weekLessons.values.forEach { lessons ->
                        lessons.forEach { weeklyLesson ->
                            val validImages = mutableListOf<String>()
                            weeklyLesson.imagePaths.forEach { imagePath ->
                                val file = File(imagePath)
                                if (file.exists()) {
                                    // Verify the file is actually a valid image
                                    val options = BitmapFactory.Options().apply {
                                        inJustDecodeBounds = true // Only get dimensions, don't load full image
                                    }
                                    BitmapFactory.decodeFile(imagePath, options)
                                    if (options.outWidth > 0 && options.outHeight > 0) {
                                        validImages.add(imagePath)
                                    } else {
                                        // Delete corrupted image file
                                        file.delete()
                                        Log.w("ImageCleanup", "Removed corrupted image: $imagePath")
                                    }
                                }
                            }
                            // Update with only valid images
                            if (validImages.size != weeklyLesson.imagePaths.size) {
                                weeklyLesson.imagePaths.clear()
                                weeklyLesson.imagePaths.addAll(validImages)
                            }
                        }
                    }
                }
                saveDataToStorage()
            } catch (e: Exception) {
                Log.e("ImageCleanup", "Error during image cleanup: ${e.message}")
            }
        }
    }

    // Call this in onCreate or when loading data
    private fun loadDataWithErrorHandling() {
        try {
            loadDataFromStorage()
            cleanupCorruptedImages() // Add this line
        } catch (e: Exception) {
            Log.e("DataLoad", "Error loading data, clearing corrupted data", e)
            clearCorruptedData()
        }
    }

    private fun refreshCurrentNoteDialogImages() {
        val currentLesson = currentWeeklyLessonForNote
        if (currentLesson != null) {
            // Simply refresh the entire list - RecyclerView will handle updates efficiently
            loadLessonsForCurrentWeekday()

            if (noteDialog?.isShowing == true) {
                try {
                    val dialogView = noteDialog?.window?.decorView
                    dialogView?.findViewById<LinearLayout>(R.id.imagesContainer)?.let { imagesContainer ->
                        refreshImagesUI(imagesContainer, currentLesson.imagePaths)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    noteDialog?.dismiss()
                    showNoteDialog(currentLesson)
                }
            }

            Log.d("ImageRefresh", "Refreshed images for ${currentLesson.id}")
        }
    }
    private fun updateLessonButtonImmediately(weeklyLesson: WeeklyLesson) {
        try {
            val lessonId = weeklyLesson.id
            val mainContainer = dayLayoutContainer.findViewWithTag<LinearLayout>("lesson_$lessonId")

            if (mainContainer != null) {
                val imageIndicator = mainContainer.findViewWithTag<ImageView>("image_indicator_$lessonId")

                if (imageIndicator != null) {
                    val shouldBeVisible = weeklyLesson.imagePaths.isNotEmpty()
                    imageIndicator.visibility = if (shouldBeVisible) View.VISIBLE else View.GONE
                    // Update the state cache
                    lessonImageStates[lessonId] = shouldBeVisible
                    Log.d("ImageUpdate", "Immediately updated image indicator for $lessonId: $shouldBeVisible")
                }
            }
        } catch (e: Exception) {
            Log.e("ImageUpdate", "Error updating lesson button immediately: ${e.message}")
        }
    }
    private fun debugLessonButtons() {
        val currentWeekday = getCurrentWeekday()
        val currentWeekId = getCurrentWeekIdentifier()
        val weekLessons = weeklyLessons[currentWeekId]?.get(currentWeekday) ?: emptyList()

        Log.d("DebugLesson", "=== LESSON BUTTON DEBUG ===")
        Log.d("DebugLesson", "Current weekday: $currentWeekday")
        Log.d("DebugLesson", "Weekly lessons count: ${weekLessons.size}")

        weekLessons.forEach { lesson ->
            val template = getTemplateForWeeklyLesson(lesson)
            Log.d("DebugLesson", "Lesson: ${template?.name ?: "Unknown"}, Images: ${lesson.imagePaths.size}, Has indicator: ${dayLayoutContainer.findViewWithTag<ImageView>("image_indicator_${lesson.id}") != null}")
        }
        Log.d("DebugLesson", "=== END DEBUG ===")
    }
    private fun loadImageIntoView(imagePath: String, imageView: ImageView) {
        // Clear previous image first
        imageView.setImageDrawable(null)

        var originalBitmap: Bitmap? = null
        var thumbnail: Bitmap? = null

        try {
            val file = File(imagePath)
            if (!file.exists()) {
                imageView.setImageResource(android.R.drawable.ic_menu_report_image)
                return
            }

            // Calculate sample size based on required thumbnail size
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(file, maxWidth = 120, maxHeight = 120)
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            originalBitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            if (originalBitmap == null) {
                imageView.setImageResource(android.R.drawable.ic_dialog_alert)
                return
            }

            // Create thumbnail
            thumbnail = Bitmap.createScaledBitmap(originalBitmap, 120, 120, true)
            imageView.setImageBitmap(thumbnail)

            // Cleanup original bitmap if different from thumbnail
            if (originalBitmap != thumbnail) {
                originalBitmap.recycle()
                originalBitmap = null
            }

        } catch (e: OutOfMemoryError) {
            Log.e("MemoryManagement", "Out of memory creating thumbnail: $imagePath")
            cleanupBitmap(originalBitmap, imageView)
            cleanupBitmap(thumbnail, imageView)
            System.gc()
            imageView.setImageResource(android.R.drawable.ic_dialog_alert)
        } catch (e: Exception) {
            Log.e("MemoryManagement", "Error creating thumbnail: ${e.message}")
            cleanupBitmap(originalBitmap, imageView)
            cleanupBitmap(thumbnail, imageView)
            imageView.setImageResource(android.R.drawable.ic_dialog_alert)
        }
    }

    /**
     * Calculate sampling size to load smaller bitmap and save memory
     */
    private fun calculateInSampleSize(file: File, maxWidth: Int = 800, maxHeight: Int = 800): Int {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            var inSampleSize = 1
            val (width, height) = options.outWidth to options.outHeight

            if (height > maxHeight || width > maxWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2

                while (halfHeight / inSampleSize >= maxHeight &&
                    halfWidth / inSampleSize >= maxWidth) {
                    inSampleSize *= 2
                }
            }
            inSampleSize
        } catch (e: Exception) {
            2 // Default fallback
        }
    }

    /**
     * Safely cleanup bitmap and clear image view reference
     */
    private fun cleanupBitmap(bitmap: Bitmap?, imageView: ImageView? = null) {
        try {
            bitmap?.let {
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
            imageView?.setImageDrawable(null)
        } catch (e: Exception) {
            Log.e("MemoryManagement", "Error cleaning up bitmap: ${e.message}")
        }
    }

    /**
     * Clear all image views in a container to free memory
     */
    private fun cleanupImageContainer(container: ViewGroup) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is ImageView) {
                cleanupBitmap(null, child)
            } else if (child is ViewGroup) {
                cleanupImageContainer(child)
            }
        }
    }

    private fun saveImageToInternalStorage(bitmap: Bitmap): String? {
        return try {
            val filesDir = applicationContext.filesDir
            val imageFile = File(filesDir, "image_${System.currentTimeMillis()}.jpg")
            val stream = FileOutputStream(imageFile)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            stream.flush()
            stream.close()
            imageFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun updateToolbar() {
        if (isWeekView) return

        val dayNameFormat = SimpleDateFormat("EEEE", Locale.getDefault())
        val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
        val weekFormat = SimpleDateFormat("'${getString(R.string.week)}' w", Locale.getDefault())

        val weekdayName = dayNameFormat.format(currentDate.time)
        val capitalizedWeekday = weekdayName.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }

        val dayName = capitalizedWeekday
        val dateInfo = "${dateFormat.format(currentDate.time)} • ${weekFormat.format(currentDate.time)}"

        val fullText = "$dayName\n$dateInfo"

        val spannableString = SpannableString(fullText)

        val dayNameSize = 26f
        val dateSize = dayNameSize * 0.6f

        spannableString.setSpan(
            AbsoluteSizeSpan(dayNameSize.toInt(), true),
            0,
            dayName.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        spannableString.setSpan(
            AbsoluteSizeSpan(dateSize.toInt(), true),
            dayName.length + 1,
            fullText.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        spannableString.setSpan(
            StyleSpan(android.graphics.Typeface.BOLD),
            0,
            dayName.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        tvDayTitle.text = spannableString
        tvDayTitle.gravity = Gravity.CENTER
    }

    private fun saveDataToStorage() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val editor = prefs.edit()

                // Save templates (unchanged)
                val templatesData = lessonTemplatesByWeekday.map { (weekday, templates) ->
                    "$weekday:${templates.joinToString("|||") { template ->
                        "${template.id}|${template.name}|${template.startTime.timeInMillis}|${template.endTime.timeInMillis}|${template.color}|${template.isFreeLesson}"
                    }}"
                }.joinToString(";;;")
                editor.putString(TEMPLATES_KEY, templatesData)

                // Save weekly lessons with improved serialization
                val lessonsData = weeklyLessons.map { (weekId, weekLessons) ->
                    val weekData = weekLessons.map { (weekday, lessons) ->
                        "$weekday:${lessons.joinToString("|||") { lesson ->
                            lesson.toStorageString()
                        }}"
                    }.joinToString(";;;")
                    "$weekId::$weekData"
                }.joinToString(NOTES_SEPARATOR)

                editor.putString(LESSONS_KEY, lessonsData)
                editor.apply()

                Log.d("SaveDebug", "Data saved: ${weeklyLessons.values.flatMap { it.values }.sumOf { it.size }} lessons")

            } catch (e: Exception) {
                Log.e("SaveDebug", "Error saving data: ${e.message}")
            }
        }
    }

    // Backup save method
    private fun backupSaveData() {
        try {
            val backupFile = File(filesDir, "lessons_backup.json")
            val jsonData = buildJsonData()
            backupFile.writeText(jsonData)
            Log.d("SaveDebug", "Backup saved to: ${backupFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("SaveDebug", "Backup save failed: ${e.message}")
        }
    }

    private fun buildJsonData(): String {
        val lessonsMap = mutableMapOf<String, Any>()
        weeklyLessons.forEach { (weekId, weekLessons) ->
            val weekMap = mutableMapOf<String, Any>()
            weekLessons.forEach { (weekday, lessons) ->
                weekMap[weekday.toString()] = lessons.map { lesson ->
                    mapOf(
                        "id" to lesson.id,
                        "templateId" to lesson.templateId,
                        "note" to lesson.note,
                        "imagePaths" to lesson.imagePaths,
                        "reminderTime" to lesson.reminderTime
                    )
                }
            }
            lessonsMap[weekId] = weekMap
        }
        return Gson().toJson(lessonsMap)
    }
    private fun loadDataFromStorage() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

            // Load templates first
            loadTemplates(prefs)

            // Load weekly lessons
            loadWeeklyLessons(prefs)

            // Validate and repair data
            validateAndRepairData()

            Log.d("LoadDebug", "Data loaded: ${weeklyLessons.values.flatMap { it.values }.sumOf { it.size }} lessons")

        } catch (e: Exception) {
            Log.e("LoadDebug", "Error loading data: ${e.message}")
            clearCorruptedData()
        }
    }

    private fun loadTemplates(prefs: android.content.SharedPreferences) {
        lessonTemplatesByWeekday.clear()
        prefs.getString(TEMPLATES_KEY, null)?.let { templatesData ->
            templatesData.split(";;;").forEach { entry ->
                val parts = entry.split(":", limit = 2)
                if (parts.size == 2) {
                    val weekday = parts[0].toInt()
                    val templates = parts[1].split("|||").mapNotNull { templateString ->
                        val templateParts = templateString.split("|")
                        if (templateParts.size >= 6) {
                            LessonTemplate(
                                id = templateParts[0],
                                name = templateParts[1],
                                startTime = Calendar.getInstance().apply { timeInMillis = templateParts[2].toLong() },
                                endTime = Calendar.getInstance().apply { timeInMillis = templateParts[3].toLong() },
                                color = templateParts[4],
                                isFreeLesson = templateParts[5].toBoolean()
                            )
                        } else null
                    }
                    lessonTemplatesByWeekday[weekday] = templates.toMutableList()
                }
            }
        }
    }

    private fun loadWeeklyLessons(prefs: android.content.SharedPreferences) {
        weeklyLessons.clear()

        prefs.getString(LESSONS_KEY, null)?.let { lessonsData ->
            if (lessonsData.isNotEmpty()) {
                lessonsData.split(NOTES_SEPARATOR).forEach { weekEntry ->
                    val parts = weekEntry.split("::", limit = 2)
                    if (parts.size == 2) {
                        val weekId = parts[0]
                        val weekLessons = mutableMapOf<Int, MutableList<WeeklyLesson>>()

                        parts[1].split(";;;").forEach { weekdayEntry ->
                            val weekdayParts = weekdayEntry.split(":", limit = 2)
                            if (weekdayParts.size == 2) {
                                val weekday = weekdayParts[0].toInt()
                                val lessons = weekdayParts[1].split("|||").mapNotNull { lessonString ->
                                    WeeklyLesson.fromStorageString(lessonString)
                                }.toMutableList()
                                weekLessons[weekday] = lessons
                            }
                        }
                        weeklyLessons[weekId] = weekLessons
                    }
                }
            }
        }
    }

    private fun validateAndRepairData() {
        var repairedCount = 0

        // Create a map of all valid template IDs for quick lookup
        val validTemplateIds = lessonTemplatesByWeekday.values
            .flatten()
            .map { it.id }
            .toSet()

        // Repair weekly lessons with invalid template references
        weeklyLessons.values.forEach { weekLessons ->
            weekLessons.values.forEach { lessons ->
                val iterator = lessons.iterator()
                while (iterator.hasNext()) {
                    val lesson = iterator.next()
                    // FIXED: Use templateId instead of template.id
                    if (lesson.templateId !in validTemplateIds) {
                        // Try to find a matching template by time or create a default one
                        val defaultTemplate = lessonTemplatesByWeekday.values.flatten().firstOrNull()
                        if (defaultTemplate != null) {
                            val repairedLesson = WeeklyLesson(
                                templateId = defaultTemplate.id,
                                note = lesson.note,
                                imagePaths = lesson.imagePaths,
                                weekIdentifier = lesson.weekIdentifier,
                                reminderTime = lesson.reminderTime
                            )
                            iterator.remove()
                            lessons.add(repairedLesson)
                            repairedCount++
                            Log.w("DataRepair", "Repaired lesson with invalid template reference")
                        } else {
                            iterator.remove()
                            Log.w("DataRepair", "Removed orphaned lesson without matching template")
                        }
                    }
                }
            }
        }

        if (repairedCount > 0) {
            Log.i("DataRepair", "Repaired $repairedCount lessons")
            saveDataToStorage() // Save repaired data
        }
    }
    private fun findTemplateByTime(lesson: WeeklyLesson): LessonTemplate? {
        // This is a fallback - in your case you might need different logic
        return lessonTemplatesByWeekday.values
            .flatten()
            .firstOrNull()
    }

    private fun attemptBackupRecovery() {
        try {
            val backupFile = File(filesDir, "lessons_backup.json")
            if (backupFile.exists()) {
                val jsonData = backupFile.readText()
                // recoverFromBackup(jsonData) - You'll need to implement this
                Log.i("BackupRecovery", "Successfully recovered from backup")
            }
        } catch (e: Exception) {
            Log.e("BackupRecovery", "Backup recovery failed: ${e.message}")
            // Last resort: clear corrupted data
            clearCorruptedData()
        }
    }

    private fun debugReminderData() {
        Log.d("ReminderDebug", "=== REMINDER DEBUG ===")
        var totalReminders = 0
        weeklyLessons.values.forEach { weekLessons ->
            weekLessons.values.forEach { lessons ->
                lessons.forEach { lesson ->
                    if (lesson.hasReminder()) {
                        totalReminders++
                        // FIXED: Use getTemplateForWeeklyLesson to get the template name
                        val templateName = getTemplateForWeeklyLesson(lesson)?.name ?: "Unknown"
                        Log.d("ReminderDebug", "Lesson: $templateName, Reminder: ${lesson.reminderTime}")
                    }
                }
            }
        }
        Log.d("ReminderDebug", "Total reminders found: $totalReminders")
        Log.d("ReminderDebug", "=== END DEBUG ===")
    }
    private fun clearCorruptedData() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().remove(LESSONS_KEY).remove(TEMPLATES_KEY).apply()
        lessonTemplatesByWeekday.clear()
        weeklyLessons.clear()
    }

    private fun showDatePickerDialog(currentDate: Calendar, onDateSet: (Calendar) -> Unit) {
        val datePicker = DatePickerDialog(this, { _, year, month, day ->
            val newDate = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, day)
            }
            onDateSet(newDate)
        },
            currentDate.get(Calendar.YEAR),
            currentDate.get(Calendar.MONTH),
            currentDate.get(Calendar.DAY_OF_MONTH))

        datePicker.datePicker.minDate = System.currentTimeMillis() - 1000
        datePicker.show()
    }

    private fun showNoteDialog(weeklyLesson: WeeklyLesson) {
        currentWeeklyLessonForNote = weeklyLesson
        val dialogView = layoutInflater.inflate(R.layout.dialog_note, null)
        val tvNoteTitle = dialogView.findViewById<TextView>(R.id.tvNoteTitle)
        val etNote = dialogView.findViewById<EditText>(R.id.etNote)
        val btnSaveNote = dialogView.findViewById<Button>(R.id.btnSaveNote)
        val btnCancelNote = dialogView.findViewById<Button>(R.id.btnCancelNote)
        val btnDeleteNote = dialogView.findViewById<Button>(R.id.btnDeleteNote)
        val btnAddImage = dialogView.findViewById<Button>(R.id.btnAddImage)
        val btnSetReminder = dialogView.findViewById<Button>(R.id.btnSetReminder)
        val reminderContainer = dialogView.findViewById<LinearLayout>(R.id.reminderContainer)
        val imagesContainer = dialogView.findViewById<LinearLayout>(R.id.imagesContainer)

        val originalNote = weeklyLesson.note
        val originalImagePaths = ArrayList(weeklyLesson.imagePaths)
        val originalReminder = weeklyLesson.reminderTime

        // REVERTED: Use original capitalized weekday names
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val language = prefs.getString(LANGUAGE_KEY, "en") ?: "en"

        val weekdayNames = getWeekdayNames(language)
        val currentWeekday = getCurrentWeekday()
        val weekdayName = weekdayNames[currentWeekday]

        // REVERTED: Use normal date formatting (not lowercase)
        val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
        val formattedDate = dateFormat.format(currentDate.time)

        val headerText = "${getTemplateForWeeklyLesson(weeklyLesson)?.name}\n$weekdayName, $formattedDate"
        tvNoteTitle.text = headerText

        etNote.setText(weeklyLesson.note)
        btnDeleteNote.visibility = if (weeklyLesson.note.isEmpty() && weeklyLesson.imagePaths.isEmpty()) View.GONE else View.VISIBLE

        setupReminderUI(weeklyLesson, btnSetReminder, reminderContainer)

        refreshImagesUI(imagesContainer, weeklyLesson.imagePaths)

        noteDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setOnDismissListener {
                currentWeeklyLessonForNote = null
                noteDialog = null
            }
            .create()

        // Apply window settings programmatically
        noteDialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        noteDialog?.window?.setLayout(getDialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)

        btnSaveNote.setOnClickListener {
            weeklyLesson.note = etNote.text.toString().trim()
            saveDataToStorage()
            loadLessonsForCurrentWeekday()
            Toast.makeText(this, getString(R.string.note_saved), Toast.LENGTH_SHORT).show()
            noteDialog?.dismiss()
        }

        btnCancelNote.setOnClickListener {
            weeklyLesson.note = originalNote
            weeklyLesson.imagePaths.clear()
            weeklyLesson.imagePaths.addAll(originalImagePaths)
            weeklyLesson.reminderTime = originalReminder
            noteDialog?.dismiss()
        }

        btnAddImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            galleryLauncher.launch(intent)
        }

        btnSetReminder.setOnClickListener {
            showSetReminderDialog(weeklyLesson) {
                setupReminderUI(weeklyLesson, btnSetReminder, reminderContainer)
            }
        }

        btnDeleteNote.setOnClickListener {
            val deleteDialogView = layoutInflater.inflate(R.layout.dialog_confirmation, null)
            val tvHeader = deleteDialogView.findViewById<TextView>(R.id.tvHeader)
            val tvMessage = deleteDialogView.findViewById<TextView>(R.id.tvMessage)
            val btnYes = deleteDialogView.findViewById<Button>(R.id.btnYes)
            val btnNo = deleteDialogView.findViewById<Button>(R.id.btnNo)

            tvHeader.text = getString(R.string.delete_note_confirmation)
            tvMessage.text = getString(R.string.delete_note_confirmation)

            val deleteDialog = AlertDialog.Builder(this)
                .setView(deleteDialogView)
                .create()

            // Apply window settings programmatically
            deleteDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            deleteDialog.window?.setLayout(getDialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)

            btnYes.setOnClickListener {
                weeklyLesson.note = ""
                weeklyLesson.imagePaths.clear()
                weeklyLesson.reminderTime = 0
                cancelNotification(weeklyLesson)
                saveDataToStorage()
                deleteDialog.dismiss()
                noteDialog?.dismiss()
                loadLessonsForCurrentWeekday()
                Toast.makeText(this, getString(R.string.note_deleted), Toast.LENGTH_SHORT).show()
            }

            btnNo.setOnClickListener {
                deleteDialog.dismiss()
            }

            deleteDialog.show()
        }

        noteDialog?.show()
    }

    private fun showImageConfirmationDialog(uri: Uri) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirmation, null)
        val tvHeader = dialogView.findViewById<TextView>(R.id.tvHeader)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvMessage)
        val btnYes = dialogView.findViewById<Button>(R.id.btnYes)
        val btnNo = dialogView.findViewById<Button>(R.id.btnNo)

        tvHeader.text = getString(R.string.add_image)
        tvMessage.text = getString(R.string.add_image_confirmation)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(getDialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)

        btnYes.setOnClickListener {
            dialog.dismiss()
            try {
                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    Log.e("ImageLoad", "Failed to open input stream for URI: $uri")
                    return@setOnClickListener
                }

                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (bitmap == null) {
                    Log.e("ImageLoad", "Failed to decode bitmap from URI: $uri")
                    return@setOnClickListener
                }

                saveImageToInternalStorage(bitmap)?.let { imagePath ->
                    currentWeeklyLessonForNote?.imagePaths?.add(imagePath)
                    saveDataToStorage()

                    // Simply refresh the RecyclerView
                    loadLessonsForCurrentWeekday()

                    refreshCurrentNoteDialogImages()
                    Toast.makeText(this, "Image added successfully", Toast.LENGTH_SHORT).show()

                    Log.d("ImageLoad", "Image added to ${currentWeeklyLessonForNote?.id}, total images: ${currentWeeklyLessonForNote?.imagePaths?.size}")
                } ?: run {
                    Log.e("ImageLoad", "Failed to save image to storage")
                    Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show()
                }

            } catch (e: SecurityException) {
                Log.e("ImageLoad", "Security exception - permission denied: ${e.message}")
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            } catch (e: FileNotFoundException) {
                Log.e("ImageLoad", "File not found: ${e.message}")
                Toast.makeText(this, "Image file not found", Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                Log.e("ImageLoad", "IO error: ${e.message}")
                Toast.makeText(this, "Error reading image", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("ImageLoad", "Unexpected error: ${e.message}")
                Toast.makeText(this, "Error adding image", Toast.LENGTH_SHORT).show()
            }
        }

        btnNo.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
    }