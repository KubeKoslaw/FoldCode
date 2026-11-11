package io.fold.preview

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Notices dev servers on the phone (php -S, Vite, Next, Flask…) by checking the usual ports on
 * 127.0.0.1 every two seconds, and reports when one starts or stops listening.
 * Only connects to loopback; nothing leaves the phone.
 */
class DevServerWatcher(
    private val ignore: () -> Set<Int>,
    private val onStarted: (port: Int) -> Unit,
    private val onStopped: (port: Int) -> Unit,
) {
    private val thread = HandlerThread("dev-servers").apply { start() }
    private val bg = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val listening = mutableSetOf<Int>()
    @Volatile private var running = false

    private val scan = object : Runnable {
        override fun run() {
            if (!running) return
            val skip = ignore()
            val now = PORTS.filter { it !in skip && isListening(it) }.toSet()
            for (p in now - listening) main.post { onStarted(p) }
            for (p in listening - now) main.post { onStopped(p) }
            listening.clear()
            listening.addAll(now)
            bg.postDelayed(this, 2000)
        }
    }

    fun start() {
        if (running) return
        running = true
        bg.post(scan)
    }

    fun stop() {
        running = false
        bg.removeCallbacks(scan)
    }

    fun quit() {
        stop()
        thread.quitSafely()
    }

    private fun isListening(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 150); true }
    } catch (_: Exception) {
        false
    }

    companion object {
        /** Common dev-server ports: Next/Express, Vite (+preview), Angular, Astro, Flask, Live Server, PHP/Django, … */
        val PORTS = listOf(3000, 3001, 4173, 4200, 4321, 5000, 5173, 5174, 5500, 8000, 8001, 8080, 8081, 8888, 9000)
    }
}
