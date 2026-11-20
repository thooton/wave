#!/usr/bin/env python3
"""Host checks of phone.py's parsers against dumpsys output (no phone): python3 tools/qa/stress/test_phone.py"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import phone  # noqa: E402

MEMINFO = """Applications Memory Usage (in Kilobytes):
Uptime: 1 Realtime: 1

** MEMINFO in pid 4242 [com.wave.xp3] **
                   Pss  Private  Private  SwapPss     Heap     Heap     Heap
                 Total    Dirty    Clean    Dirty     Size    Alloc     Free
                ------   ------   ------   ------   ------   ------   ------
  Native Heap    11820    11748        0        0    20480    13112     7367
  Dalvik Heap     9210     9160        0        0    21880    16310     5570
        TOTAL    58841    40200     9052        0    42360    29422    12937

 App Summary
                       Pss(KB)
                        ------
           Java Heap:    10168
         Native Heap:    11748
                Code:     9052
               TOTAL:    58841
"""
FRAMES = """Profile data in ms:
---PROFILEDATA---
Flags,IntendedVsync,Vsync,OldestInputEvent,NewestInputEvent,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,
0,1000000000,1000000000,9223372036854775807,0,1001000000,1002000000,1003000000,1004000000,1005000000,1006000000,1007000000,1008000000,1020000000,
0,2000000000,2000000000,1990000000,1990000000,2250000000,2251000000,2252000000,2253000000,2254000000,2255000000,2256000000,2257000000,2300000000,
---PROFILEDATA---
"""
BATTERY = "Current Battery Service state:\n  AC powered: false\n  USB powered: true\n  status: 2\n  health: 2\n  present: true\n  level: 57\n  scale: 100\n  temperature: 311\n"


class Parsers(unittest.TestCase):
    def test_meminfo(self):
        m = phone.parse_meminfo(MEMINFO)
        self.assertEqual((m['dalvik_alloc'], m['native_alloc'], m['java_pss'], m['native_pss'], m['total_pss']), (16310, 13112, 10168, 11748, 58841))

    def test_frames(self):
        rows = phone.parse_frames(FRAMES)
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[1]['HandleInputStart'] - rows[1]['OldestInputEvent'], 260000000)
        self.assertEqual(rows[0]['OldestInputEvent'], phone.NONE)

    def test_battery(self):
        self.assertEqual(phone.parse_battery(BATTERY), (57, True, 311))
        self.assertEqual(phone.parse_battery('')[0], None)

    def test_stats(self):
        self.assertEqual(phone.stats([5, 1, 3]), {'n': 3, 'median': 3, 'p95': 5, 'max': 5})

    def test_sources_json(self):
        import json
        entries = json.loads(phone.sources_json(4))
        self.assertEqual([e['url'] for e in entries], [f'https://localhost:4484/s00{n}.git' for n in (1, 2, 3)])
        self.assertEqual(entries[0], {'url': 'https://localhost:4484/s001.git', 'name': 'Stress Source 001', 'commit': '', 'lastRefresh': 0})


if __name__ == '__main__':
    unittest.main()
