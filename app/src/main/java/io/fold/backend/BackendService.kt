package io.fold.backend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.fold.MainActivity
import io.fold.R
import io.fold.runtime.Backend
import io.fold.runtime.Runtime
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** What the UI needs to know about the VS Code backend. */
sealed interface BackendState {
    data object Stopped : BackendState
    data object Starting : BackendState
    data class Ready(val url: String, val port: Int, val cookie: String?) : BackendState
    data class Failed(val message: String) : BackendState
}

/**
 * Owns the single backend process of the app. The [BackendService] keeps the app process
 * alive in the background; the activity only observes [state].
 */
object BackendHolder {
    private const val TAG = "FoldBackend"
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(BackendState) -> Unit>()
    private var backend: Backend? = null
    private var stopping = false
    private val recentCrashes = ArrayDeque<Long>()

    @Volatile
    var state: BackendState = BackendState.Stopped
        private set

    fun addListener(l: (BackendState) -> Unit) {
        listeners += l
        main.post { l(state) }
    }

    fun removeListener(l: (BackendState) -> Unit) {
        listeners -= l
    }

    private fun publish(s: BackendState) {
        state = s
        main.post { listeners.forEach { it(s) } }
    }

    fun logFile(ctx: Context) = File(ctx.filesDir, "logs/code-server.log").apply { parentFile?.mkdirs() }

    @Synchronized
    fun ensureRunning(ctx: Context) {
        if (backend?.isRunning == true && (state is BackendState.Ready || state is BackendState.Starting)) return
        stopping = false
        val app = ctx.applicationContext
        publish(BackendState.Starting)
        thread(name = "backend-start") { startNow(app) }
    }

    private fun startNow(ctx: Context) {
        try {
            val rt = Runtime(ctx)
            val b = Backend(rt)
            val log = logFile(ctx)
            if (log.length() > 2_000_000) log.delete()
            runCatching { io.fold.vscode.FoldExtension.ensureInstalled(ctx, rt) }.onFailure { Log.w(TAG, "extension install failed", it) }
            rt.setupStorageLinks()
            // VS Code's GitHub account (and git in its terminals) use the token from FoldCode's GitHub sign-in.
            val env = buildMap { io.fold.github.GitHub(rt).token()?.let { put("GITHUB_TOKEN", it) } }
            b.start(File(rt.home, "projects").apply { mkdirs() }, log, env)
            synchronized(this) { backend = b }
            if (!b.waitUntilHealthy()) {
                publish(BackendState.Failed("VS Code backend didn't start (see the log)"))
                b.stop()
                return
            }
            publish(BackendState.Ready(b.baseUrl, b.port, b.login()))
            watch(ctx, b)
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            publish(BackendState.Failed(t.message ?: t.toString()))
        }
    }

    /** Restarts the backend if it dies on its own (at most 3 times in 2 minutes). */
    private fun watch(ctx: Context, b: Backend) = thread(name = "backend-watch") {
        val code = b.waitForExit()
        if (stopping || backend !== b) return@thread
        Log.w(TAG, "backend exited with $code")
        val now = SystemClock.elapsedRealtime()
        recentCrashes.addLast(now)
        while (recentCrashes.isNotEmpty() && now - recentCrashes.first() > 120_000) recentCrashes.removeFirst()
        if (recentCrashes.size > 3) {
            publish(BackendState.Failed("VS Code backend keeps crashing (exit $code), see the log"))
        } else {
            publish(BackendState.Starting)
            startNow(ctx)
        }
    }

    /** Restart (e.g. after GitHub sign-in, so VS Code picks up the new token). */
    fun restart(ctx: Context) {
        stop()
        ensureRunning(ctx)
    }

    @Synchronized
    fun stop() {
        stopping = true
        backend?.stop()
        backend = null
        publish(BackendState.Stopped)
    }
}

/**
 * Foreground service: keeps VS Code (terminals, dev servers, tasks) running while the app is in
 * the background, with a notification that has a Stop action.
 */
class BackendService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.backend_channel), NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BackendHolder.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        BackendHolder.ensureRunning(this)
        return START_STICKY
    }

    override fun onDestroy() {
        BackendHolder.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BackendService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_fold)
            .setContentTitle(getString(R.string.backend_running))
            .setContentText(getString(R.string.backend_running_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "backend"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "io.fold.action.STOP_BACKEND"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, BackendService::class.java))
        }
    }
}
