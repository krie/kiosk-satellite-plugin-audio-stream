#!/usr/bin/env python3
"""Compile the plugin against Android SDK 35 and run JVM-safe unit tests."""
import os
import sys
from pathlib import Path
import subprocess
import tempfile

from android_sdk import android_platform

ROOT = Path(__file__).resolve().parents[1]
SDK_ROOT = Path(
    os.environ.get(
        'ANDROID_HOME',
        os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk')),
    )
)
JAVA_HOME = os.environ.get('JAVA_HOME')


def java_tool(name):
    return str(Path(JAVA_HOME) / 'bin' / name) if JAVA_HOME else name


try:
    platform = android_platform(SDK_ROOT, '35')
except ValueError as error:
    raise SystemExit(str(error)) from error

sources = [
    *sorted((ROOT / 'sdk/src').rglob('*.java')),
    *sorted((ROOT / 'src').rglob('*.java')),
    *sorted((ROOT / 'tests').rglob('*.java')),
]

with tempfile.TemporaryDirectory(prefix='kiosk-audio-stream-test-') as directory:
    subprocess.run(
        [
            java_tool('javac'),
            '--release',
            '8',
            '-cp',
            str(platform),
            '-d',
            directory,
            *map(str, sources),
        ],
        check=True,
    )

    for test in [
        'io.github.krie.kiosk.plugins.audiostream.PcmGainTest',
        'io.github.krie.kiosk.plugins.audiostream.AdtsTest',
        'io.github.krie.kiosk.plugins.audiostream.PluginSettingsTest',
        'io.github.krie.kiosk.plugins.audiostream.AacHttpServerTest',
    ]:
        subprocess.run(
            [java_tool('java'), '-ea', '-cp', directory, test],
            check=True,
        )

subprocess.run([sys.executable, str(ROOT / 'tools/test_android_sdk.py')], check=True)
subprocess.run([sys.executable, str(ROOT / 'tools/test_plugin_manifest.py')], check=True)
