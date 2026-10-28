package com.timeorganizer.usagetracker.capture

/** One thought on its way to the server: what `POST tracker/android/capture` is sent. */
data class CaptureDraft(val text: String, val url: String?)

/** What one capture's answer means for its row in the queue. */
enum class CaptureSendOutcome {
    /** On the capture list (`added`, `alreadyOpen`, `reopened`): the row goes. */
    SENT,

    /** The server refused this capture's body (400, 413, 422). Kept, marked, and the next capture is tried. */
    REFUSED,

    /** A 401 the refresh could not cure: nothing else will get through either. Kept. */
    AUTH_REQUIRED,

    /** Offline, a 5xx, a throttle, a 404 from a proxy, 409 `noPriority` (true of every capture): kept, try again later. */
    WAIT
}

/**
 * **Pure.** Quick capture's rules (prompt 18): what a typed line or a share becomes, and what each answer does to the
 * queue. A capture is never dropped by the phone — not offline, not signed out, not refused — because a lost thought is
 * the one thing this feature exists to prevent; only the server taking it, or an account switch, removes a row.
 */
object CaptureRules {

    /** The server's `AndroidTrackerCaptureValidator.MaxTextLength`; the server cuts the name and note itself. */
    const val MAX_TEXT_LENGTH = 10_000

    /** `AndroidTrackerCaptureValidator.MaxUrlLength`. A longer link stays in the text, where it still reaches the note. */
    const val MAX_URL_LENGTH = 2048

    private val URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    /** The dialog's one field. Blank is nothing to save. */
    fun typed(text: String?): CaptureDraft? {
        val trimmed = text?.trim().orEmpty()
        return if (trimmed.isEmpty()) null else CaptureDraft(fit(trimmed), null)
    }

    /**
     * A share (`ACTION_SEND`, `text/plain`). Browsers send the link as the text and the page title as the subject, so
     * a bare link takes the subject as its words ("Article title" + the link) — a to-do named by a URL says nothing.
     * Otherwise the text is the thought, and its first link travels as `url` too (the server keeps one copy in the note).
     */
    fun shared(text: String?, subject: String?): CaptureDraft? {
        val body = text?.trim().orEmpty()
        val title = subject?.trim().orEmpty()
        val link = URL.find(body)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')?.takeIf { it.length <= MAX_URL_LENGTH }

        val words = when {
            body.isEmpty() -> title
            link != null && body == link && title.isNotEmpty() -> title
            else -> body
        }
        if (words.isEmpty()) return null
        return CaptureDraft(fit(words), link?.takeIf { it != words })
    }

    fun classify(status: Int?): CaptureSendOutcome = when (status) {
        null -> CaptureSendOutcome.WAIT
        in 200..299 -> CaptureSendOutcome.SENT
        401 -> CaptureSendOutcome.AUTH_REQUIRED
        400, 413, 422 -> CaptureSendOutcome.REFUSED
        else -> CaptureSendOutcome.WAIT
    }

    /** Whether a pass over the queue goes on to the next capture after this one's answer. */
    fun continues(outcome: CaptureSendOutcome): Boolean =
        outcome == CaptureSendOutcome.SENT || outcome == CaptureSendOutcome.REFUSED

    /** Cut to the server's length, never between the two halves of a surrogate pair. */
    private fun fit(text: String): String {
        if (text.length <= MAX_TEXT_LENGTH) return text
        val cut = if (Character.isHighSurrogate(text[MAX_TEXT_LENGTH - 1])) MAX_TEXT_LENGTH - 1 else MAX_TEXT_LENGTH
        return text.substring(0, cut)
    }
}
