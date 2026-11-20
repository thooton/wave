// Build assets listed by name with their SHA-256 in files/files.json.
import { sha256 } from '../protocols/boot-image.mjs';
import { checkProgrammer } from '../protocols/sahara.mjs';

const NAME = /^[A-Za-z0-9._-]+\.(?:apk|mbn)$/;
const HASH = /^[0-9a-f]{64}$/;

// The entries the site lists, by role ('programmer', 'magisk', 'wave'); {} if none.
export async function servedFiles(base) {
  let list;
  try {
    const response = await fetch(new URL('files.json', base), { cache: 'no-cache' });
    if (!response.ok) return {};
    list = await response.json();
  } catch { return {}; }
  const entries = {};
  for (const [name, entry] of Object.entries(list ?? {})) {
    if (['programmer', 'magisk', 'wave'].includes(name) && typeof entry?.file === 'string' && NAME.test(entry.file)
      && typeof entry.sha256 === 'string' && HASH.test(entry.sha256)) entries[name] = entry;
  }
  return entries;
}

// Download one listed file and check it against its listed SHA-256.
export async function fetchServed(base, entry) {
  const response = await fetch(new URL(entry.file, base));
  if (!response.ok) throw new Error(`Could not download ${entry.file} from this site.`);
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (await sha256(bytes) !== entry.sha256) throw new Error(`${entry.file} does not match the listed SHA-256.`);
  return bytes;
}

export async function fetchProgrammer(base, entry) {
  if (!entry) throw new Error('This site build is missing the bundled Sonim programmer.');
  const bytes = await fetchServed(base, entry);
  await checkProgrammer(bytes);
  return bytes;
}
