package io.github.landwarderer.futon.core.debuglog

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Verbose, persistent log of what the app does in the background, for the in-app debug log screen.
 *
 * Entries are kept in memory for the screen and appended to a file, so they survive the process being killed:
 * a check that runs hours later is still there to read. The file rotates at [MAX_FILE_SIZE], keeping one
 * previous file.
 */
object DebugLog {

	data class Entry(
		val seq: Long,
		val time: Long,
		val tag: String,
		val message: String,
		val thread: String,
	) {

		fun format(): String = "${TIME_FORMAT.format(Instant.ofEpochMilli(time))} [$tag] ($thread) $message"
	}

	private val lock = Any()
	private val buffer = ArrayDeque<Entry>(MAX_ENTRIES)
	private val writer = Executors.newSingleThreadExecutor { Thread(it, "DebugLog").apply { isDaemon = true } }
	private var seq = 0L
	private var dir: File? = null

	private val versionFlow = MutableStateFlow(0L)

	/** Changes every time the log changes; collect it and re-read [snapshot]. */
	val version: StateFlow<Long> = versionFlow.asStateFlow()

	fun init(context: Context) {
		val logDir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
		synchronized(lock) {
			if (dir != null) return
			dir = logDir
		}
		writer.execute {
			// Bring back the tail of the previous runs, so the screen shows what happened before a restart
			val restored = runCatching {
				(previousFile(logDir).readLinesOrEmpty() + currentFile(logDir).readLinesOrEmpty()).takeLast(MAX_ENTRIES)
			}.getOrDefault(emptyList())
			synchronized(lock) {
				val live = buffer.toList()
				buffer.clear()
				restored.forEach { line -> buffer.addLast(Entry(seq++, 0L, RESTORED_TAG, line, "")) }
				live.forEach { buffer.addLast(it.copy(seq = seq++)) }
				while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
			}
			versionFlow.value++
		}
		log("App", "Process started (pid ${android.os.Process.myPid()})")
	}

	fun log(tag: String, message: String) {
		val entry = synchronized(lock) {
			Entry(seq++, System.currentTimeMillis(), tag, message, Thread.currentThread().name).also {
				buffer.addLast(it)
				if (buffer.size > MAX_ENTRIES) buffer.removeFirst()
			}
		}
		Log.d("DebugLog/$tag", message)
		versionFlow.value = entry.seq
		writer.execute { append(entry) }
	}

	fun log(tag: String, message: String, error: Throwable) {
		log(tag, "$message: ${error.javaClass.simpleName}: ${error.message}\n${error.stackTraceToString().take(MAX_TRACE)}")
	}

	fun snapshot(): List<Entry> = synchronized(lock) { buffer.toList() }

	/** Everything still on disk (both files), for exporting: may hold more than the in-memory buffer. */
	fun exportText(): String {
		val logDir = dir ?: return snapshot().joinToString("\n") { it.format() }
		return runCatching {
			writer.submit { }.get() // flush pending writes
			(previousFile(logDir).readLinesOrEmpty() + currentFile(logDir).readLinesOrEmpty()).joinToString("\n")
		}.getOrElse { snapshot().joinToString("\n") { it.format() } }
	}

	fun clear() {
		synchronized(lock) { buffer.clear() }
		writer.execute {
			dir?.let {
				currentFile(it).delete()
				previousFile(it).delete()
			}
		}
		versionFlow.value++
	}

	private fun append(entry: Entry) {
		val logDir = dir ?: return
		runCatching {
			val file = currentFile(logDir)
			if (file.length() > MAX_FILE_SIZE) {
				val previous = previousFile(logDir)
				previous.delete()
				file.renameTo(previous)
			}
			currentFile(logDir).appendText(entry.format() + "\n")
		}
	}

	private fun currentFile(dir: File) = File(dir, "debug.log")

	private fun previousFile(dir: File) = File(dir, "debug.1.log")

	private fun File.readLinesOrEmpty(): List<String> = if (exists()) readLines() else emptyList()

	private val TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

	const val RESTORED_TAG = "~"
	private const val DIR_NAME = "debug_log"
	private const val MAX_ENTRIES = 5_000
	private const val MAX_FILE_SIZE = 2L * 1024 * 1024
	private const val MAX_TRACE = 2_000
}
