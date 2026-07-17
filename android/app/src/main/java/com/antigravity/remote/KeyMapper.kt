package com.antigravity.remote

import androidx.compose.ui.input.key.Key

object KeyMapper {
    fun mapKey(key: Key): String? {
        return when (key) {
            Key.Enter -> "Enter"
            Key.Backspace -> "Backspace"
            Key.Spacebar -> "Space"
            Key.ShiftLeft, Key.ShiftRight -> "Shift"
            Key.CtrlLeft, Key.CtrlRight -> "Ctrl"
            Key.AltLeft, Key.AltRight -> "Alt"
            Key.Escape -> "Escape"
            Key.DirectionUp -> "ArrowUp"
            Key.DirectionDown -> "ArrowDown"
            Key.DirectionLeft -> "ArrowLeft"
            Key.DirectionRight -> "ArrowRight"
            Key.MetaLeft, Key.MetaRight -> "Win"
            Key.Tab -> "Tab"
            Key.CapsLock -> "CapsLock"
            Key.NumLock -> "NumLock"
            Key.ScrollLock -> "ScrollLock"
            Key.Insert -> "Insert"
            Key.Delete -> "Delete"
            Key.Home, Key.MoveHome -> "Home"
            Key.MoveEnd -> "End"
            Key.PageUp -> "PageUp"
            Key.PageDown -> "PageDown"
            Key.PrintScreen -> "PrintScreen"
            Key.F1 -> "F1"
            Key.F2 -> "F2"
            Key.F3 -> "F3"
            Key.F4 -> "F4"
            Key.F5 -> "F5"
            Key.F6 -> "F6"
            Key.F7 -> "F7"
            Key.F8 -> "F8"
            Key.F9 -> "F9"
            Key.F10 -> "F10"
            Key.F11 -> "F11"
            Key.F12 -> "F12"
            else -> null
        }
    }

    fun splitIntoUnicodeCharacters(input: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < input.length) {
            val codePoint = input.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            result.add(String(Character.toChars(codePoint)))
            i += charCount
        }
        return result
    }
}
