package app.bball.court

import android.content.Context
import java.io.File

/**
 * A crash that kills the process leaves nothing on screen for the user to send us.
 * This writes the stack trace next to the app's files and the next launch shows it
 * inside the UI ("上次崩了，原因如下"), which is the only way to debug on a phone we
 * do not hold.
 */
object CrashLog {
    private const val NAME = "last-crash.txt"
    private fun file(ctx: Context) = File(ctx.filesDir, NAME)

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                file(ctx).writeText(
                    buildString {
                        appendLine("thread=" + thread.name)
                        appendLine("time=" + System.currentTimeMillis())
                        appendLine(error.stackTraceToString())
                    },
                )
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(ctx: Context): String? = try {
        val f = file(ctx)
        if (f.exists() && f.length() > 0) f.readText().take(4000) else null
    } catch (_: Throwable) { null }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Throwable) {} }
}
