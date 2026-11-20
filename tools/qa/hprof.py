#!/usr/bin/env python3
"""Read an Android heap dump: instance counts, and why an object is still alive.

    hprof.py DUMP.hprof [--count PREFIX] [--why CLASS] [--max-paths N]

DUMP is what `am dumpheap` writes; it is run through the SDK's hprof-conv first when it
is still in Android's format. --count lists the classes under PREFIX (com.wave.xp3 by
default) by instance count; --why prints, for every instance of CLASS, the shortest chain
of strong references from a GC root (weak, soft and phantom referents don't count), so a
leaked Activity names what holds it. tools/qa/soak.py uses it on the dumps it takes.
"""
import argparse
from collections import Counter, deque
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

SIZES = {2: None, 4: 1, 5: 2, 6: 4, 7: 8, 8: 1, 9: 2, 10: 4, 11: 8}
ROOT_TAGS = {0xff: 0, 0x01: 0, 0x02: 8, 0x03: 8, 0x04: 4, 0x05: 0, 0x06: 4, 0x07: 0, 0x08: 8,
             0x89: 0, 0x8a: 0, 0x8b: 0, 0x8c: 0, 0x8d: 0, 0x8e: 8, 0x90: 0}
ROOT_NAMES = {0xff: 'unknown', 0x01: 'JNI global', 0x02: 'JNI local', 0x03: 'Java frame', 0x04: 'native stack',
              0x05: 'system class', 0x06: 'thread block', 0x07: 'monitor', 0x08: 'thread', 0x89: 'interned string',
              0x8a: 'finalizing', 0x8b: 'debugger', 0x8c: 'reference cleanup', 0x8d: 'VM internal',
              0x8e: 'JNI monitor', 0x90: 'unreachable'}


def converted(path):
    with open(path, 'rb') as f:
        head = f.read(19)
    if head.startswith(b'JAVA PROFILE 1.0.3') and os.environ.get('HPROF_CONVERT'):
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '/opt/android-sdk'
        tool = Path(sdk) / 'platform-tools' / 'hprof-conv'
        out = Path(tempfile.mkstemp(suffix='.hprof')[1])
        subprocess.run([str(tool), str(path), str(out)], check=True, capture_output=True)
        return out, True
    return Path(path), False


class Heap:
    def __init__(self, path):
        self.strings = {}
        self.class_names = {}      # class object id -> name
        self.supers = {}           # class id -> super class id
        self.fields = {}           # class id -> [(name, type)]
        self.static_refs = {}      # class id -> [(name, id)]
        self.instances = {}        # object id -> class id
        self.sizes = {}            # object id -> shallow bytes
        self.raw = {}              # object id -> field bytes (instances), or [ids] (arrays)
        self.roots = {}            # object id -> root kind
        self.array_class = {}
        self.thread_serials = {}   # thread serial -> Thread object (root records)
        self.frame_threads = {}    # object id -> serial of the thread whose stack holds it
        self._read(path)

    def _read(self, path):
        data = Path(path).read_bytes()
        n = data.index(b'\0') + 1
        self.idsize = struct.unpack_from('>I', data, n)[0]
        pos = n + 12
        ids = '>I' if self.idsize == 4 else '>Q'
        self.idfmt = ids
        while pos < len(data):
            tag, _, length = struct.unpack_from('>BII', data, pos)
            pos += 9
            end = pos + length
            if tag == 0x01:
                sid = struct.unpack_from(ids, data, pos)[0]
                self.strings[sid] = data[pos + self.idsize:end].decode('utf-8', 'replace')
            elif tag == 0x02:
                cid = struct.unpack_from(ids, data, pos + 4)[0]
                nid = struct.unpack_from(ids, data, pos + 8 + self.idsize)[0]
                self.class_names[cid] = self.strings.get(nid, '?').replace('/', '.')
            elif tag in (0x0c, 0x1c):
                self._dump(data, pos, end)
            pos = end

    def _id(self, data, pos):
        return struct.unpack_from(self.idfmt, data, pos)[0]

    def _dump(self, data, pos, end):
        I = self.idsize
        while pos < end:
            sub = data[pos]
            pos += 1
            if sub in ROOT_TAGS:
                oid = self._id(data, pos)
                self.roots.setdefault(oid, ROOT_NAMES[sub])
                if sub in (0x02, 0x03, 0x04, 0x08):
                    serial = struct.unpack_from('>I', data, pos + I)[0]
                    if sub == 0x08:
                        self.thread_serials[serial] = oid
                    else:
                        self.frame_threads.setdefault(oid, serial)
                pos += I + (I if sub == 0x01 else ROOT_TAGS[sub])  # a JNI global is followed by its own id
            elif sub == 0x20:
                cid = self._id(data, pos)
                pos += I + 4
                self.supers[cid] = self._id(data, pos)
                pos += I * 6 + 4
                (cp,) = struct.unpack_from('>H', data, pos)
                pos += 2
                for _ in range(cp):
                    t = data[pos + 2]
                    pos += 3 + (SIZES[t] or I)
                (ns,) = struct.unpack_from('>H', data, pos)
                pos += 2
                statics = []
                for _ in range(ns):
                    name = self.strings.get(self._id(data, pos), '?')
                    t = data[pos + I]
                    pos += I + 1
                    if t == 2:
                        ref = self._id(data, pos)
                        if ref:
                            statics.append((name, ref))
                    pos += SIZES[t] or I
                self.static_refs[cid] = statics
                (nf,) = struct.unpack_from('>H', data, pos)
                pos += 2
                fields = []
                for _ in range(nf):
                    fields.append((self.strings.get(self._id(data, pos), '?'), data[pos + I]))
                    pos += I + 1
                self.fields[cid] = fields
            elif sub == 0x21:
                oid = self._id(data, pos)
                cid = self._id(data, pos + I + 4)
                (nb,) = struct.unpack_from('>I', data, pos + 2 * I + 4)
                start = pos + 2 * I + 8
                self.instances[oid] = cid
                self.sizes[oid] = nb
                self.raw[oid] = data[start:start + nb]
                pos = start + nb
            elif sub == 0x22:
                oid = self._id(data, pos)
                (count,) = struct.unpack_from('>I', data, pos + I + 4)
                acid = self._id(data, pos + I + 8)
                start = pos + 2 * I + 8
                self.instances[oid] = acid
                self.array_class[oid] = True
                self.sizes[oid] = count * I
                self.raw[oid] = [self._id(data, start + k * I) for k in range(count)]
                pos = start + count * I
            elif sub == 0x23:
                oid = self._id(data, pos)
                (count,) = struct.unpack_from('>I', data, pos + I + 4)
                t = data[pos + I + 8]
                self.sizes[oid] = count * (SIZES[t] or I)
                self.instances[oid] = ('prim', t)
                if t in (5, 8):  # char[] and byte[]: string contents
                    self.raw[oid] = data[pos + I + 9:pos + I + 9 + count * SIZES[t]]
                pos += I + 9 + count * (SIZES[t] or I)
            elif sub == 0xfe:
                pos += 4 + I
            elif sub == 0xc3:
                pos += I + 9
            else:
                raise ValueError(f'unknown heap dump record 0x{sub:02x} at {pos - 1}')

    def name(self, oid):
        cid = self.instances.get(oid)
        if isinstance(cid, tuple):
            return 'primitive[]'
        if cid is None and oid not in self.class_names:
            return '?'
        if cid is None:
            return self.class_names.get(oid, '?') + ' (class)'
        return self.class_names.get(cid, '?') + ('[]' if oid in self.array_class else '')

    def field(self, oid, wanted):
        """The id in reference field `wanted` of instance `oid`, or None."""
        for name, ref in self.refs(oid):
            if name == wanted:
                return ref
        return None

    def string(self, oid):
        """The text of java.lang.String `oid`, or None."""
        if oid is None or self.name(oid) != 'java.lang.String':
            return None
        value = self.field(oid, 'value')
        raw = self.raw.get(value)
        if not isinstance(raw, (bytes, bytearray)):
            return None
        kind = self.instances[value][1]
        return raw.decode('utf-16-be' if kind == 5 else 'latin-1', 'replace')

    def label(self, oid):
        """Its class, and for a Thread its name."""
        text = self.name(oid)
        if text == 'java.lang.Thread' or text.endswith('Thread') and oid in self.instances:
            name = self.string(self.field(oid, 'name'))
            if name is not None:
                text += f' "{name}"'
        return text

    def root_kind(self, oid):
        kind = self.roots.get(oid, 'class statics')
        serial = self.frame_threads.get(oid)
        if serial is not None and serial in self.thread_serials:
            kind += ' of ' + self.label(self.thread_serials[serial])
        return kind

    def is_reference(self, cid):
        while cid:
            if self.class_names.get(cid) == 'java.lang.ref.Reference':
                return True
            cid = self.supers.get(cid)
        return False

    def refs(self, oid):
        """(field, child) for each strong reference held by `oid`."""
        if oid in self.class_names and oid not in self.instances:
            return [(f'static {n}', r) for n, r in self.static_refs.get(oid, [])]
        raw = self.raw.get(oid)
        if raw is None:
            return []
        if oid in self.array_class:
            return [(f'[{k}]', r) for k, r in enumerate(raw) if r]
        out = []
        cid = self.instances[oid]
        skip_referent = self.is_reference(cid)
        pos = 0
        out.append(('(class)', cid))
        while cid:
            for name, t in self.fields.get(cid, []):
                size = SIZES[t] or self.idsize
                if t == 2:
                    ref = struct.unpack_from(self.idfmt, raw, pos)[0]
                    if ref and not (skip_referent and name == 'referent'):
                        out.append((name, ref))
                pos += size
            cid = self.supers.get(cid)
        return out

    def count(self, prefix):
        c = Counter()
        for oid, cid in self.instances.items():
            if not isinstance(cid, tuple) and oid not in self.array_class:
                n = self.class_names.get(cid, '?')
                if n.startswith(prefix):
                    c[n] += 1
        return c

    def paths(self, targets, limit=3):
        """Shortest strong path from a root to each target: {target: [(holder, field), ...]}."""
        targets = set(targets)
        parent = {}
        queue = deque()
        for r in self.roots:
            if r not in parent:
                parent[r] = None
                queue.append(r)
        # Classes are roots too (their statics).
        for cid in self.class_names:
            if cid not in parent:
                parent[cid] = None
                queue.append(cid)
        found = {}
        while queue and len(found) < len(targets):
            oid = queue.popleft()
            for field, child in self.refs(oid):
                if child not in parent:
                    parent[child] = (oid, field)
                    if child in targets:
                        found[child] = None
                    queue.append(child)
        out = {}
        for t in targets:
            if t not in parent:
                out[t] = None
                continue
            chain = []
            cur = t
            while parent.get(cur):
                holder, field = parent[cur]
                chain.append((holder, field))
                cur = holder
            chain.reverse()
            out[t] = (cur, chain)
        return out


def why(heap, cls, limit):
    targets = [oid for oid, cid in heap.instances.items()
               if not isinstance(cid, tuple) and oid not in heap.array_class and heap.class_names.get(cid) == cls]
    lines = [f'{len(targets)} instance(s) of {cls}']
    for t, found in list(heap.paths(targets).items())[:limit]:
        if found is None:
            lines.append(f'  0x{t:x}: no strong path from a root (garbage, or only weakly held)')
            continue
        root, chain = found
        lines.append(f'  0x{t:x}: root {heap.label(root)} ({heap.root_kind(root)})')
        for holder, field in chain:
            lines.append(f'      {heap.label(holder)} .{field} ->')
        lines.append(f'      {cls}')
    return '\n'.join(lines)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('dump')
    p.add_argument('--count', default='com.wave.xp3')
    p.add_argument('--why', action='append', default=[])
    p.add_argument('--max-paths', type=int, default=5)
    a = p.parse_args()
    path, temporary = converted(a.dump)
    try:
        heap = Heap(path)
    finally:
        if temporary:
            path.unlink()
    for name, n in sorted(heap.count(a.count).items(), key=lambda kv: (-kv[1], kv[0])):
        print(f'{n:7d}  {name}')
    for cls in a.why:
        print(why(heap, cls, a.max_paths))


if __name__ == '__main__':
    sys.exit(main())
