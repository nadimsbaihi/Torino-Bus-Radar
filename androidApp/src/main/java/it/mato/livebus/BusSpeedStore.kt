package it.mato.livebus

import android.content.Context
import java.io.File

/**
 * The learned bus speeds of this process, kept in one small private file so they survive restarts.
 * The activity and the alert service share one estimator, so neither teaches the other's copy
 * something the file already holds.
 */
object BusSpeedStore {
    private const val FILE = "bus_speeds.txt"
    private const val SAVE_EVERY_MILLIS = 60_000L
    private var shared: SpeedEstimator? = null
    private var savedRevision = -1
    private var savedAtMillis = 0L

    @Synchronized
    fun shared(context: Context): SpeedEstimator {
        shared?.let { return it }
        val estimator = SpeedEstimator()
        runCatching { File(context.applicationContext.filesDir, FILE).takeIf { it.exists() }?.readText() }
            .getOrNull()?.let(estimator::restore)
        savedRevision = estimator.revision
        shared = estimator
        return estimator
    }

    /** Writes the speeds if they changed; unless [force], at most once a minute. */
    @Synchronized
    fun save(context: Context, force: Boolean = false, nowMillis: Long = System.currentTimeMillis()) {
        val estimator = shared ?: return
        if (estimator.revision == savedRevision) return
        if (!force && nowMillis - savedAtMillis < SAVE_EVERY_MILLIS) return
        val file = File(context.applicationContext.filesDir, FILE)
        val temporary = File(file.parentFile, "$FILE.tmp")
        val saved = runCatching {
            temporary.writeText(estimator.serialize(Math.floorDiv(nowMillis / 1000, 86_400L)))
            temporary.renameTo(file)
        }.getOrDefault(false)
        if (saved) {
            savedRevision = estimator.revision
            savedAtMillis = nowMillis
        }
    }
}
