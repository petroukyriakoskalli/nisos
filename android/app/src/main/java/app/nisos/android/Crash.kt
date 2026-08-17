package app.nisos.android

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What it died of, kept where you can read it.
 *
 * This exists because of how the app is developed: there is no Android SDK on
 * the laptop, no `adb`, and no debugger. When it crashed on a phone the entire
 * evidence available was Samsung's dialog -- *"nisos closed because this app has
 * a bug"* -- which names neither the exception nor the line. Nothing in the
 * build could be inspected to find out, because the build was fine; it was the
 * device that was different.
 *
 * So the app reports on itself. The handler runs before any activity, writes the
 * trace to app-private storage, and then hands the crash on to the platform's
 * own handler so the behaviour you see is unchanged. On the next launch
 * `MainActivity` renders the trace **before it builds anything else**, which is
 * the part that matters: a crash screen that needs the ViewModel would die of
 * the same fault it was trying to report.
 */
object Crash {

    private const val FILE = "last-crash.txt"
    private val WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Record a crash. Must never throw -- it runs while one is already in flight. */
    fun write(context: Context, thread: Thread, error: Throwable, version: String) {
        try {
            val trace = StringWriter()
            PrintWriter(trace).use { error.printStackTrace(it) }

            File(context.filesDir, FILE).writeText(
                buildString {
                    appendLine("nisos $version")
                    appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine(LocalDateTime.now().format(WHEN))
                    appendLine("thread: ${thread.name}")
                    appendLine()
                    appendLine(trace.toString())
                }
            )
        } catch (_: Throwable) {
            // A failure to record a crash must not become a second crash.
        }
    }

    /** The last crash, or null if the last run ended normally. */
    fun read(context: Context): String? = try {
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.ifBlank { null }
    } catch (_: Throwable) {
        null
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE).delete()
        } catch (_: Throwable) {
        }
    }
}

/**
 * Installs the crash handler, and nothing else.
 *
 * An `Application` rather than something in `MainActivity.onCreate`, because a
 * crash during the activity's own construction is exactly the case that needs
 * catching -- and by then the activity is too late.
 */
class NisosApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Throwable) {
            "?"
        }

        // Chained, not replaced. The platform handler is what actually ends the
        // process and shows the dialog; swallowing it would leave the app in a
        // half-dead state that looks like a freeze instead of a crash.
        val platform = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Crash.write(this, thread, error, version)
            platform?.uncaughtException(thread, error)
        }
    }
}
