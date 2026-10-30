package com.timeorganizer.usagetracker.push

import com.google.gson.JsonParser

/**
 * One notification the server pushed (prompt 22), read from FCM's data fields — the server's `NativePushMessage`, its wire
 * contract: `id` (the notification row an action is taken on), `type` (the kind's key), `title`, `body`, and when present
 * `tag`, `url` (a route of the web app, starting with `/`), `subjectKind` / `subjectId`, `originallyDueAt` and `actions`
 * (a JSON array of `{ action, title }` in the user's language). **Pure**: no Android types, so the reading is JVM-tested.
 */
data class PushMessage(
    val id: Long,
    val type: String,
    val title: String,
    val body: String,
    val tag: String?,
    val url: String?,
    val actions: List<PushButton>,
) {
    /** Which of the app's channels it is posted on, by its kind. */
    val channel: ServerChannel get() = ServerChannel.of(type)

    /**
     * Where a tap goes: the web app at the producer's route, else its root. A route that isn't a plain path on the web app
     * (`//host`, `https:`) is ignored — a notification must never open another site.
     */
    fun openUrl(webAppRoot: String): String {
        val path = url?.takeIf { it.startsWith("/") && !it.startsWith("//") }?.removePrefix("/") ?: return webAppRoot
        return webAppRoot + path
    }

    companion object {
        /**
         * The message, or `null` when it isn't one we can show: no id, no title, or not the server's shape at all. The
         * buttons are kept only when well formed (an `action` and a `title` each); a malformed `actions` field costs the
         * buttons, never the notification.
         */
        fun parse(data: Map<String, String>): PushMessage? {
            val id = data["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val title = data["title"]?.takeIf { it.isNotBlank() } ?: return null
            return PushMessage(
                id = id,
                type = data["type"].orEmpty(),
                title = title,
                body = data["body"].orEmpty(),
                tag = data["tag"]?.takeIf { it.isNotBlank() },
                url = data["url"]?.takeIf { it.isNotBlank() },
                actions = buttons(data["actions"]),
            )
        }

        /** At most two: what a notification shows, and what the server declares. */
        private fun buttons(json: String?): List<PushButton> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val array = JsonParser.parseString(json).takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
                array.mapNotNull { element ->
                    val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val action = obj.get("action")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                    val title = obj.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                    if (action == null || title == null) null else PushButton(action, title)
                }.take(MAX_BUTTONS)
            } catch (_: RuntimeException) {
                emptyList()
            }
        }

        const val MAX_BUTTONS = 2
    }
}

/** A button: [action] is the server's id for it (`done`, `snooze`, …), posted back as is; [title] is already in the user's language. */
data class PushButton(val action: String, val title: String)

/**
 * The app's channels for what the server pushes, one per kind of thing, so a user can mute timers without
 * losing reminders — and none of them another feature's (the phone's own daily-limit notice keeps `daily_budget`).
 */
enum class ServerChannel(val id: String) {
    /** Reminders and things due: a personal reminder, a to-do, a routine item. */
    REMINDERS("server_reminders"),

    /** A timer the user started: a phase ending, a stopwatch still running. */
    TIMERS("server_timers"),

    /** The planner: a block starting, overrunning, missed. */
    PLANNER("server_planner"),

    /** A daily limit reached somewhere other than in front of the phone. */
    LIMITS("server_limits"),

    /** Everything else: a tracker gone quiet, the briefing, the evening, routines, an unknown kind. */
    OTHER("server_other");

    companion object {
        private val BY_TYPE = mapOf(
            "PersonalReminder" to REMINDERS,
            "TodoItemDue" to REMINDERS,
            "RoutineItemDue" to REMINDERS,
            "DeadlineApproaching" to REMINDERS,
            "ReminderDigest" to REMINDERS,
            "TimerBoundary" to TIMERS,
            "StopwatchCheckIn" to TIMERS,
            "PlannedBlockStarting" to PLANNER,
            "PlannedBlockOverrun" to PLANNER,
            "PlannedBlocksMissed" to PLANNER,
            "ActivityBudgetApproaching" to LIMITS,
            "ActivityBudgetReached" to LIMITS,
        )

        /** A kind the app doesn't know yet (the server only adds them) goes to [OTHER]: shown, never dropped. */
        fun of(type: String): ServerChannel = BY_TYPE[type] ?: OTHER
    }
}
