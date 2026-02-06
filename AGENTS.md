# AGENTS.md - Lesson Timetable Android App

## Project Overview

**Lesson** is a comprehensive Android timetable management application designed for students to organize their weekly schedules, manage lesson notes, attach images, and set reminders. The app supports multiple languages (English, Lithuanian, Spanish) and provides an intuitive interface for managing academic schedules.

## Initial Commit Analysis

**Commit:** `1eb3e38` - "pamoka"  
**Date:** November 13, 2025  
**Author:** laptop <hyst3rion@gmail.com>

This initial commit represents a complete Android application with production-ready features. The commit includes 98 files with 6,628 insertions, establishing the foundation for a full-featured lesson management system.

## Core Features

### 1. Timetable Management
- **Configurable Lesson Times**: Administrators can define up to 10 lesson slots per day with custom start and end times
- **Weekly Schedule View**: Grid-based interface displaying all weekdays (Monday-Friday)
- **Day Detail View**: Expandable daily view with swipe navigation between weekdays
- **Auto-Fill Free Periods**: Automatically creates "No class" entries for gaps between scheduled lessons
- **Color-Coded Lessons**: Each weekday has its own color palette for visual organization:
  - Monday: Blue shades (#1E3A8A to #93C5FD)
  - Tuesday: Green shades (#166534 to #86EFAC)
  - Wednesday: Pink shades (#831843 to #F9A8D4)
  - Thursday: Orange shades (#7C2D12 to #FDBA74)
  - Friday: Red shades (#7F1D1D to #FCA5A5)

### 2. Notes & Media
- **Per-Lesson Notes**: Each weekly lesson instance can have unique notes
- **Image Attachments**: Multiple images can be attached to lesson notes via gallery picker
- **Image Management**: 
  - Thumbnail preview in note interface (120x120px)
  - Fullscreen image viewing with delete capability
  - Images stored in internal app storage with JPEG compression (80% quality)
  - Memory-optimized loading with bitmap sampling

### 3. Reminders
- **Custom Notification System**: Set date/time reminders for specific lesson instances
- **Persistent Storage**: Reminders survive app restarts
- **Notification Channel**: "Lesson Reminders" with high priority and vibration
- **One-Time Reminders**: Each reminder is specific to a weekly lesson instance

### 4. Multi-Language Support
- **Supported Languages**: English (default), Lithuanian, Spanish
- **Automatic Detection**: Uses system language if supported, defaults to English
- **Dynamic Resource Loading**: All UI text, weekday names, and date formats adapt to selected language
- **Persistent Preference**: Language choice saved across app sessions

### 5. Data Persistence
- **SharedPreferences Storage**: 
  - Templates (lesson definitions per weekday)
  - Weekly lessons (notes, images, reminders per week)
  - Lesson time configurations
  - Language preference
- **Backup System**: JSON-based backup file for data recovery
- **Data Validation**: Automatic repair of corrupted or orphaned data on load
- **Efficient Serialization**: Custom string-based format with separators

### 6. Memory Management
- **Bitmap Recycling**: Proper cleanup of image resources to prevent memory leaks
- **Sample Size Calculation**: Loads images at appropriate resolution based on display requirements
- **Memory Trim Handling**: Responds to system memory pressure events
- **Container Cleanup**: Recursive cleanup of image views in layouts

## Architecture

### Key Components

#### MainActivity.kt (3,361 lines)
The main activity orchestrating the entire app with:
- **View Management**: Dual-mode display (week view / day view)
- **Fragment Coordination**: Manages week day fragments
- **Dialog Controllers**: Handles multiple custom dialogs (add lesson, edit, notes, settings, etc.)
- **Data Layer**: Manages in-memory data structures and storage operations
- **Lifecycle Management**: Handles save/restore state properly

#### ReminderReceiver.kt
BroadcastReceiver for handling scheduled reminder notifications with alarm manager integration.

#### WeekDayFragment.kt
Fragment representing individual weekday views in the pager adapter.

#### WeekDaysPagerAdapter.kt
ViewPager2 adapter for swipeable weekday navigation.

### Data Models

```kotlin
data class LessonTemplate(
    val id: String,
    val name: String,
    val startTime: Calendar,
    val endTime: Calendar,
    val color: String,
    val isFreeLesson: Boolean = false
)

data class WeeklyLesson(
    val id: String,
    val templateId: String,
    var note: String = "",
    var imagePaths: MutableList<String> = mutableListOf(),
    val weekIdentifier: String = "",
    var reminderTime: Long = 0
)

data class LessonTimeTemplate(
    val id: String,
    val lessonNumber: Int,
    val startTime: Calendar,
    val endTime: Calendar
)
```

### Adapters

**DayLessonAdapter**: RecyclerView adapter for displaying daily lessons with:
- Lesson name and time display
- Note preview (max 2 lines)
- Image indicator icon
- Separate click handlers for lesson editing and note management

**WeekAdapter**: RecyclerView adapter for week view with:
- Grid layout (2 columns, Friday centered)
- Color-coded lesson bars
- Empty state handling
- Dynamic lesson number display

## UI/UX Features

### Dialogs
1. **Add Lesson Dialog**: Create new lessons with name and time slot selection
2. **Edit Lesson Dialog**: Modify lesson details or delete from all weeks
3. **Note Dialog**: Comprehensive note editing with image attachments and reminders
4. **Settings Dialog**: Access to lesson time management, data deletion, and feature requests
5. **Language Dialog**: Visual language selector with flag icons
6. **Manage Lesson Times**: CRUD operations for lesson time templates
7. **Set Reminder Dialog**: Date/time picker for notifications
8. **Confirmation Dialogs**: Multiple confirmation flows for destructive actions

### Gesture Controls
- **Swipe Navigation**: Left/right swipe to navigate between weekdays in day view
- **Back Navigation**: Returns to week view from day view, then exits app

### Visual Design
- **Material Design**: Uses Material Components and themes
- **Custom Backgrounds**: Rounded corners, borders, and gradients
- **Responsive Layout**: Adapts to different screen sizes with dynamic margins
- **Dialog Styling**: Transparent backgrounds with custom shapes

## Technical Stack

### Dependencies
```kotlin
// Core
implementation("androidx.core:core-ktx:1.12.0")
implementation("androidx.appcompat:appcompat:1.6.1")
implementation("com.google.android.material:material:1.11.0")

// Layout
implementation("androidx.constraintlayout:constraintlayout:2.1.4")
implementation("androidx.recyclerview:recyclerview:1.3.2")

// Lifecycle
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

// JSON
implementation("com.google.code.gson:gson:2.10.1")

// Ads
implementation("com.google.android.gms:play-services-ads:22.6.0")

// Testing
testImplementation("junit:junit:4.13.2")
androidTestImplementation("androidx.test.ext:junit:1.1.5")
androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
```

### Build Configuration
- **Namespace**: app.pamoka.timetable
- **Min SDK**: 21 (Android 5.0 Lollipop)
- **Target SDK**: 35 (Android 15)
- **Compile SDK**: 35
- **Version**: 1.0 (versionCode 7)
- **ProGuard**: Enabled for release builds with resource shrinking

### Permissions
```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" /> (Android 13+)
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
```

## App Distribution

### Release Builds
The project includes production-ready release artifacts:
- `app-release.aab` (2,008,262 bytes)
- `app-releasegplay.aab` (1,963,300 bytes) - Google Play optimized
- Baseline profiles included for improved startup performance

## Data Management Patterns

### Storage Strategy
1. **Templates**: Stored per-weekday, reused across all weeks
2. **Weekly Lessons**: Instances created per week identifier (Monday date of each week)
3. **Images**: Stored in app's internal files directory with timestamp-based naming
4. **Separation of Concerns**: Templates define structure, weekly lessons store instance data

### Data Integrity
- **Orphan Prevention**: Validates template references on load
- **Automatic Repair**: Recreates missing weekly lessons from templates
- **Corruption Handling**: Clears corrupted data and attempts backup recovery
- **Image Validation**: Verifies image files exist and are valid on load

## Feature Request System

Integrated feedback mechanism allowing users to submit feature requests:
- **Character Limits**: 5-200 characters
- **Rate Limiting**: 1 request per minute to prevent spam
- **Google Forms Integration**: Submissions sent to developer's form
- **Metadata Collection**: Includes app version, device info, and timestamp

## Future Enhancement Areas

Based on the codebase structure, potential improvements could include:
1. Cloud synchronization for multi-device support
2. Export/import functionality for data portability
3. Widget support for home screen schedule preview
4. Theme customization options
5. Statistics and attendance tracking
6. Teacher/class contact information
7. Assignment integration
8. Calendar app integration

## Developer Notes

### Code Quality
- **Kotlin Best Practices**: Uses data classes, null safety, and coroutines
- **Lifecycle Awareness**: Proper handling of configuration changes
- **Memory Safety**: Comprehensive bitmap management
- **Error Handling**: Try-catch blocks with logging for critical operations

### Testing
- Basic test structure in place (ExampleUnitTest, ExampleInstrumentedTest)
- Room for expansion of test coverage

### Localization
- Complete string resources for 3 languages
- Flag assets included (UK, Lithuania, Spain)
- Date/time formatting respects locale

---

**Last Updated**: November 13, 2025  
**Project Status**: Initial production release  
**Total Commits Analyzed**: 1
