"""Host checks for the soak's parsing and its heap dump reader (no phone needed)."""
import struct
import tempfile
import unittest
from pathlib import Path

import hprof
import soak

MEMINFO = '''@@status
Name:\tcom.wave.xp3
State:\tS (sleeping)
VmRSS:\t   57820 kB
Threads:\t27
@@fds 69
@@meminfo
** MEMINFO in pid 2736 [com.wave.xp3] **
                   Pss  Private  Private  SwapPss     Heap     Heap     Heap
                 Total    Dirty    Clean    Dirty     Size    Alloc     Free
                ------   ------   ------   ------   ------   ------   ------
  Native Heap     5100     5060        0        0    11776     5396     6379
  Dalvik Heap     2200     2180        0        0    21282     2850    18432
 Dalvik Other      600      600        0        0
        Stack      300      300        0        0
        TOTAL    26664    20000     2144        0    33058     8246    24811

 App Summary
                       Pss(KB)
                        ------
           Java Heap:     2180
         Native Heap:     5060
            Graphics:     5036

 Objects
               Views:      261         ViewRootImpl:        1
         AppContexts:        4           Activities:        3
              Assets:        2        AssetManagers:        2
       Local Binders:       62        Proxy Binders:       23
       Parcel memory:        9         Parcel count:       36
    Death Recipients:        0      OpenSSL Sockets:        0
'''


class Sample(unittest.TestCase):
    def test_meminfo_threads_and_descriptors(self):
        s = soak.parse_sample(MEMINFO)
        self.assertEqual(s, {'threads': 27, 'rss_kb': 57820, 'fds': 69, 'native_size_kb': 11776,
                             'native_alloc_kb': 5396, 'java_size_kb': 21282, 'java_alloc_kb': 2850,
                             'pss_kb': 26664, 'uss_kb': 22144, 'graphics_kb': 5036, 'views': 261, 'viewroots': 1,
                             'activities': 3, 'appcontexts': 4, 'assets': 2, 'local_binders': 62,
                             'proxy_binders': 23, 'death_recipients': 0})

    def test_summary_slope_is_per_hundred_cycles_over_the_second_half(self):
        rows = [{'phase': 'walk', 'cycle': c, 'views': 100 + (c if c >= 50 else 0)} for c in range(0, 101, 10)]
        t = soak.summarise(rows)['walk']['views']
        self.assertEqual((t['first'], t['last'], t['max']), (100, 200, 200))
        self.assertAlmostEqual(t['slope100'], 100.0)


BATTERY = """Current Battery Service state:
  AC powered: false
  USB powered: true
  Wireless powered: false
  status: 2
  health: 2
  present: true
  level: 47
  scale: 100
  voltage: 3912
  temperature: 312
  technology: Li-ion
"""


class Battery(unittest.TestCase):
    def test_level_temperature_and_power(self):
        phone = soak.Phone()
        phone.sh = lambda script, timeout=120: BATTERY
        self.assertEqual(phone.battery(), {'level': 47, 'temp': 312, 'status': 2, 'plugged': True})

    def test_the_soak_stops_under_its_floor(self):
        phone = soak.Phone()
        levels = iter([31, 29])
        phone.battery = lambda: {'level': next(levels), 'temp': 300, 'plugged': True}
        run = soak.Soak(phone, None, Path('.'), 20, 0, battery_stop=30)
        run.guard('walk', 1)
        with self.assertRaisesRegex(soak.SoakError, 'battery at 29%'):
            run.guard('walk', 2)


class Source(unittest.TestCase):
    def test_churn_commits_a_new_round(self):
        with tempfile.TemporaryDirectory() as d:
            src = soak.Source(Path(d))
            src.build()
            before = sorted(p.name for p in (src.repo / 'games').iterdir())
            src.churn()
            src.churn()
            after = sorted(p.name for p in (src.repo / 'games').iterdir())
            self.assertEqual(src.round, 2)
            self.assertIn('games-101', after)
            self.assertIn('games-102', after)
            self.assertEqual(len(after), len(before) + 2)
            self.assertIn('"version": "1.2"', (src.repo / 'applications' / 'applications-10' / 'package.json').read_text())


def tiny_dump():
    """An hprof with a GC root -> Holder.field -> Leaked, and an unreachable Leaked."""
    I = 4
    out = bytearray(b'JAVA PROFILE 1.0.3\0') + struct.pack('>IQ', I, 0)

    def record(tag, body):
        out.extend(struct.pack('>BII', tag, 0, len(body)) + body)

    names = {1: 'Holder', 2: 'Leaked', 3: 'field', 4: 'java/lang/Object'}
    for sid, text in names.items():
        record(0x01, struct.pack('>I', sid) + text.encode())
    for serial, (cid, sid) in enumerate(((100, 1), (200, 2), (300, 4)), 1):
        record(0x02, struct.pack('>IIII', serial, cid, 0, sid))

    def class_dump(cid, sup, fields):
        body = struct.pack('>BII', 0x20, cid, 0) + struct.pack('>IIIIII', sup, 0, 0, 0, 0, 0) + struct.pack('>I', 8)
        body += struct.pack('>HH', 0, 0) + struct.pack('>H', len(fields))
        for sid, t in fields:
            body += struct.pack('>IB', sid, t)
        return body

    heap = class_dump(300, 0, []) + class_dump(100, 300, [(3, 2)]) + class_dump(200, 300, [])
    heap += struct.pack('>BIII', 0x21, 1000, 0, 100) + struct.pack('>I', 4) + struct.pack('>I', 2000)
    heap += struct.pack('>BIII', 0x21, 2000, 0, 200) + struct.pack('>I', 0)
    heap += struct.pack('>BIII', 0x21, 3000, 0, 200) + struct.pack('>I', 0)
    heap += struct.pack('>BIII', 0x03, 1000, 1, 0)   # Java frame root: Holder
    record(0x0c, heap)
    return bytes(out)


class Hprof(unittest.TestCase):
    def test_why_names_the_chain_and_the_garbage(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'tiny.hprof'
            path.write_bytes(tiny_dump())
            heap = hprof.Heap(path)
            self.assertEqual(heap.count('Leaked'), {'Leaked': 2})
            text = hprof.why(heap, 'Leaked', 5)
            self.assertIn('2 instance(s) of Leaked', text)
            self.assertIn('0x7d0: root Holder (Java frame)', text)
            self.assertIn('Holder .field ->', text)
            self.assertIn('0xbb8: no strong path from a root', text)


if __name__ == '__main__':
    unittest.main()
