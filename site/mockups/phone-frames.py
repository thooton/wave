#!/usr/bin/env python3
"""Shoot every mockup frame on the phone (8516067e) from a debug build of Wave:
phone-frames.py OUTDIR [sequence-index...]; writes OUTDIR/<frame-id>.png, the
names of shots/. Install app-debug.apk first and hold the phone lock.
Steps: launch | KEY (UP/DOWN/LEFT/RIGHT mean the d-pad; x-* shots have no mockup) | KEY*N | text:abc | wait:ms | shot:frame-id."""
import os, subprocess, sys, time
S = ['adb', '-s', '8516067e']
out = sys.argv[1]
os.makedirs(out, exist_ok=True)

SEQ = [
    ('browse', 'launch shot:wave-home DOWN*6 shot:wave-home-more'),
    ('browse', 'launch DOWN*2 ENTER shot:wave-about BACK RIGHT shot:sources UP shot:sources-bar DOWN DOWN MENU shot:sources-menu BACK'
               ' UP UP LEFT ENTER wait:300 shot:sources-refreshing wait:2500'),
    ('browse', 'launch RIGHT DOWN ENTER wait:300 shot:source ENTER DOWN*2 shot:section MENU shot:section-menu BACK'
               ' ENTER shot:details-get DOWN*3 shot:details-get-more MENU shot:details-menu BACK BACK'
               ' UP ENTER shot:details-installed BACK DOWN*2 ENTER shot:details-update BACK'),
    ('browse', 'launch RIGHT DOWN*2 ENTER wait:300 DOWN ENTER DOWN ENTER shot:details-unfit'),
    ('browse', 'launch RIGHT DOWN*3 ENTER wait:300 DOWN ENTER ENTER shot:details-newer'),
    ('browse', 'launch RIGHT*2 shot:installed UP shot:installed-updateall DOWN MENU shot:installed-menu BACK DOWN*5 shot:installed-more'),
    ('browse', 'launch LEFT shot:search-empty text:mu shot:search-typing text:s DOWN shot:search-results UP BACK*3 text:xyzzy shot:search-none'),
    ('browse', 'launch RIGHT UP ENTER shot:add-source text:mkline/games DOWN shot:add-source-button ENTER wait:200 shot:add-source-fetching'
               ' wait:1800 shot:add-source-trust BACK UP BACK*5 text:nope ENTER wait:2000 shot:add-source-bad'),
    ('browse', 'launch ENTER shot:details-module ENTER wait:400 shot:queue-confirm ENTER wait:4500 shot:queue-progress wait:11000 shot:queue-reboot'),
    ('browse', 'launch RIGHT DOWN ENTER ENTER DOWN*2 ENTER ENTER wait:400 ENTER wait:6000 shot:queue-done'),
    ('browse', 'launch DOWN ENTER ENTER wait:400 ENTER wait:5000 shot:error-hash'),
    ('errors', 'launch shot:error-watchdog ENTER RIGHT DOWN*3 shot:error-source UP UP LEFT ENTER wait:2150 shot:error-network-toast'
               ' RIGHT*2 DOWN*8 shot:error-watchdog-row'),
    ('offline', 'launch RIGHT shot:error-network'),
    ('reboot', 'launch shot:wave-home-reboot RIGHT*2 shot:installed-reboot'),
    ('uptodate', 'launch RIGHT*2 shot:installed-none'),
    ('noroot', 'launch DOWN*4 ENTER shot:no-root'),
    ('browse', 'launch LEFT text:launcher DOWN MENU shot:x-remove-menu DOWN*2 ENTER wait:600 shot:x-confirm-remove'),
    ('reboot', 'launch ENTER shot:x-reboot-dialog'),
]

def sh(*a):
    subprocess.run(S + ['shell', *a], capture_output=True)

def shot(name):
    png = subprocess.run(S + ['exec-out', 'screencap', '-p'], capture_output=True).stdout
    open(os.path.join(out, name + '.png'), 'wb').write(png)
    print('shot', name, flush=True)

only = [int(x) for x in sys.argv[2:]]
for i, (fixture, steps) in enumerate(SEQ):
    if only and i not in only:
        continue
    for step in steps.split():
        if step == 'launch':
            sh('am', 'force-stop', 'com.wave.xp3')
            sh('input', 'keyevent', 'KEYCODE_WAKEUP')
            sh('wm', 'dismiss-keyguard')
            sh('am', 'start', '-n', 'com.wave.xp3/.app.WaveActivity', '--es', 'fixture', fixture)
            time.sleep(2.5)
        elif step.startswith('shot:'):
            shot(step[5:])
        elif step.startswith('wait:'):
            time.sleep(int(step[5:]) / 1000)
        elif step.startswith('text:'):
            sh('input', 'text', step[5:])
            time.sleep(0.8)
        else:
            key, _, n = step.partition('*')
            if key in ('UP', 'DOWN', 'LEFT', 'RIGHT'):
                key = 'DPAD_' + key
            for _ in range(int(n or 1)):
                sh('input', 'keyevent', 'KEYCODE_' + key)
                time.sleep(0.5)
            time.sleep(0.45)
