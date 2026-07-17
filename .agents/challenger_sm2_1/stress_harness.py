import subprocess
import sys
import os
import asyncio
import json
import time
import websockets

PROJECT_ROOT = r"c:\Development\Monolith"
RECEIVER_PATH = os.path.join(PROJECT_ROOT, "receiver", "receiver.py")
RUN_TESTS_PATH = os.path.join(PROJECT_ROOT, "tests", "run_tests.py")
STRESS_TESTS_MODULE = "tests.stress_tests"

async def run_loop_tests():
    print("--- Running tests/run_tests.py 10 times sequentially ---")
    failures = 0
    for i in range(10):
        start = time.time()
        res = subprocess.run([sys.executable, RUN_TESTS_PATH], cwd=PROJECT_ROOT, capture_output=True, text=True)
        dur = time.time() - start
        if res.returncode != 0:
            print(f"Run {i+1} FAILED (duration: {dur:.2f}s)")
            print("STDOUT:")
            print(res.stdout)
            print("STDERR:")
            print(res.stderr)
            failures += 1
        else:
            print(f"Run {i+1} PASSED (duration: {dur:.2f}s)")
    return failures

async def run_loop_stress():
    print("--- Running tests/stress_tests.py 10 times sequentially ---")
    failures = 0
    for i in range(10):
        start = time.time()
        res = subprocess.run([sys.executable, "-m", "unittest", STRESS_TESTS_MODULE], cwd=PROJECT_ROOT, capture_output=True, text=True)
        dur = time.time() - start
        if res.returncode != 0:
            print(f"Stress Run {i+1} FAILED (duration: {dur:.2f}s)")
            print("STDOUT:")
            print(res.stdout)
            print("STDERR:")
            print(res.stderr)
            failures += 1
        else:
            print(f"Stress Run {i+1} PASSED (duration: {dur:.2f}s)")
    return failures

async def flood_receiver():
    print("--- Flooding Receiver with concurrent clients ---")
    # Spawn receiver on port 9090
    proc = await asyncio.create_subprocess_exec(
        sys.executable, "-u", RECEIVER_PATH, "--mock", "--port", "9090",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        cwd=PROJECT_ROOT
    )
    
    # Wait for startup
    try:
        line = await asyncio.wait_for(proc.stdout.readline(), timeout=3.0)
        print("Receiver started:", line.decode().strip())
    except Exception as e:
        print("Failed to start receiver:", e)
        proc.terminate()
        return
        
    async def client_flood(cid):
        try:
            async with websockets.connect("ws://localhost:9090") as ws:
                for msg_id in range(100):
                    payload = {"event": "mouse_move", "dx": float(cid), "dy": float(msg_id)}
                    await ws.send(json.dumps(payload))
                    # Optional very short sleep
                    await asyncio.sleep(0.001)
        except Exception as e:
            return f"Client {cid} error: {e}"
        return None

    # Connect 100 clients concurrently and send 100 messages each
    print("Spawning 100 concurrent clients...")
    start_time = time.time()
    tasks = [client_flood(i) for i in range(100)]
    results = await asyncio.gather(*tasks)
    dur = time.time() - start_time
    
    errors = [r for r in results if r is not None]
    print(f"Flood completed in {dur:.2f}s. Client errors: {len(errors)}")
    if errors:
        for err in errors[:5]:
            print("  ", err)

    # Let the server process everything
    await asyncio.sleep(1.0)
    
    # Gracefully terminate
    proc.terminate()
    stdout_data, stderr_data = await proc.communicate()
    
    stdout_lines = stdout_data.decode().splitlines()
    stderr_lines = stderr_data.decode().splitlines()
    print(f"Total processed events logged on stdout: {len(stdout_lines) - 1}") # minus server listening line
    print(f"Total error lines logged on stderr: {len(stderr_lines)}")
    
    if len(stderr_lines) > 0:
        print("Sample stderr logs:")
        for line in stderr_lines[:5]:
            print("  ", line)

async def main():
    test_fails = await run_loop_tests()
    stress_fails = await run_loop_stress()
    await flood_receiver()
    
    print("--- Done ---")
    print(f"Summary: test_cases loop failures = {test_fails}, stress_tests loop failures = {stress_fails}")

if __name__ == "__main__":
    asyncio.run(main())
