"""Put the repository root on sys.path so `import nisos` works without installing.

This project is meant to be cloned into Termux and run in place -- no pip
install, no virtualenv, no build step. The tests should work the same way.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
