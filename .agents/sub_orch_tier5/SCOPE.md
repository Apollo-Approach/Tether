# Scope: Adversarial Hardening (Tier 5) Phase

## Architecture
- **Android Client**:
  - `com.antigravity.remote.MainActivity`: WebSocket client, UI gesture/keyboard capture.
  - `com.antigravity.remote.KeyMapper`: Maps Android keystrokes to JSON protocol values.
- **Receiver Server**:
  - `receiver/receiver.py`: Python WebSocket server, decodes packets, maps actions, triggers input actions on Windows host.
- **Tests**:
  - `/tests`: Python integration tests and stress tests.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | Iteration 1: Gap Discovery | Challenger analysis of source and tests using `test-coverage-audit` | None | DONE |
| 2 | Iteration 1: Integration & Fixes | Worker integrates tests and fixes exposed bugs | M1 | DONE |
| 3 | Iteration 1: Verification | Verification by Reviewers, Challenger, and Forensic Auditor | M2 | DONE |
| 4 | Iteration 2 (if gaps found) | Repeat gap analysis and resolution loop | M3 | SKIPPED |

## Interface Contracts
- See `PROJECT.md` at root.
- All new adversarial tests must conform to existing JSON WebSocket protocol structure.
