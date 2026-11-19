import json
from pathlib import Path
import tempfile
import unittest

import run
import seeded


class SeededScenarios(unittest.TestCase):
    def scenario(self, **extra):
        doc = {'name': 'x', 'steps': [{'name': 'Launch', 'action': 'launch'}], **extra}
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / 's.json'
        path.write_text(json.dumps(doc), encoding='utf-8')
        return path

    def test_shipped_scenarios_are_valid(self):
        for path in sorted((Path(__file__).parent / 'seeded').glob('*.json')):
            doc, files, expect = seeded.read(path)
            plain = path.parent / ('.check-' + path.name)
            try:
                plain.write_text(json.dumps({'name': doc['name'], 'steps': doc['steps']}), encoding='utf-8')
                run.read_scenario(plain)
            finally:
                plain.unlink(missing_ok=True)

    def test_seed_paths_stay_inside_the_sources_files(self):
        for bad in ('files/other.txt', 'files/sources/../x', '../files/sources.json', 'shared_prefs/x.xml', '/sdcard/x',
                    'files/sources/a b'):
            with self.assertRaises(run.HarnessError, msg=bad):
                seeded.read(self.scenario(seed={bad: 'x'}))
        with self.assertRaises(run.HarnessError):
            seeded.read(self.scenario(seed={'files/sources.json': 'x' * (seeded.MAX_FILE + 1)}))
        with self.assertRaises(run.HarnessError):
            seeded.read(self.scenario(expect=['files/receipts.json']))
        with self.assertRaises(run.HarnessError):
            seeded.read(self.scenario(builder='nope'))
        seeded.read(self.scenario(seed={'files/sources.json': '[]'}, expect=['files/sources/damaged.json']))

    def test_cached_source_lives_where_the_store_looks(self):
        files = seeded.cached_and_signin()
        cached_id = seeded.source_id('https://qa-cache.invalid/source')
        tree = f'files/sources/{cached_id}/tree/{"a" * 40}'
        self.assertIn(tree + '/wave.json', files)
        entries = json.loads(files['files/sources.json'])
        self.assertEqual(['a' * 40, ''], [e['commit'] for e in entries])
        self.assertTrue(all(isinstance(v, (str, bytes)) for v in files.values()))

    def test_bomb_png_is_tiny_and_declares_its_size(self):
        data = seeded.png(65535)
        self.assertLess(len(data), 100)
        self.assertEqual(65535, int.from_bytes(data[16:20], 'big'))


if __name__ == '__main__':
    unittest.main()
