## Challenge Summary

**Overall risk assessment**: LOW

The receiver codebase is simple, structured cleanly, and provides validation checks. The risk is assessed as LOW since it runs locally, but there are a few edge cases related to input decoding and rate limits that could be improved for robust production usage.

## Challenges

### [Low] Challenge 1: Unhandled UnicodeDecodeError for Non-UTF-8 Binary Messages

- **Assumption challenged**: The WebSocket message payload is always valid UTF-8.
- **Attack scenario**: A compromised client or fuzzing tool connects and sends arbitrary non-UTF-8 binary frames (e.g. `b'\xff\xff'`).
- **Blast radius**: `json.loads` will raise a `UnicodeDecodeError` when converting from bytes. Since the server only catches `json.JSONDecodeError`, this exception is unhandled in `handle_client`. The `websockets` library catches unhandled exceptions in the handler, prints the traceback, and terminates that client connection. The server does not crash, but the client connection is dropped instead of receiving a clean error response.
- **Mitigation**: Wrap the `json.loads` call in a broader `ValueError` catch (or catch both `json.JSONDecodeError` and `UnicodeDecodeError` specifically).

### [Low] Challenge 2: Lack of Application-Level Rate Limiting

- **Assumption challenged**: The client sends messages at a human-interactable speed.
- **Attack scenario**: A malicious client sends an infinite sequence of messages at maximum speed.
- **Blast radius**: The server processes messages sequentially per client. A flood of events could consume substantial CPU resources on the host machine.
- **Mitigation**: Introduce rate limiting or throttling inside `handle_client` based on timestamps or token-bucket algorithm.

## Stress Test Results

- **Multiple Concurrent Clients** → 5 concurrent clients connect and send keys → Server logs all 5 keys successfully without dropping any connections → PASS
- **Accidental Connection Drop** → Client sends click and drops transport directly, then a new client connects → Server detects the disconnect gracefully, accepts new connection, and handles the subsequent click → PASS
- **Malformed JSON Streams** → Client sends non-JSON text, empty strings, and unclosed JSON braces, followed by a valid command → Server prints errors to stderr for invalid ones and successfully executes the valid command → PASS
- **Missing Payload Fields** → Client sends messages missing `event`, `dx`/`dy`, `button`, or `key` fields → Server logs validation errors to stderr and remains healthy → PASS
- **Unexpected Field Types** → Client sends incorrect types (e.g., bool or string for coordinates) → Server rejects them with type validation errors on stderr and continues → PASS
- **Massive Payload Size** → Client sends a 2MB message → Connection is rejected/closed by the library transport limits, and the server recovers to accept new connections immediately → PASS

## Unchallenged Areas

- **Android App Compile & Build Quality** — out of scope for the current E2E test and receiver focus, as the Kotlin app is an initial layout mock.
- **PyAutoGUI OS-Level Emulation** — PyAutoGUI library calls are bypassed under `--mock` mode during E2E testing to avoid messing up the host environment.
