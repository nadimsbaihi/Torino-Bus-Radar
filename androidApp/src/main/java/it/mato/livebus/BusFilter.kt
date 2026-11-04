package it.mato.livebus

import android.content.Context

/**
 * Which bus lines the overview map shows. The rider ticks the lines to keep and the rest are hidden;
 * what is remembered is the lines left out, so a line added by a new timetable shows until chosen away.
 */
object BusFilter {
    private const val STORE = "bus_filter"
    private const val HIDDEN = "hidden"

    /** Lines in the order a rider reads them: 2, 4, 10, 16 CD, then names such as STAR 1. */
    fun sorted(lines: Collection<String>): List<String> = lines.distinct().sortedWith(
        compareBy<String> { Regex("^\\d+").find(it)?.value?.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it })

    /** The lines whose name holds [query], however it is capitalised or spaced; all of them for an empty one. */
    fun matching(lines: List<String>, query: String): List<String> {
        val wanted = query.filterNot(Char::isWhitespace)
        return if (wanted.isEmpty()) lines
            else lines.filter { it.filterNot(Char::isWhitespace).contains(wanted, ignoreCase = true) }
    }

    fun hidden(context: Context): Set<String> =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getStringSet(HIDDEN, null).orEmpty().toSet()

    fun saveHidden(context: Context, hidden: Set<String>) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putStringSet(HIDDEN, hidden).apply()
    }

    /** The lines among [all] the rider has left ticked. */
    fun shown(all: Collection<String>, hidden: Set<String>) = all.filter { it !in hidden }
}
