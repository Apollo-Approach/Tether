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

class RoverCDPClient:
    """Encapsulates CDP logic for communicating with the Rover app."""
    
    def __init__(self):
        self._cdp_port = None

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
            return None

    async def inject_chat(self, message_text: str, conversation_id: str | None = None) -> bool:
        """Inject a chat message into Rover's chat input via CDP.
        Navigates to the correct conversation if needed, detects subagent views,
        then uses Input.insertText + Enter keypress to submit."""
        ws_url = await self.get_page_ws(conversation_id)
        if not ws_url:
            print("[CDP] No page target available", file=sys.stderr, flush=True)
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
                            if "error" in resp:
                                print(f"[CDP] Error: {resp['error']}", file=sys.stderr)
                            return resp
    
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
                    await send_cdp("Page.navigate", {"url": target_url})
                    await asyncio.sleep(2.0)
    
                async def is_subagent_view():
                    r = await send_cdp("Runtime.evaluate", {
                        "expression": "document.body.innerText.includes('Cannot send message to subagent')",
                        "returnByValue": True
                    })
                    return r.get("result", {}).get("result", {}).get("value", False)
    
                # Step 1: Navigate to the correct conversation if needed
                if conversation_id:
                    current_url = await get_current_url()
                    if conversation_id not in current_url:
                        base_match = re.match(r'(https?://[^/]+)', current_url)
                        if base_match:
                            await navigate_to_conversation(base_match.group(1))
    
                        # Step 2: Check if navigation landed on a subagent view
                        # (only after navigating — the banner text can appear in the
                        #  sidebar even on the main view, causing false positives)
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
    
                await asyncio.sleep(0.1)
    
                # Step 4: Insert the message text
                await send_cdp("Input.insertText", {"text": message_text})
    
                await asyncio.sleep(0.2)
    
                # Step 5: Press Enter to submit
                await send_cdp("Input.dispatchKeyEvent", {
                    "type": "rawKeyDown",
                    "key": "Enter",
                    "code": "Enter",
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
    
                return True
        except Exception as e:
            print(f"[CDP] Injection error: {e}", file=sys.stderr, flush=True)
            # Port may have changed
            self._cdp_port = None
            return False

    async def inject_model_change(self, model_name: str, conversation_id: str | None = None) -> bool:
        """Change the selected model in Rover via CDP."""
        ws_url = await self.get_page_ws(conversation_id)
        if not ws_url:
            print("[CDP] No page target available for model change", file=sys.stderr, flush=True)
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
