import unittest
import sys
import os

if sys.platform.startswith('win'):
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    if hasattr(sys.stderr, 'reconfigure'):
        sys.stderr.reconfigure(encoding='utf-8')

def main():
    # Ensure the root folder is in the python path
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if project_root not in sys.path:
        sys.path.insert(0, project_root)

    print("Discovering and running tests...")
    
    # Discover and run tests in the 'tests' directory
    loader = unittest.TestLoader()
    suite = loader.discover(start_dir=os.path.join(project_root, 'tests'), pattern='test_*.py')
    
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    
    # Exit with code 0 if successful, 1 otherwise
    sys.exit(0 if result.wasSuccessful() else 1)

if __name__ == "__main__":
    main()
