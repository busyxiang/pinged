package my.pinged.capture

/**
 * What spec 10.2's daily check tells a user who is not opening the app, or
 * null for nothing.
 *
 * The order is `MainActivity.bannerFor`'s, for its reason: a statement that
 * capture is broken outright outranks one that a single source has gone quiet.
 * Two of the banner's states make no claim about the listener here: a heartbeat
 * that could not be read, and a fresh install that has heard nothing and has
 * no past to have lost. Neither says anything about one source, which is read
 * from `capture_source`, so a quiet source is still named under both.
 */
internal object HealthAlert {
    /**
     * [key] names the problem without how long it has lasted, so a daily
     * check posts it once rather than every day it persists, and posts again
     * only when it changes.
     */
    data class Alert(val key: String, val title: String, val body: String)

    fun decide(report: CaptureReport, quiet: List<SourceGaps.Quiet>): Alert? = when {
        report.storageUnavailable -> Alert(
            key = "storage",
            title = "Pinged cannot open its data",
            body = "Nothing new is being recorded. Open Pinged to see what has happened.",
        )
        !report.granted -> Alert(
            key = "not-granted",
            title = "Pinged cannot see notifications",
            body = "Notification access is off, so nothing is being recorded.",
        )
        !report.healthUnreadable && report.lastNotificationAt != 0L && report.looksDead -> Alert(
            key = "stopped",
            title = "Pinged has heard nothing in ${days(report.staleForMillis)}",
            body = "Your phone may have shut it down. Nothing you spent since then was recorded.",
        )
        quiet.size == 1 -> quiet.single().let {
            Alert(
                key = "quiet:" + it.pkg,
                title = "No ${it.label} notifications in ${it.days} days",
                body = "There were ${it.expected} in the month before. Its notifications may have been turned off.",
            )
        }
        quiet.size > 1 -> Alert(
            key = "quiet:" + quiet.map { it.pkg }.sorted().joinToString(","),
            title = "${quiet.size} sources have gone quiet",
            body = quiet.joinToString(", ") { it.label } + ". Their notifications may have been turned off.",
        )
        else -> null
    }

    private fun days(millis: Long): String {
        val days = millis / SourceGaps.DAY_MILLIS
        return if (days == 1L) "a day" else "$days days"
    }
}
