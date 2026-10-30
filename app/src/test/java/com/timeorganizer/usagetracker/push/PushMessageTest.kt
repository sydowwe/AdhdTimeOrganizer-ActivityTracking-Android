package com.timeorganizer.usagetracker.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The server's `NativePushMessage` read on the phone (prompt 22): what is shown, what is dropped, where a tap goes. */
class PushMessageTest {

    private val full = mapOf(
        "id" to "42",
        "type" to "TodoItemDue",
        "title" to "Pay rent",
        "body" to "Due at 18:00",
        "tag" to "todo-7",
        "url" to "/todo-list/3",
        "subjectKind" to "todoListItem",
        "subjectId" to "7",
        "actions" to """[{"action":"done","title":"Done"},{"action":"snooze","title":"Snooze 1 h"}]""",
    )

    @Test
    fun readsEveryField() {
        val message = PushMessage.parse(full)!!

        assertEquals(42L, message.id)
        assertEquals("TodoItemDue", message.type)
        assertEquals("Pay rent", message.title)
        assertEquals("Due at 18:00", message.body)
        assertEquals("todo-7", message.tag)
        assertEquals("/todo-list/3", message.url)
        assertEquals(listOf(PushButton("done", "Done"), PushButton("snooze", "Snooze 1 h")), message.actions)
        assertEquals(ServerChannel.REMINDERS, message.channel)
    }

    @Test
    fun withoutAnIdOrATitle_isNotShown() {
        assertNull(PushMessage.parse(full - "id"))
        assertNull(PushMessage.parse(full + ("id" to "abc")))
        assertNull(PushMessage.parse(full + ("id" to "0")))
        assertNull(PushMessage.parse(full - "title"))
        assertNull(PushMessage.parse(full + ("title" to " ")))
        assertNull(PushMessage.parse(emptyMap()))
    }

    @Test
    fun theOptionalFields_mayBeAbsent() {
        val message = PushMessage.parse(mapOf("id" to "1", "title" to "Reminder"))!!

        assertEquals("", message.body)
        assertEquals("", message.type)
        assertNull(message.tag)
        assertNull(message.url)
        assertTrue(message.actions.isEmpty())
        assertEquals(ServerChannel.OTHER, message.channel)
    }

    @Test
    fun aMalformedActionsField_costsTheButtons_notTheNotification() {
        listOf("not json", "{}", """[1, "x"]""", """[{"action":"done"}]""", """[{"title":"Done"}]""", """[{"action":"","title":"Done"}]""")
            .forEach { actions ->
                val message = PushMessage.parse(full + ("actions" to actions))
                assertEquals("still shown for $actions", "Pay rent", message?.title)
                assertTrue("no buttons for $actions", message!!.actions.isEmpty())
            }
    }

    @Test
    fun atMostTwoButtons() {
        val three = """[{"action":"a","title":"A"},{"action":"b","title":"B"},{"action":"c","title":"C"}]"""
        assertEquals(listOf("a", "b"), PushMessage.parse(full + ("actions" to three))!!.actions.map { it.action })
    }

    @Test
    fun aTapOpensTheWebAppAtTheRoute_orItsRoot() {
        val root = "https://test.adhdtimeorganizer.com/"

        assertEquals("https://test.adhdtimeorganizer.com/todo-list/3", PushMessage.parse(full)!!.openUrl(root))
        assertEquals(root, PushMessage.parse(full - "url")!!.openUrl(root))
    }

    @Test
    fun aRouteToAnotherSite_isNeverOpened() {
        val root = "https://test.adhdtimeorganizer.com/"
        listOf("//evil.example/x", "https://evil.example/", "javascript:alert(1)", "todo-list/3").forEach { url ->
            assertEquals(url, root, PushMessage.parse(full + ("url" to url))!!.openUrl(root))
        }
    }

    @Test
    fun eachKind_goesToItsChannel_andAnUnknownOneToOther() {
        mapOf(
            "PersonalReminder" to ServerChannel.REMINDERS,
            "TodoItemDue" to ServerChannel.REMINDERS,
            "RoutineItemDue" to ServerChannel.REMINDERS,
            "TimerBoundary" to ServerChannel.TIMERS,
            "StopwatchCheckIn" to ServerChannel.TIMERS,
            "PlannedBlockOverrun" to ServerChannel.PLANNER,
            "PlannedBlockStarting" to ServerChannel.PLANNER,
            "ActivityBudgetReached" to ServerChannel.LIMITS,
            "ActivityBudgetApproaching" to ServerChannel.LIMITS,
            "TrackerSilent" to ServerChannel.OTHER,
            "SomethingNew" to ServerChannel.OTHER,
        ).forEach { (type, channel) -> assertEquals(type, channel, ServerChannel.of(type)) }
    }

    @Test
    fun theChannelIds_areFixed() {
        // A channel's id is the user's settings for it: renaming one drops their choices and makes a new channel.
        assertEquals(
            listOf("server_reminders", "server_timers", "server_planner", "server_limits", "server_other"),
            ServerChannel.entries.map { it.id }
        )
    }
}
