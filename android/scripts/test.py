#!/usr/bin/env python3
"""Run host-only protocol tests. Deliberately has no ADB or network operations."""
from pathlib import Path
from build import APP, JAVA, OUT, ROOT, run

dest = OUT / 'test-classes'
dest.mkdir(parents=True, exist_ok=True)
names = ['PttHandoff', 'PttLease', 'UiContract', 'Protocol', 'Sse', 'ConversationMemory', 'ServiceCheckResult', 'RecordingFeedback', 'TurnText', 'PlaybackVolume']
sources = []
for name in names:
    candidates = list((ROOT / 'android/common/src').rglob(name + '.java')) + list((APP / 'src').rglob(name + '.java'))
    assert len(candidates) == 1, name
    sources += candidates
sources += list((APP / 'tests').rglob('*.java'))
sources += [ROOT / ('android/launcher/src/com/neotalkie/device/'+name+'.java') for name in ['BleProbeProtocol','WifiProvisionSession','LanHandshake']]
sources += list((ROOT / 'android/launcher/tests').rglob('*.java'))
for name in ['PttSequence', 'AudioRoleSwitch']:
    sources.append(ROOT / 'android/audio-link/src/com/neotalkie/audiolink' / (name + '.java'))
sources += list((ROOT / 'android/audio-link/tests').rglob('*.java'))
run(JAVA / 'bin/javac', '-encoding', 'UTF-8', '-d', dest, *sources)
run(JAVA / 'bin/java', '-cp', dest, 'com.neotalkie.device.ProtocolTest')
run(JAVA / 'bin/java', '-cp', dest, 'com.neotalkie.device.ConversationMemoryTest')
run(JAVA / 'bin/java', '-cp', dest, 'com.neotalkie.device.ServiceCheckResultTest')
run(JAVA / 'bin/java', '-cp', dest, 'com.neotalkie.device.RecordingFeedbackTest')
run(JAVA / 'bin/java', '-cp', dest, 'com.neotalkie.device.TurnTextTest')

run(JAVA / 'bin/java', '-cp', dest, 'com.neotalkie.device.PlaybackVolumeTest')

run(JAVA / 'bin/java', '-cp', dest, 'com.neotalkie.device.PttHandoffTest')

run(JAVA / 'bin/java', '-cp', dest, 'com.neotalkie.device.PttLeaseTest')

run(JAVA / 'bin/java', '-cp', dest, 'com.neotalkie.device.BleProbeProtocolTest')

run(JAVA / 'bin/java', '-cp', dest, 'com.neotalkie.device.LanHandshakeTest')

run(JAVA / 'bin/java', '-cp', dest, 'com.neotalkie.audiolink.PttSequenceTest')
run(JAVA / 'bin/java', '-cp', dest, 'com.neotalkie.audiolink.AudioRoleSwitchTest')
