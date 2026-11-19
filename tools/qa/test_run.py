import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
from xml.etree import ElementTree as ET
import zlib

import run
from pixels import HEIGHT, WIDTH, PNG_MAGIC, decode, difference, paeth


def png_chunk(kind, payload):
    return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload))


class Pixels(unittest.TestCase):
    def test_all_png_filters_and_rgb_rgba(self):
        for channels in (3, 4):
            previous = [0] * WIDTH * channels
            filtered = bytearray()
            expected = bytearray()
            for y in range(HEIGHT):
                mode = y % 5
                filtered.append(mode)
                row = [(x + y * 3) % 256 for x in range(WIDTH * channels)]
                expected.extend(value for i, value in enumerate(row) if channels == 3 or i % 4 != 3)
                for x, value in enumerate(row):
                    left = row[x - channels] if x >= channels else 0
                    above = previous[x]
                    above_left = previous[x - channels] if x >= channels else 0
                    predict = (0, left, above, (left + above) // 2, paeth(left, above, above_left))[mode]
                    filtered.append((value - predict) % 256)
                previous = row
            header = struct.pack('>IIBBBBB', WIDTH, HEIGHT, 8, 2 if channels == 3 else 6, 0, 0, 0)
            png = (PNG_MAGIC + png_chunk(b'IHDR', header) + png_chunk(b'IDAT', zlib.compress(filtered))
                   + png_chunk(b'IEND', b''))
            self.assertEqual(decode(png), expected)
            with self.assertRaises(ValueError):
                decode(png[:-1])

    def test_visual_check_ignores_status_bar_and_tiny_deltas(self):
        a = bytes(WIDTH * HEIGHT * 3)
        b = bytearray(a)
        b[0] = 255                  # top bar, outside the crop
        b[WIDTH * 30 * 3] = 7      # nearly identical
        self.assertEqual(difference(a, b, run.REGION), 0)
        b[WIDTH * 30 * 3 + 1] = 8  # one changed RGB channel
        self.assertEqual(difference(a, b, (0, 30, 2, 1)), .5)


class Health(unittest.TestCase):
    def test_marker_scopes_and_attributes_failures(self):
        logs = '''E/ActivityManager( 111): ANR in com.wave.xp3
I/WaveQa  ( 888): token
E/AndroidRuntime( 222): FATAL EXCEPTION: main
E/AndroidRuntime( 222): Process: com.wave.xp3.other, PID: 222
E/AndroidRuntime( 333): FATAL EXCEPTION: main
E/AndroidRuntime( 333): Process: com.wave.xp3:test, PID: 333
E/AndroidRuntime( 444): Process: com.wave.xp3, PID: 444
I/am_anr  ( 111): [0,555,com.wave.xp3,1,Input dispatching timed out]
I/am_crash( 111): [0,666,com.wave.xp3,1,Native crash]
'''
        self.assertEqual(run.health_events(logs, 'token'), [
            'ANR in Wave', 'Android reported a Wave crash (PID 666)', 'FATAL EXCEPTION in Wave (PID 333)'])
        with self.assertRaises(run.HarnessError):
            run.health_events(logs, 'not uncleared logs')

    def test_popup_window_owner_not_focused_app(self):
        windows = '''  Window #1 Window{99ad u0 PopupWindow:other}:
    mOwnerUid=1000 package=android appop=NONE
  Window #2 Window{1df u0 PopupWindow:c44}:
    mOwnerUid=1024 package=com.wave.xp3 appop=NONE
  mCurrentFocus=Window{1df u0 PopupWindow:c44}
  mFocusedApp=AppWindowToken{f99 com.wave.xp3/.MainActivity}
'''
        self.assertTrue(run.focused_on_wave(windows))
        self.assertFalse(run.focused_on_wave(windows.replace('CurrentFocus=Window{1df', 'CurrentFocus=Window{99ad')))
        self.assertTrue(run.focused_on_wave('mCurrentFocus=Window{a1 u0 com.wave.xp3/.MainActivity}'))
        self.assertFalse(run.focused_on_wave('mCurrentFocus=null'))

    def test_labels_match_only_wave_nodes(self):
        tree = ET.fromstring('''<hierarchy>
          <node package="com.other" text="Confirm" focused="true"/>
          <node package="com.wave.xp3" text="Cannot Check   for Updates" selected="true" bounds="a"/>
          <node package="com.wave.xp3" content-desc="Check Again" />
        </hierarchy>''')
        self.assertTrue(run.text_matches(tree, ['cannot check for updates', 'Again']))
        self.assertFalse(run.text_matches(tree, ['Confirm']))
        self.assertEqual(len(run.focus(tree)), 1)


class Safety(unittest.TestCase):
    def test_lock_remains_owned_by_the_other_user_and_releases_on_error(self):
        with tempfile.TemporaryDirectory() as temp, patch('run.LOCK', Path(temp) / 'phone.lock'):
            with self.assertRaisesRegex(ValueError, 'failure'):
                with run.phone_lock():
                    with self.assertRaises(run.HarnessError), run.phone_lock():
                        self.fail('Should not acquire the lock twice')
                    self.assertTrue((run.LOCK / 'owner').is_file())
                    raise ValueError('failure')
            self.assertFalse(run.LOCK.exists())

    def test_scenario_examples_are_valid_and_enter_is_guarded(self):
        for path in (run.HERE / 'scenarios').glob('*.json'):
            run.read_scenario(path)
        with tempfile.TemporaryDirectory() as temp:
            bad = Path(temp) / 'bad.json'
            for step in ({'name': 'Unsafe', 'key': 'WAKEUP'}, {'name': 'Unsafe', 'key': 'ENTER'},
                         {'name': 'Short name', 'key': 'DOWN'}, {'name': 'Android name', 'key': 'KEYCODE_DPAD_DOWN'},
                         {'name': 'Missing key'}):
                bad.write_text(json.dumps({'name': 'bad', 'steps': [
                    {'name': 'Launch', 'action': 'launch'}, step]}), encoding='utf-8')
                with self.assertRaises(run.HarnessError):
                    run.read_scenario(bad)

    def test_unknown_key_fails_loudly_with_accepted_names(self):
        with tempfile.TemporaryDirectory() as temp:
            bad = Path(temp) / 'bad.json'
            bad.write_text(json.dumps({'name': 'bad', 'steps': [
                {'name': 'Launch', 'action': 'launch'}, {'name': 'Typo', 'key': 'KEYCODE_DOWN'}]}), encoding='utf-8')
            with self.assertRaises(run.HarnessError) as caught:
                run.read_scenario(bad)
            message = str(caught.exception)
            self.assertIn("'KEYCODE_DOWN'", message)
            for key in run.KEYS:
                self.assertIn(key, message)


if __name__ == '__main__':
    unittest.main()
