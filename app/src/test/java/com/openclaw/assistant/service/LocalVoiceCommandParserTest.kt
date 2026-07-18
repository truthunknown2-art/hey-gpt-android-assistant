package com.openclaw.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalVoiceCommandParserTest {
    @Test
    fun `parses a contact call`() {
        assertEquals(
            LocalVoiceCommand.Call("Mom"),
            LocalVoiceCommandParser.parse("call Mom"),
        )
    }

    @Test
    fun `parses latest and unread SMS variants`() {
        assertEquals(
            LocalVoiceCommand.ReadSms(unreadOnly = false),
            LocalVoiceCommandParser.parse("read my latest text"),
        )
        assertEquals(
            LocalVoiceCommand.ReadSms(unreadOnly = true),
            LocalVoiceCommandParser.parse("please read my unread messages"),
        )
    }

    @Test
    fun `parses Spotify query without retaining destination words`() {
        assertEquals(
            LocalVoiceCommand.PlayMedia("Kind of Blue"),
            LocalVoiceCommandParser.parse("play Kind of Blue on Spotify"),
        )
    }

    @Test
    fun `conversation falls through to ChatGPT`() {
        assertNull(LocalVoiceCommandParser.parse("tell me about black holes"))
    }
}
