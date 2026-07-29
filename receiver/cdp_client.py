"""
Client for interacting with the Rover Chrome DevTools Protocol (CDP).
"""
from __future__ import annotations

import asyncio
import json
import re
import subprocess
import sys
import urllib.request
import websockets
from contextlib import asynccontextmanager

class RoverCDPClient:
    """Encapsulates CDP logic for communicating with the Rover app."""
    
    def __init__(self):
        self._cdp_port = None

    @asynccontextmanager
    async def _cdp_session(self, ws_url: str, timeout: float = 5.0):
        try:
            async with websockets.connect(ws_url, max_size=50*1024*1024, close_timeout=5) as ws:
                msg_id = 1
                async def send_cdp(method, params=None):
                    nonlocal msg_id
                    payload = {"id": msg_id, "method": method, "params": params or {}}
                    msg_id += 1
                    await ws.send(json.dumps(payload))
                    start_time = asyncio.get_event_loop().time()
                    while True:
                        time_left = timeout - (asyncio.get_event_loop().time() - start_time)
                        if time_left <= 0:
                            raise asyncio.TimeoutError(f"CDP method {method} timed out")
                        try:
                            resp_str = await asyncio.wait_for(ws.recv(), timeout=time_left)
                            resp = json.loads(resp_str)
                            if resp.get("id") == payload["id"]:
                                if "error" in resp:
                                    print(f"[CDP] Error in {method}: {resp['error']}", file=sys.stderr)
                                return resp
                        except asyncio.TimeoutError:
                            raise asyncio.TimeoutError(f"CDP method {method} timed out")
                yield ws, send_cdp
        except Exception as e:
            print(f"[CDP] Session error: {e}", file=sys.stderr)
            raise

    def discover_port(self) -> int | None:
        """Discover Rover's Chrome DevTools Protocol port by scanning its process."""
        try:
            # Find Rover process and its listening port
            result = subprocess.run(
                ['powershell', '-Command',
                 'Get-Process -Name Antigravity -ErrorAction SilentlyContinue | '
                 'Where-Object {$_.Id -eq (Get-CimInstance Win32_Process -Filter '
                 '"Name=\'Antigravity.exe\'" | Where-Object {$_.CommandLine -notlike '
                 '"*--type=*"}).ProcessId} | ForEach-Object { '
                 '(Get-NetTCPConnection -OwningProcess $_.Id -State Listen '
                 '-ErrorAction SilentlyContinue).LocalPort }'],
                capture_output=True, text=True, timeout=10
            )
            ports = [int(p.strip()) for p in result.stdout.strip().split('\n') if p.strip().isdigit()]
            for port in ports:
                try:
                    resp = urllib.request.urlopen(f"http://127.0.0.1:{port}/json/version", timeout=2)
                    data = json.loads(resp.read())
                    if 'Antigravity' in data.get('User-Agent', ''):
                        self._cdp_port = port
                        print(f"[CDP] Discovered Antigravity DevTools on port {port}", flush=True)
                        return port
                except Exception:
                    continue
        except Exception as e:
            print(f"[CDP] Discovery via process scan failed: {e}", file=sys.stderr)
    
        # Fallback: brute-scan likely port range
        try:
            result = subprocess.run(
                ['powershell', '-Command',
                 'Get-NetTCPConnection -OwningProcess '
                 '(Get-Process -Name Antigravity -ErrorAction SilentlyContinue).Id '
                 '-State Listen -ErrorAction SilentlyContinue | '
                 'Select-Object -ExpandProperty LocalPort'],
                capture_output=True, text=True, timeout=10
            )
            ports = [int(p.strip()) for p in result.stdout.strip().split('\n') if p.strip().isdigit()]
            for port in ports:
                try:
                    resp = urllib.request.urlopen(f"http://127.0.0.1:{port}/json/version", timeout=2)
                    data = json.loads(resp.read())
                    if 'Antigravity' in data.get('User-Agent', '') or 'Electron' in data.get('User-Agent', ''):
                        self._cdp_port = port
                        print(f"[CDP] Discovered Antigravity DevTools on port {port}", flush=True)
                        return port
                except Exception:
                    continue
        except Exception as e:
            print(f"[CDP] Fallback discovery failed: {e}", file=sys.stderr)
    
        print("[CDP] Could not discover Antigravity DevTools port", file=sys.stderr, flush=True)
        return None

    async def get_page_ws(self, conversation_id: str | None = None) -> str | None:
        """Get the WebSocket URL for the Rover page target.
        If conversation_id is provided, prefer the page whose URL contains it."""
        if not self._cdp_port:
            await asyncio.to_thread(self.discover_port)
        if not self._cdp_port:
            return None
    
        try:
            def fetch():
                return urllib.request.urlopen(f"http://127.0.0.1:{self._cdp_port}/json", timeout=2).read()
            resp_data = await asyncio.to_thread(fetch)
            targets = json.loads(resp_data)
            pages = [t for t in targets if t.get('type') == 'page']
            if not pages:
                return None
    
            # Prefer a page matching the conversation ID
            if conversation_id:
                for p in pages:
                    if conversation_id in p.get('url', ''):
                        return p['webSocketDebuggerUrl']
    
            # Fall back to first page
            return pages[0]['webSocketDebuggerUrl']
        except Exception as e:
            print(f"[CDP] Error getting page target: {e}", file=sys.stderr)
            # Port may have changed (Rover restarted), rediscover
            self._cdp_port = None
            await asyncio.to_thread(self.discover_port)
            if not self._cdp_port:
                return None
            try:
                def fetch():
                    return urllib.request.urlopen(f"http://127.0.0.1:{self._cdp_port}/json", timeout=2).read()
                resp_data = await asyncio.to_thread(fetch)
                targets = json.loads(resp_data)
                pages = [t for t in targets if t.get('type') == 'page']
                if not pages:
                    return None
                if conversation_id:
                    for p in pages:
                        if conversation_id in p.get('url', ''):
                            return p['webSocketDebuggerUrl']
                return pages[0]['webSocketDebuggerUrl']
            except Exception as e2:
                print(f"[CDP] Retry error getting page target: {e2}", file=sys.stderr)
                return None

    async def stop_task(self, task_id: str, conversation_id: str | None = None) -> None:
        """Stop a running task by programmatically clicking its stop button."""
        ws_url = await self.get_page_ws(conversation_id)
        if not ws_url:
            return

        try:
            async with self._cdp_session(ws_url) as (ws, send_cdp):
                js = f"""
                (() => {{
                    const stopBtn = document.querySelector(`button[data-tooltip-id="stop-task-{task_id}"]`);
                    if (stopBtn) stopBtn.click();
                }})();
                """
                await send_cdp("Runtime.evaluate", {"expression": js})
        except Exception as e:
            print(f"[CDP] Error stopping task: {e}", file=sys.stderr)

    async def inject_chat(self, message_text: str, conversation_id: str | None = None, skip_navigation: bool = False) -> bool:
        """Inject a chat message into Rover's chat input via CDP.
        Navigates to the correct conversation if needed, detects subagent views,
        then uses Input.insertText + Enter keypress to submit.
        
        skip_navigation: if True, skip URL check and navigate — use when dialog is open
                         and navigation would dismiss it (e.g. approval responses).
        """
        ws_url = await self.get_page_ws(conversation_id)
        if not ws_url:
            print("[CDP] No page target available", file=sys.stderr, flush=True)
            return False
    
        try:
            async with self._cdp_session(ws_url, timeout=10.0) as (ws, send_cdp):
                async def get_current_url():
                    r = await send_cdp("Runtime.evaluate", {
                        "expression": "window.location.href",
                        "returnByValue": True
                    })
                    result = r.get("result", {}).get("result", {})
                    return result.get("value", "") if isinstance(result, dict) else ""
    
                async def navigate_to_conversation(base_url):
                    target_url = f"{base_url}/c/{conversation_id}"
                    print(f"[CDP] Navigating to conversation {conversation_id[:12]}...", flush=True)
                    await send_cdp("Page.enable")
                    await send_cdp("Page.navigate", {"url": target_url})
                    start_time = asyncio.get_event_loop().time()
                    while asyncio.get_event_loop().time() - start_time < 5.0:
                        try:
                            evt_str = await asyncio.wait_for(ws.recv(), timeout=1.0)
                            evt = json.loads(evt_str)
                            if evt.get("method") == "Page.loadEventFired":
                                break
                        except asyncio.TimeoutError:
                            continue
    
                async def is_subagent_view():
                    r = await send_cdp("Runtime.evaluate", {
                        "expression": "document.body.innerText.includes('Cannot send message to subagent')",
                        "returnByValue": True
                    })
                    return r.get("result", {}).get("result", {}).get("value", False)
    
                # Step 1: Navigate to the correct conversation if needed
                if conversation_id and not skip_navigation:
                    current_url = await get_current_url()
                    if conversation_id not in current_url:
                        base_match = re.match(r'(https?://[^/]+)', current_url)
                        if base_match:
                            await navigate_to_conversation(base_match.group(1))
                    
                    # Step 2: Check if navigation landed on a subagent view
                    if await is_subagent_view():
                        print("[CDP] Detected subagent view, re-navigating to main conversation...", flush=True)
                        current_url = await get_current_url()
                        base_match = re.match(r'(https?://[^/]+)', current_url)
                        if base_match:
                            await navigate_to_conversation(base_match.group(1))
    
                # Step 3: Focus the contenteditable chat input
                focus_result = await send_cdp("Runtime.evaluate", {
                    "expression": """
                        (() => {
                            const ce = document.querySelector('[contenteditable="true"]');
                            if (!ce) return {error: 'No contenteditable found'};
                            ce.focus();
                            window.getSelection().selectAllChildren(ce);
                            return {ok: true};
                        })()
                    """,
                    "returnByValue": True
                })
    
                # Validate focus succeeded
                focus_value = focus_result.get("result", {}).get("result", {}).get("value", {})
                if isinstance(focus_value, dict) and focus_value.get("error"):
                    # Page may still be loading after navigation — retry once after a longer wait
                    print(f"[CDP] Contenteditable not found, retrying after 3s...", flush=True)
                    await asyncio.sleep(3.0)
                    focus_result = await send_cdp("Runtime.evaluate", {
                        "expression": """
                            (() => {
                                const ce = document.querySelector('[contenteditable="true"]');
                                if (!ce) return {error: 'No contenteditable found'};
                                ce.focus();
                                window.getSelection().selectAllChildren(ce);
                                return {ok: true};
                            })()
                        """,
                        "returnByValue": True
                    })
                    focus_value = focus_result.get("result", {}).get("result", {}).get("value", {})
                    if isinstance(focus_value, dict) and focus_value.get("error"):
                        print(f"[CDP] Contenteditable still not found after retry — injection failed", file=sys.stderr, flush=True)
                        return False

                await asyncio.sleep(0.1)
    
                # Step 4: Insert the message text
                await send_cdp("Input.insertText", {"text": message_text})
    
                await asyncio.sleep(0.2)
    
                # Step 5: Press Enter to submit
                await send_cdp("Input.dispatchKeyEvent", {
                    "type": "keyDown",
                    "key": "Enter",
                    "code": "Enter",
                    "text": "\r",
                    "unmodifiedText": "\r",
                    "windowsVirtualKeyCode": 13,
                    "nativeVirtualKeyCode": 13
                })
                await asyncio.sleep(0.05)
                await send_cdp("Input.dispatchKeyEvent", {
                    "type": "char",
                    "key": "Enter",
                    "text": "\r",
                    "unmodifiedText": "\r",
                    "windowsVirtualKeyCode": 13,
                    "nativeVirtualKeyCode": 13
                })
                await asyncio.sleep(0.05)
                await send_cdp("Input.dispatchKeyEvent", {
                    "type": "keyUp",
                    "key": "Enter",
                    "code": "Enter",
                    "windowsVirtualKeyCode": 13,
                    "nativeVirtualKeyCode": 13
                })

                # Fallback: Also try clicking the submit button if Enter failed
                await asyncio.sleep(0.5)
                await send_cdp("Runtime.evaluate", {
                    "expression": """
                        (() => {
                            const ce = document.querySelector('[contenteditable="true"]');
                            if (!ce) return;
                            if (!ce.innerText.trim()) return;
                            
                            let curr = ce;
                            for (let i = 0; i < 6; i++) {
                                if (curr.parentElement) curr = curr.parentElement;
                            }
                            const btns = Array.from(curr.querySelectorAll('button'));
                            const sendBtn = btns.find(b => b.getAttribute('aria-label') === 'Send message' || b.getAttribute('aria-label') === 'Send Message');
                            if (sendBtn && !sendBtn.disabled) {
                                sendBtn.click();
                            } else {
                                const submit = curr.querySelector('button[type="submit"]');
                                if (submit && !submit.disabled) submit.click();
                            }
                        })()
                    """
                })
    
                return True
        except Exception as e:
            print(f"[CDP] Injection error: {e}", file=sys.stderr, flush=True)
            # Port may have changed
            self._cdp_port = None
            return False

    async def inject_keystrokes(self, text: str, conversation_id: str | None = None) -> bool:
        """Inject text by dispatching raw key events via CDP.
        Does NOT require a contenteditable element — types directly into whatever
        has focus (e.g. Antigravity's permission dialog input).
        Presses Enter after typing the text."""
        ws_url = await self.get_page_ws(conversation_id)
        if not ws_url:
            print("[CDP] No page target available for keystrokes", file=sys.stderr, flush=True)
            return False

        try:
            async with self._cdp_session(ws_url) as (ws, send_cdp):
                await send_cdp("Input.insertText", {"text": text})
                await asyncio.sleep(0.05)

                await send_cdp("Input.dispatchKeyEvent", {
                    "type": "keyDown",
                    "key": "Enter",
                    "code": "Enter",
                    "text": "\r",
                    "unmodifiedText": "\r",
                    "windowsVirtualKeyCode": 13,
                    "nativeVirtualKeyCode": 13
                })
                await asyncio.sleep(0.05)
                await send_cdp("Input.dispatchKeyEvent", {
                    "type": "char",
                    "key": "Enter",
                    "text": "\r",
                    "unmodifiedText": "\r",
                    "windowsVirtualKeyCode": 13,
                    "nativeVirtualKeyCode": 13
                })
                await asyncio.sleep(0.05)
                await send_cdp("Input.dispatchKeyEvent", {
                    "type": "keyUp",
                    "key": "Enter",
                    "code": "Enter",
                    "windowsVirtualKeyCode": 13,
                    "nativeVirtualKeyCode": 13
                })

                print(f"[CDP] Injected keystrokes: '{text}' + Enter", flush=True)
                return True
        except Exception as e:
            print(f"[CDP] Keystroke injection error: {e}", file=sys.stderr, flush=True)
            self._cdp_port = None
            return False

    async def inject_stop(self, conversation_id: str | None = None) -> bool:
        """Stop the current agent execution in Antigravity via Ctrl+D shortcut."""
        ws_url = await self.get_page_ws(conversation_id)
        if not ws_url:
            print("[CDP] No page target available for stop", file=sys.stderr, flush=True)
            return False

        try:
            async with self._cdp_session(ws_url) as (ws, send_cdp):
                # Send Ctrl+D keypress
                await send_cdp("Input.dispatchKeyEvent", {
                    "type": "rawKeyDown",
                    "key": "d",
                    "code": "KeyD",
                    "windowsVirtualKeyCode": 68,
                    "nativeVirtualKeyCode": 68,
                    "modifiers": 2  # Ctrl modifier
                })
                await asyncio.sleep(0.05)
                await send_cdp("Input.dispatchKeyEvent", {
                    "type": "keyUp",
                    "key": "d",
                    "code": "KeyD",
                    "windowsVirtualKeyCode": 68,
                    "nativeVirtualKeyCode": 68,
                    "modifiers": 2
                })

                print("[CDP] Stop signal sent (Ctrl+D)", flush=True)
                return True
        except Exception as e:
            print(f"[CDP] Stop error: {e}", file=sys.stderr, flush=True)
            self._cdp_port = None
            return False

    async def inject_approve(self, option_index: int, option_text: str = "", conversation_id: str | None = None) -> bool:
        """Click an approval option button in Antigravity's ask_permission dialog.
        
        Prefers matching by option_text (exact button text) to avoid index ordering issues.
        Falls back to index-based matching within dialog containers only.
        option_index: 0=Approve, 1=Approve Once, 2=Approve (Project), 3=Deny
        """
        ws_url = await self.get_page_ws(conversation_id)
        if not ws_url:
            print("[CDP] No page target available for approve", file=sys.stderr, flush=True)
            return False

        try:
            async with self._cdp_session(ws_url) as (ws, send_cdp):
                async def eval_js(js):
                    res = await send_cdp("Runtime.evaluate", {"expression": js, "returnByValue": True})
                    return res.get("result", {}).get("result", {}).get("value")

                # Escape the option text for safe JS injection
                safe_text = option_text.replace("\\", "\\\\").replace("'", "\\'")

                result = await eval_js(f"""
                (() => {{
                    const targetText = '{safe_text}'.toLowerCase().trim();
                    const idx = {option_index};

                    // Strategy 1: Find button by EXACT text match anywhere on page
                    // This is the most reliable — avoids index ordering ambiguity
                    if (targetText) {{
                        const allButtons = Array.from(document.querySelectorAll('button'));
                        const match = allButtons.find(b => b.textContent.trim().toLowerCase() === targetText);
                        if (match) {{
                            match.click();
                            return {{ok: true, clicked: match.textContent.trim(), via: 'text-match'}};
                        }}
                        // Try partial match (e.g. "approve once" contains "once")
                        const partial = allButtons.find(b => b.textContent.trim().toLowerCase().includes(targetText));
                        if (partial) {{
                            partial.click();
                            return {{ok: true, clicked: partial.textContent.trim(), via: 'partial-text-match'}};
                        }}
                    }}

                    // Strategy 2: Index within a confirmed dialog/alertdialog container only
                    const dialog = document.querySelector('[role="dialog"], [role="alertdialog"]');
                    if (dialog) {{
                        const btns = Array.from(dialog.querySelectorAll('button'));
                        if (btns.length > idx) {{
                            btns[idx].click();
                            return {{ok: true, clicked: btns[idx].textContent.trim(), via: 'dialog-index'}};
                        }}
                    }}

                    // Strategy 3: Permission keywords in a floating panel, clicked by index
                    const permKeywords = ['allow', 'deny', 'reject', 'once'];
                    const allButtons = Array.from(document.querySelectorAll('button'));
                    const approvalButtons = allButtons.filter(b => {{
                        const t = b.textContent.trim().toLowerCase();
                        return permKeywords.some(k => t === k || t.startsWith('allow') || t.startsWith('deny'));
                    }});
                    if (approvalButtons.length > idx) {{
                        approvalButtons[idx].click();
                        return {{ok: true, clicked: approvalButtons[idx].textContent.trim(), via: 'keyword-index', found: approvalButtons.map(b => b.textContent.trim())}};
                    }}

                    return {{error: 'No approval dialog found', targetText, idx}};
                }})()
                """)

                if isinstance(result, dict) and result.get("ok"):
                    print(f"[CDP] Approval clicked: '{result.get('clicked')}' via {result.get('via')} (wanted: '{option_text}')", flush=True)
                    return True
                else:
                    print(f"[CDP] Approval failed: {result}", file=sys.stderr, flush=True)
                    return False

        except Exception as e:
            print(f"[CDP] Approve error: {e}", file=sys.stderr, flush=True)
            self._cdp_port = None
            return False


    async def inject_create_project(self, project_name: str, conversation_id: str | None = None) -> str | None:
        """Create a new project in Antigravity via its UI flow using CDP.
        
        Flow: D+ button → New Project → Skip folders → Type name → Create
        Returns the new section/conversation ID from the URL on success, None on failure.
        """
        ws_url = await self.get_page_ws(conversation_id)
        if not ws_url:
            print("[CDP] No page target available for create_project", file=sys.stderr, flush=True)
            return False

        try:
            async with self._cdp_session(ws_url, timeout=10.0) as (ws, send_cdp):
                async def eval_js(js):
                    res = await send_cdp("Runtime.evaluate", {"expression": js, "returnByValue": True})
                    return res.get("result", {}).get("result", {}).get("value")

                async def type_text(text):
                    """Type text char-by-char using rawKeyDown+char+keyUp to trigger React."""
                    for char in text:
                        await send_cdp("Input.dispatchKeyEvent", {
                            "type": "rawKeyDown",
                            "key": char,
                            "text": char,
                            "unmodifiedText": char,
                        })
                        await send_cdp("Input.dispatchKeyEvent", {
                            "type": "char",
                            "key": char,
                            "text": char,
                            "unmodifiedText": char,
                        })
                        await send_cdp("Input.dispatchKeyEvent", {
                            "type": "keyUp",
                            "key": char,
                        })
                        await asyncio.sleep(0.01)

                # Step 1: Close any open dialog
                await eval_js("""
                (() => {
                    const d = document.querySelector('[role="dialog"]');
                    if (d) { const c = d.querySelector('button[aria-label="Close"]'); if (c) c.click(); }
                })()
                """)
                await asyncio.sleep(0.5)

                # Step 2: Click D+ button (2nd button near "Projects" label)
                result = await eval_js("""
                (() => {
                    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                    while (walker.nextNode()) {
                        if (walker.currentNode.textContent.trim() === 'Projects') {
                            const container = walker.currentNode.parentElement.closest('div');
                            if (container) {
                                const buttons = container.querySelectorAll('button');
                                if (buttons.length >= 2) { buttons[1].click(); return true; }
                            }
                        }
                    }
                    return false;
                })()
                """)
                if not result:
                    print("[CDP] Could not find D+ button near Projects", file=sys.stderr, flush=True)
                    return False
                await asyncio.sleep(1.0)

                # Step 3: Click "New Project" in the dropdown
                result = await eval_js("""
                (() => {
                    const d = document.querySelector('[role="dialog"]');
                    if (!d) return false;
                    for (const btn of d.querySelectorAll('button')) {
                        if (btn.textContent.trim().includes('New Project')) { btn.click(); return true; }
                    }
                    return false;
                })()
                """)
                if not result:
                    print("[CDP] Could not find 'New Project' option", file=sys.stderr, flush=True)
                    return False
                await asyncio.sleep(1.0)

                # Step 4: Click "Skip" (folder selection step)
                result = await eval_js("""
                (() => {
                    const d = document.querySelector('[role="dialog"]');
                    if (!d) return false;
                    for (const btn of d.querySelectorAll('button')) {
                        if (btn.textContent.trim() === 'Skip') { btn.click(); return true; }
                    }
                    return false;
                })()
                """)
                if not result:
                    print("[CDP] Could not find 'Skip' button", file=sys.stderr, flush=True)
                    return False
                await asyncio.sleep(1.0)

                # Step 5: Focus the project name input
                result = await eval_js("""
                (() => {
                    const d = document.querySelector('[role="dialog"]');
                    if (!d) return false;
                    const input = d.querySelector('input[placeholder="Enter project name..."]');
                    if (!input) return false;
                    input.focus();
                    return true;
                })()
                """)
                if not result:
                    print("[CDP] Could not find project name input", file=sys.stderr, flush=True)
                    return False
                await asyncio.sleep(0.2)

                # Step 6: Type the project name
                await send_cdp("Input.insertText", {"text": project_name})
                await asyncio.sleep(0.3)

                # Step 7: Click "Create"
                result = await eval_js("""
                (() => {
                    const d = document.querySelector('[role="dialog"]');
                    if (!d) return {error: 'no dialog'};
                    const btn = Array.from(d.querySelectorAll('button')).find(b => b.textContent.trim() === 'Create');
                    if (!btn) return {error: 'no Create button'};
                    if (btn.disabled) return {error: 'Create button disabled'};
                    btn.click();
                    return {ok: true};
                })()
                """)
                if isinstance(result, dict) and result.get("ok"):
                    print(f"[CDP] Project '{project_name}' created successfully", flush=True)
                    
                    # Wait for Antigravity to navigate to the new project
                    await send_cdp("Page.enable")
                    start_time = asyncio.get_event_loop().time()
                    while asyncio.get_event_loop().time() - start_time < 5.0:
                        try:
                            evt_str = await asyncio.wait_for(ws.recv(), timeout=1.0)
                            evt = json.loads(evt_str)
                            if evt.get("method") == "Page.loadEventFired":
                                break
                        except asyncio.TimeoutError:
                            continue
                    
                    # Extract the section/conversation ID from the new URL
                    url = await eval_js("window.location.href")
                    section_id = None
                    if url and "section=" in url:
                        import re as _re
                        m = _re.search(r'section=([a-f0-9-]+)', url)
                        if m:
                            section_id = m.group(1)
                            print(f"[CDP] New project section ID: {section_id}", flush=True)
                    
                    return section_id or "created"
                else:
                    print(f"[CDP] Create project failed: {result}", file=sys.stderr, flush=True)
                    return None

        except Exception as e:
            print(f"[CDP] Create project error: {e}", file=sys.stderr, flush=True)
            self._cdp_port = None
            return None

    async def inject_model_change(self, model_name: str, conversation_id: str | None = None) -> bool:
        """Change the selected model in Rover via CDP."""
        ws_url = await self.get_page_ws(conversation_id)
        if not ws_url:
            print("[CDP] No page target available for model change", file=sys.stderr, flush=True)
            return False
        
        try:
            async with self._cdp_session(ws_url) as (ws, send_cdp):
                # Open model selector dropdown
                js1 = """
                (() => {
                    const btn = document.querySelector('button[aria-label^="Select model"]');
                    if (btn) btn.click();
                })()
                """
                await send_cdp("Runtime.evaluate", {"expression": js1})
                await asyncio.sleep(0.3) # Wait for popup to render
                
                # Click the target model
                js2 = f"""
                (() => {{
                    const options = Array.from(document.querySelectorAll('[role="dialog"] button, [role="listbox"] button, [role="menuitem"], [role="option"]'));
                    const target = options.find(el => el.textContent.includes("{model_name}"));
                    if (target) {{
                        target.click();
                        return true;
                    }}
                    return false;
                }})()
                """
                res = await send_cdp("Runtime.evaluate", {"expression": js2, "returnByValue": True})
                return res.get("result", {}).get("result", {}).get("value", False)
        except Exception as e:
            print(f"[CDP] Error setting model: {e}", file=sys.stderr, flush=True)
            return False

    async def inject_project_settings(self, project_id: str, is_turbo: bool, folder_uri: str, project_name: str) -> bool:
        """Update project settings via headless gRPC-Web injection."""
        ws_url = await self.get_page_ws()
        if not ws_url:
            print("[CDP] No page target available for project update", file=sys.stderr, flush=True)
            return False
            
        try:
            async with websockets.connect(ws_url, max_size=50*1024*1024, close_timeout=5) as ws:
                msg_id = 1
                async def send_cdp(method, params=None):
                    nonlocal msg_id
                    payload = {"id": msg_id, "method": method, "params": params or {}}
                    msg_id += 1
                    await ws.send(json.dumps(payload))
                    while True:
                        resp = json.loads(await ws.recv())
                        if resp.get("id") == payload["id"]:
                            return resp
                
                # We need to construct the JavaScript code to inject
                # Using f-strings to inject our parameters into the JS snippet
                file_policy = "AGENT_SETTING_POLICY_ALLOW" if is_turbo else "AGENT_SETTING_POLICY_ASK"
                exec_policy = "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER" if is_turbo else "CASCADE_COMMANDS_AUTO_EXECUTION_OFF"
                
                js = f'''
                (async () => {{
                    const projectId = "{project_id}";
                    const port = window.location.port || "54183";
                    const targetUrl = `https://127.0.0.1:${{port}}/exa.language_server_pb.LanguageServerService/UpdateProject`;
                    
                    let csrfToken = localStorage.getItem("codeium-csrf-token") || "";
                    
                    const payloadJson = JSON.stringify({{
                        "project": {{
                            "id": projectId,
                            "name": "{project_name}",
                            "projectResources": {{
                                "resources": [
                                    {{
                                        "gitFolder": {{
                                            "folderUri": "{folder_uri}",
                                            "defaultBranch": "master"
                                        }}
                                    }}
                                ]
                            }},
                            "settings": {{
                                "fileAccessPolicy": "{file_policy}",
                                "internetPolicy": "AGENT_SETTING_POLICY_ASK",
                                "sandboxMode": false,
                                "autoExecutionPolicy": "{exec_policy}",
                                "artifactReviewMode": "ARTIFACT_REVIEW_MODE_ALWAYS"
                            }},
                            "updatedAt": new Date().toISOString()
                        }}
                    }});
                    
                    const encoder = new TextEncoder();
                    const payloadBytes = encoder.encode(payloadJson);
                    const length = payloadBytes.length;
                    const body = new Uint8Array(5 + length);
                    
                    body[0] = 0;
                    body[1] = (length >> 24) & 0xFF;
                    body[2] = (length >> 16) & 0xFF;
                    body[3] = (length >> 8) & 0xFF;
                    body[4] = length & 0xFF;
                    body.set(payloadBytes, 5);
                    
                    const response = await fetch(targetUrl, {{
                        method: "POST",
                        headers: {{
                            "content-type": "application/grpc-web+json",
                            "x-grpc-web": "1",
                            "x-codeium-csrf-token": csrfToken
                        }},
                        body: body
                    }});
                    
                    return {{ status: response.status, ok: response.ok }};
                }})()
                '''
                
                res = await send_cdp("Runtime.evaluate", {"expression": js, "awaitPromise": True, "returnByValue": True})
                print("DEBUG RES:", res, flush=True)
                result = res.get("result", {}).get("result", {}).get("value", {})
                if result.get("ok"):
                    return True
                else:
                    print(f"[CDP] Update failed: {result}", file=sys.stderr, flush=True)
                    return False
        except Exception as e:
            print(f"[CDP] Error updating project: {e}", file=sys.stderr, flush=True)
            return False

    async def monitor_queue(self, callback, conversation_id: str | None = None) -> None:
        """Continuously monitor the desktop DOM for queue changes and push to callback."""
        ws_url = await self.get_page_ws(conversation_id)
        if not ws_url:
            return

        try:
            async with websockets.connect(ws_url, max_size=50*1024*1024) as ws:
                msg_id = 1
                async def send_cdp(method, params=None):
                    nonlocal msg_id
                    payload = {"id": msg_id, "method": method, "params": params or {}}
                    msg_id += 1
                    await ws.send(json.dumps(payload))

                # Enable Runtime events to receive bindings
                await send_cdp("Runtime.enable")
                # Add binding
                await send_cdp("Runtime.addBinding", {"name": "roverQueueCallback"})

                await asyncio.sleep(0.2) # Give CDP a moment to register the binding

                # Inject MutationObserver
                observer_js = """
                (() => {
                    const init = () => {
                        if (!window.roverQueueCallback) {
                            setTimeout(init, 50);
                            return;
                        }
                        
                        if (window._roverQueueTimer) {
                            clearInterval(window._roverQueueTimer);
                        }
                        
                        const readQueue = () => {
                            const qCard = document.querySelector('[data-testid="queued-messages-card"]');
                            if (!qCard) return [];
                            const rows = Array.from(qCard.querySelectorAll('.flex.flex-row.items-center.justify-between.gap-3.w-full'));
                            return rows.map(row => {
                                const textSpan = row.querySelector('span.line-clamp-2');
                                return textSpan ? textSpan.innerText : '';
                            });
                        };

                        const readTasks = () => {
                            const tasks = [];
                            document.querySelectorAll('[data-tooltip-id^="label-task-"]').forEach(el => {
                                const id = el.getAttribute('data-tooltip-id').replace('label-task-', '');
                                const textSpan = el.querySelector('span.font-mono');
                                const name = textSpan ? textSpan.textContent : 'Unknown Task';
                                tasks.push({ id, name });
                            });
                            return tasks;
                        };

                        let lastJson = "";
                        const checkState = () => {
                            const msgs = readQueue();
                            const tasks = readTasks();
                            const currentJson = JSON.stringify({ messages: msgs, tasks: tasks });
                            if (currentJson !== lastJson) {
                                lastJson = currentJson;
                                window.roverQueueCallback(currentJson);
                            }
                        };

                        window._roverQueueTimer = setInterval(() => {
                            checkState();
                        }, 1000);
                        
                        // Initial check
                        checkState();
                    };
                    init();
                })();
                """
                await send_cdp("Runtime.evaluate", {"expression": observer_js})

                while True:
                    resp_str = await ws.recv()
                    resp = json.loads(resp_str)
                    if resp.get("method") == "Runtime.bindingCalled" and resp.get("params", {}).get("name") == "roverQueueCallback":
                        payload = resp["params"]["payload"]
                        try:
                            state_data = json.loads(payload)
                            if asyncio.iscoroutinefunction(callback):
                                await callback(state_data)
                            else:
                                callback(state_data)
                        except Exception as e:
                            print(f"[CDP] Error parsing queue callback payload: {e}", file=sys.stderr)
        except asyncio.CancelledError:
            pass # Task was cancelled, exit cleanly
        except Exception as e:
            print(f"[CDP] monitor_queue error: {e}", file=sys.stderr)
            self._cdp_port = None

    async def manage_queued_message(self, index: int, action: str, conversation_id: str | None = None) -> bool:
        """Trigger an action ('Send Now', 'Edit', 'Delete') on the queued message at the given index."""
        ws_url = await self.get_page_ws(conversation_id)
        if not ws_url:
            return False

        try:
            async with self._cdp_session(ws_url) as (ws, send_cdp):
                res = await send_cdp("Runtime.evaluate", {
                    "expression": f"""
                    (() => {{
                        const qCard = document.querySelector('[data-testid="queued-messages-card"]');
                        if (!qCard) return false;
                        const rows = Array.from(qCard.querySelectorAll('.flex.flex-row.items-center.justify-between.gap-3.w-full'));
                        if ({index} < 0 || {index} >= rows.length) return false;
                        const row = rows[{index}];
                        const btn = row.querySelector(`button[aria-label="{action}"]`) || Array.from(row.querySelectorAll('button')).find(b => (b.getAttribute('aria-label') || '').toLowerCase().includes('{action}'.toLowerCase()) || (b.textContent || '').toLowerCase().includes('{action}'.toLowerCase()));
                        if (!btn) return false;
                        btn.click();
                        return true;
                    }})();
                    """,
                    "returnByValue": True
                })
                val = res.get('result', {}).get('result', {}).get('value', False)
                return bool(val)
        except Exception as e:
            print(f"[CDP] manage_queued_message error: {e}", file=sys.stderr)
            self._cdp_port = None
            return False
