import asyncio
import sys
import os
from unittest.mock import patch

# Add project root to sys.path
project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, project_root)

from tests.test_cases import TestTier1FeatureCoverage

async def test_zombie_on_connect_failure():
    test_instance = TestTier1FeatureCoverage()
    
    # We want to patch websockets.connect to simulate a connection failure
    async def mock_connect(*args, **kwargs):
        raise ConnectionRefusedError("Simulated connection failure")
        
    with patch('websockets.connect', side_effect=mock_connect):
        try:
            print("Running asyncSetUp with mocked connection failure...")
            await test_instance.asyncSetUp()
            print("ERROR: asyncSetUp succeeded but was expected to fail!")
            return False
        except ConnectionRefusedError:
            print("Caught simulated connection failure as expected.")
            
    # Now check if the process was terminated
    process = test_instance.process
    await asyncio.sleep(0.5) # Give it a brief moment to clean up
    poll_val = process.returncode
    
    if poll_val is not None:
        print(f"SUCCESS: Process was terminated successfully with returncode {poll_val}.")
        return True
    else:
        print("FAILURE: Process is still running (zombie process left behind)!")
        # Kill it to avoid leaking
        process.terminate()
        await process.wait()
        return False

async def test_zombie_on_startup_timeout():
    test_instance = TestTier1FeatureCoverage()
    
    # We want to simulate a timeout reading the startup log line
    # Let's patch asyncio.wait_for to raise TimeoutError immediately when it's called with timeout=5.0
    original_wait_for = asyncio.wait_for
    
    async def mock_wait_for(fut, timeout):
        if timeout == 15.0: # The startup timeout in asyncSetUp
            raise asyncio.TimeoutError("Simulated startup timeout")
        return await original_wait_for(fut, timeout)
        
    with patch('asyncio.wait_for', side_effect=mock_wait_for):
        try:
            print("Running asyncSetUp with mocked startup timeout...")
            await test_instance.asyncSetUp()
            print("ERROR: asyncSetUp succeeded but was expected to fail!")
            return False
        except RuntimeError as e:
            print(f"Caught expected exception: {e}")
            
    # Now check if the process was terminated
    process = test_instance.process
    await asyncio.sleep(0.5)
    poll_val = process.returncode
    
    if poll_val is not None:
        print(f"SUCCESS: Process was terminated successfully with returncode {poll_val}.")
        return True
    else:
        print("FAILURE: Process is still running (zombie process left behind)!")
        process.terminate()
        await process.wait()
        return False

async def main():
    connect_success = await test_zombie_on_connect_failure()
    timeout_success = await test_zombie_on_startup_timeout()
    if connect_success and timeout_success:
        print("ALL ZOMBIE TESTS PASSED.")
        sys.exit(0)
    else:
        print("SOME ZOMBIE TESTS FAILED.")
        sys.exit(1)

if __name__ == '__main__':
    asyncio.run(main())
