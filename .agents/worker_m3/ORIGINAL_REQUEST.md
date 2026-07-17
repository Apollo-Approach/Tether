## 2026-07-14T23:22:00Z
You are Worker for Milestone M3 (Android UI and Input Capture).
Your identity: teamwork_preview_worker.
Your working directory: c:\Development\Monolith\.agents\worker_m3\.
Your objective is to:
1. Implement the input capture modifications in `MainActivity.kt` (`c:\Development\Monolith\android\app\src\main\java\com\antigravity\remote\MainActivity.kt`) based on the recommendations in Explorer 1's handoff report (`c:\Development\Monolith\.agents\explorer_m3_1\handoff.md`):
   - Replace the chained drag and tap modifiers with a custom unified gesture detector on the trackpad Box using `awaitPointerEventScope` to handle onTap (Left Click), onLongPress (Right Click), and relative drags without gesture conflicts.
   - Replace the OutlinedTextField value and onValueChange callback with an IME-resettable TextField state using a single space placeholder `" "` and a resetting guard flag.
   - Implement key event capture using Modifier.onKeyEvent, intercepting special keys (Enter -> "Enter", Backspace -> "Backspace", Spacebar -> "Space", Shift/Ctrl/Alt -> "Shift"/"Ctrl"/"Alt", Escape -> "Escape", Arrow keys -> "ArrowLeft"/"ArrowRight"/"ArrowUp"/"ArrowDown").
2. Extract the key mapping logic into a helper class `KeyMapper` in a new file `c:\Development\Monolith\android\app\src\main\java\com\antigravity\remote\KeyMapper.kt` so it can be unit tested.
3. Create a unit test `c:\Development\Monolith\android\app\src\test\java\com\antigravity\remote\KeyMapperTest.kt` to test the KeyMapper class.
4. Execute `run_command` in `c:\Development\Monolith\android\` to verify the build and tests:
   - `.\gradlew.bat clean assembleDebug`
   - `.\gradlew.bat test`
5. Write your findings and the build/test outcomes to handoff.md in your working directory and notify the parent Sub-Orchestrator (conv ID: 80559160-1dc7-4ab2-b0d1-86ae5ad1662d).

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A Forensic Auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.
