package app.pamoka.timetable

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // FIXED: Get the lesson ID and fetch the actual lesson data
        val lessonId = intent.getStringExtra("lesson_id")
        val note = intent.getStringExtra("note") ?: ""

        // You'll need to get the lesson name from your data storage
        // For now, use a placeholder - you'll need to implement this properly
        val lessonName = getLessonNameFromStorage(context, lessonId) ?: "Lesson"

        showNotification(context, lessonName, note)
    }

    private fun getLessonNameFromStorage(context: Context, lessonId: String?): String? {
        // TODO: Implement this method to get the actual lesson name from your data storage
        // You'll need to access your SharedPreferences or database to get the lesson name
        // For now, return a placeholder
        return "Lesson"
    }

    private fun showNotification(context: Context, lessonName: String, note: String) {
        createNotificationChannel(context)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, "lesson_reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Use system icon for now
            .setContentTitle("Lesson Reminder: $lessonName")
            .setContentText(if (note.isNotEmpty()) note else "Time for your lesson!")
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (note.isNotEmpty()) note else "Time for your lesson!"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setLights(Color.BLUE, 1000, 1000)
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .build()

        notificationManager.notify(lessonName.hashCode(), notification)
    }

    private fun createNotificationChannel(context: Context) {
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

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        // FIXED: Removed duplicate "companion object" nesting

        fun scheduleReminder(context: Context, reminderTime: Calendar, weeklyLesson: WeeklyLesson) {
            // FIXED: Get the template to include lesson name in the notification
            val template = getTemplateForWeeklyLesson(context, weeklyLesson)
            val lessonName = template?.name ?: "Lesson"

            val intent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra("lesson_id", weeklyLesson.id)
                putExtra("lesson_name", lessonName)
                putExtra("note", weeklyLesson.note)
                putExtra("reminder_time", reminderTime.timeInMillis)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                weeklyLesson.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                reminderTime.timeInMillis,
                pendingIntent
            )

            Log.d("Reminder", "Scheduled reminder for lesson: $lessonName")
        }

        fun cancelReminder(context: Context, weeklyLesson: WeeklyLesson) {
            val intent = Intent(context, ReminderReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                weeklyLesson.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()

            Log.d("Reminder", "Cancelled reminder for lesson: ${weeklyLesson.id}")
        }

        private fun getTemplateForWeeklyLesson(context: Context, weeklyLesson: WeeklyLesson): LessonTemplate? {
            // TODO: Implement this to get the template from your data storage
            // You'll need to access your SharedPreferences to load the templates
            // For now, return null - you'll need to implement proper data loading
            return null
        }
    }
}