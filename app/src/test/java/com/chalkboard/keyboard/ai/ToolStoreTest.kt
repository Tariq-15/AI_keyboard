package com.chalkboard.keyboard.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolStoreTest {

    @Test
    fun `assistants survive a round trip with quotes and line breaks intact`() {
        val assistants = listOf(
            CustomAssistant("Email", "Rewrite as a short email.\nSign off with \"Regards\"."),
            CustomAssistant("বাংলা", "Translate to Bangla"),
        )
        assertEquals(assistants, ToolStore.decodeAssistants(ToolStore.encodeAssistants(assistants)))
    }

    @Test
    fun `unreadable or half-filled entries are dropped rather than thrown`() {
        assertEquals(emptyList<CustomAssistant>(), ToolStore.decodeAssistants(""))
        assertEquals(emptyList<CustomAssistant>(), ToolStore.decodeAssistants("not json"))
        assertEquals(
            listOf(CustomAssistant("Kept", "Do the thing")),
            ToolStore.decodeAssistants(
                """[{"name":"No instruction"},{"name":"Kept","instruction":"Do the thing"},7]"""
            ),
        )
    }
}
