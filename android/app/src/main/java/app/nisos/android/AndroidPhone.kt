package app.nisos.android

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import app.nisos.core.ActionError
import app.nisos.core.CalendarEntry
import app.nisos.core.Phone
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Everything that touches Android, in one file.
 *
 * This is the file that justifies the whole rewrite. Compare it with what it
 * replaces:
 *
 * | Action        | Termux                                    | Here |
 * |---------------|-------------------------------------------|------|
 * | torch         | `termux-torch on` (shells out)            | `setTorchMode` |
 * | battery       | `termux-battery-status`, then parse JSON  | `BatteryManager` |
 * | volume        | `termux-volume`, parse, scale by max step | `AudioManager` |
 * | do not disturb| broadcast → Tasker → a second task        | one intent |
 * | next meeting  | broadcast → Tasker → `content query` → write a file on /sdcard → poll for it | a cursor |
 * | new meeting   | the same, backwards                       | one insert |
 *
 * The calendar rows are the point. That round trip existed because Termux
 * cannot declare `READ_CALENDAR`, so the permission had to be borrowed from
 * another app through a broadcast that cannot return a value. Two lines in a
 * manifest deleted all of it -- the answer file on shared storage, the polling
 * loop, the stale-answer trap, and the class of failure where Tasker silently
 * was not running.
 *
 * Failures here raise [ActionError], which the loop turns into a spoken
 * sentence rather than a crash. Missing runtime permissions get their own
 * message, because "that didn't work" sends you looking in the wrong place.
 */
class AndroidPhone(
    private val context: Context,
    private val memory: Memory,
) : Phone {

    private fun require(permission: String) {
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            throw ActionError("permission $permission not granted")
        }
    }

    // -- torch -------------------------------------------------------------
    // No permission at all since API 23. The camera permission is deliberately
    // not in the manifest: asking for the camera to flash an LED is the kind
    // of thing that makes people uninstall an assistant.
    override fun torch(on: Boolean) {
        val cameras = context.getSystemService<CameraManager>()
            ?: throw ActionError("no camera service")
        val id = cameras.cameraIdList.firstOrNull { camera ->
            cameras.getCameraCharacteristics(camera)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw ActionError("no torch on this device")
        cameras.setTorchMode(id, on)
    }

    // -- battery -----------------------------------------------------------
    override fun battery(): Pair<Int, String> {
        val manager = context.getSystemService<BatteryManager>()
            ?: throw ActionError("no battery service")
        val percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = when (manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            else -> "unknown"
        }
        return percent to status
    }

    // -- timer -------------------------------------------------------------
    // A platform intent, exactly as in the Python version. SKIP_UI starts the
    // countdown without the clock app coming to the foreground.
    override fun startTimer(minutes: Int) {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "nisos")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // -- messaging ---------------------------------------------------------
    override fun sendSms(to: String, body: String) {
        require(Manifest.permission.SEND_SMS)
        val number = lookupNumber(to) ?: to
        val manager = context.getSystemService(SmsManager::class.java)
            ?: throw ActionError("no SMS service")
        // Long messages have to be split or they arrive truncated, and a
        // Greek message hits the limit at 70 characters rather than 160
        // because it is sent as UCS-2.
        val parts = manager.divideMessage(body)
        if (parts.size == 1) {
            manager.sendTextMessage(number, null, body, null, null)
        } else {
            manager.sendMultipartTextMessage(number, null, parts, null, null)
        }
    }

    override fun openWhatsApp(number: String, body: String) {
        val url = "https://wa.me/${number.filter { it.isDigit() }}?text=${Uri.encode(body)}"
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // -- clipboard ---------------------------------------------------------
    override fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService<android.content.ClipboardManager>()
            ?: throw ActionError("no clipboard service")
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("nisos", text))
    }

    // -- do not disturb ----------------------------------------------------
    // The one thing here that still cannot be done silently. Notification
    // policy access is a special grant, and an app cannot request it with a
    // normal permission dialog -- it has to send you to Settings. Better than
    // Termux managed, where it needed a whole second application.
    override fun doNotDisturb(on: Boolean) {
        val notifications = context.getSystemService<android.app.NotificationManager>()
            ?: throw ActionError("no notification service")
        if (!notifications.isNotificationPolicyAccessGranted) {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            throw ActionError("do not disturb access not granted")
        }
        notifications.setInterruptionFilter(
            if (on) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        )
    }

    // -- volume ------------------------------------------------------------
    // Percent in, stream steps out, scaled to whatever this device reports.
    // Step counts differ per device, so 100% has to mean the real maximum.
    override fun setVolume(level: Int) {
        val audio = context.getSystemService<AudioManager>()
            ?: throw ActionError("no audio service")
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        var steps = Math.round(level / 100f * maximum)
        // Asking for 5% and getting silence looks like a failure.
        if (level > 0 && steps == 0) steps = 1
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, steps.coerceIn(0, maximum), 0)
    }

    // -- calendar ----------------------------------------------------------
    override fun nextEvent(): CalendarEntry? {
        require(Manifest.permission.READ_CALENDAR)
        val now = System.currentTimeMillis()
        val horizon = now + 7L * 24 * 3600 * 1000

        // The Instances table rather than Events, so a repeating meeting
        // resolves to its next actual occurrence instead of the series start.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, horizon)
        }.build()

        context.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
            null, null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val title = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: "something"
            val begins = cursor.getLong(1)
            return CalendarEntry(title, ((begins - now) / 60000).toInt())
        }
        return null
    }

    override fun addEvent(summary: String, start: LocalDateTime, minutes: Int) {
        require(Manifest.permission.WRITE_CALENDAR)
        val zone = ZoneId.systemDefault()
        val begins = start.atZone(zone).toInstant().toEpochMilli()

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, writableCalendarId())
            put(CalendarContract.Events.TITLE, summary)
            put(CalendarContract.Events.DTSTART, begins)
            put(CalendarContract.Events.DTEND, begins + minutes * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
        }
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: throw ActionError("the calendar refused the entry")
    }

    /**
     * The first calendar this app may write to.
     *
     * CONTRIBUTOR is 500 and OWNER is 700; anything below can be read but not
     * written, and picking one of those fails with a permission error on a
     * phone that plainly has the permission.
     */
    private fun writableCalendarId(): Long {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        throw ActionError("no writable calendar on this phone")
    }

    // -- contacts ----------------------------------------------------------
    override fun lookupNumber(name: String): String? {
        // What you taught it out loud wins over the address book: that is the
        // more recent intent, and it is how a name the recogniser mangles gets
        // a number attached at all.
        memory.contact(name)?.let { return it }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name)
        )
        context.contentResolver.query(
            uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    override fun resolveContact(name: String): String = memory.alias(name) ?: name

    // -- memory ------------------------------------------------------------
    override fun remember(key: String, value: String) = memory.remember(key, value)
    override fun recall(key: String): String? = memory.recall(key)
    override fun forget(key: String): Boolean = memory.forget(key)
    override fun memoryCounts(): Pair<Int, Int> = memory.counts()
}
