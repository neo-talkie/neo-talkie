#!/usr/bin/env bash
set -euo pipefail
sdkmanager 'platforms;android-28' 'build-tools;35.0.0' 'platform-tools'
python3 android/scripts/setup.py
python3 android/scripts/build.py
python3 android/scripts/test.py
