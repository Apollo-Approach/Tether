package com.antigravity.remote

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyMapperTest {
    @Test
    fun testMapKeyEnter() {
        assertEquals("Enter", KeyMapper.mapKey(Key.Enter))
    }

    @Test
    fun testMapKeyBackspace() {
        assertEquals("Backspace", KeyMapper.mapKey(Key.Backspace))
    }

    @Test
    fun testMapKeySpacebar() {
        assertEquals("Space", KeyMapper.mapKey(Key.Spacebar))
    }

    @Test
    fun testMapKeyShift() {
        assertEquals("Shift", KeyMapper.mapKey(Key.ShiftLeft))
        assertEquals("Shift", KeyMapper.mapKey(Key.ShiftRight))
    }

    @Test
    fun testMapKeyCtrl() {
        assertEquals("Ctrl", KeyMapper.mapKey(Key.CtrlLeft))
        assertEquals("Ctrl", KeyMapper.mapKey(Key.CtrlRight))
    }

    @Test
    fun testMapKeyAlt() {
        assertEquals("Alt", KeyMapper.mapKey(Key.AltLeft))
        assertEquals("Alt", KeyMapper.mapKey(Key.AltRight))
    }

    @Test
    fun testMapKeyEscape() {
        assertEquals("Escape", KeyMapper.mapKey(Key.Escape))
    }

    @Test
    fun testMapKeyArrowKeys() {
        assertEquals("ArrowUp", KeyMapper.mapKey(Key.DirectionUp))
        assertEquals("ArrowDown", KeyMapper.mapKey(Key.DirectionDown))
        assertEquals("ArrowLeft", KeyMapper.mapKey(Key.DirectionLeft))
        assertEquals("ArrowRight", KeyMapper.mapKey(Key.DirectionRight))
    }

    @Test
    fun testMapKeyNewKeys() {
        assertEquals("Win", KeyMapper.mapKey(Key.MetaLeft))
        assertEquals("Win", KeyMapper.mapKey(Key.MetaRight))
        assertEquals("Tab", KeyMapper.mapKey(Key.Tab))
        assertEquals("CapsLock", KeyMapper.mapKey(Key.CapsLock))
        assertEquals("NumLock", KeyMapper.mapKey(Key.NumLock))
        assertEquals("ScrollLock", KeyMapper.mapKey(Key.ScrollLock))
        assertEquals("Insert", KeyMapper.mapKey(Key.Insert))
        assertEquals("Delete", KeyMapper.mapKey(Key.Delete))
        assertEquals("Home", KeyMapper.mapKey(Key.Home))
        assertEquals("Home", KeyMapper.mapKey(Key.MoveHome))
        assertEquals("End", KeyMapper.mapKey(Key.MoveEnd))
        assertEquals("PageUp", KeyMapper.mapKey(Key.PageUp))
        assertEquals("PageDown", KeyMapper.mapKey(Key.PageDown))
        assertEquals("PrintScreen", KeyMapper.mapKey(Key.PrintScreen))
        assertEquals("F1", KeyMapper.mapKey(Key.F1))
        assertEquals("F2", KeyMapper.mapKey(Key.F2))
        assertEquals("F3", KeyMapper.mapKey(Key.F3))
        assertEquals("F4", KeyMapper.mapKey(Key.F4))
        assertEquals("F5", KeyMapper.mapKey(Key.F5))
        assertEquals("F6", KeyMapper.mapKey(Key.F6))
        assertEquals("F7", KeyMapper.mapKey(Key.F7))
        assertEquals("F8", KeyMapper.mapKey(Key.F8))
        assertEquals("F9", KeyMapper.mapKey(Key.F9))
        assertEquals("F10", KeyMapper.mapKey(Key.F10))
        assertEquals("F11", KeyMapper.mapKey(Key.F11))
        assertEquals("F12", KeyMapper.mapKey(Key.F12))
    }

    @Test
    fun testMapKeyUnknown() {
        assertEquals(null, KeyMapper.mapKey(Key.Unknown))
    }

    @Test
    fun testSplitIntoUnicodeCharactersSimple() {
        val result = KeyMapper.splitIntoUnicodeCharacters("abc")
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun testSplitIntoUnicodeCharactersWithNewline() {
        val result = KeyMapper.splitIntoUnicodeCharacters("a\nb")
        assertEquals(listOf("a", "\n", "b"), result)
    }

    @Test
    fun testSplitIntoUnicodeCharactersEmoji() {
        val result = KeyMapper.splitIntoUnicodeCharacters("🚀")
        assertEquals(listOf("🚀"), result)
    }

    @Test
    fun testSplitIntoUnicodeCharactersMixed() {
        val result = KeyMapper.splitIntoUnicodeCharacters("Hi 🚀!")
        assertEquals(listOf("H", "i", " ", "🚀", "!"), result)
    }
}
