// ADB SYNC push/pull and APK installation. Staged only in the rooting build.
import { quoteShell } from './adb.mjs?v=redesign5';

const text = new TextEncoder();
const view = data => new DataView(data.buffer, data.byteOffset, data.byteLength);
const ascii = data => String.fromCharCode(...data);
const decode = bytes => new TextDecoder().decode(bytes);

function syncHeader(tag, size) {
  const header = new Uint8Array(8);
  header.set(text.encode(tag));
  view(header).setUint32(4, size, true);
  return header;
}

async function syncStart(stream, mode, path) {
  if (!path.startsWith('/') || path.includes('\0') || path.includes(',') || text.encode(path).length > 900) throw new Error('Invalid ADB sync path.');
  const arg = text.encode(mode === 'SEND' ? `${path},33188` : path);
  const request = new Uint8Array(8 + arg.length);
  request.set(syncHeader(mode, arg.length));
  request.set(arg, 8);
  await stream.send(request);
}
async function syncReply(stream) {
  const header = await stream.take(8);
  const tag = ascii(header.subarray(0, 4)), size = view(header).getUint32(4, true);
  if (tag === 'FAIL') {
    if (size > 4096) throw new Error('Invalid ADB sync failure frame.');
    throw new Error(`ADB sync: ${decode(await stream.take(size))}`);
  }
  return { tag, size };
}
async function syncEnd(stream) {
  await stream.send(syncHeader('QUIT', 0));
  await stream.drain();
}
export async function push(adb, path, data, seconds = Math.floor(Date.now() / 1000), onProgress) {
  if (!(data instanceof Uint8Array) || data.length > 128 * 1024 ** 2) throw new Error('Unsupported ADB push data or size.');
  return adb.runService('sync:', async stream => {
    await syncStart(stream, 'SEND', path);
    for (let offset = 0; offset < data.length; offset += 65536) {
      const part = data.subarray(offset, offset + 65536);
      const packet = new Uint8Array(8 + part.length);
      packet.set(syncHeader('DATA', part.length));
      packet.set(part, 8);
      await stream.send(packet);
      onProgress?.(offset + part.length, data.length);
    }
    await stream.send(syncHeader('DONE', seconds));
    const { tag, size } = await syncReply(stream);
    if (tag !== 'OKAY' || size !== 0) throw new Error('Unexpected ADB push acknowledgement.');
    await syncEnd(stream);
  });
}
export async function pull(adb, path, maxBytes = 64 * 1024 ** 2) {
  return adb.runService('sync:', async stream => {
    const chunks = [];
    let total = 0;
    await syncStart(stream, 'RECV', path);
    while (true) {
      const { tag, size } = await syncReply(stream);
      if (tag === 'DONE' && size === 0) break;
      if (tag !== 'DATA' || size < 1 || size > 65536 || (total += size) > maxBytes) throw new Error('Unexpected or oversized ADB pull data.');
      chunks.push(await stream.take(size));
    }
    await syncEnd(stream);
    const all = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) { all.set(chunk, offset); offset += chunk.length; }
    return all;
  });
}
export async function install(adb, apk, onProgress) {
  const path = `/data/local/tmp/wave-install-${crypto.randomUUID()}.apk`;
  try {
    await push(adb, path, apk, undefined, onProgress);
    const result = await adb.shell(`pm install -r ${quoteShell(path)}`, undefined, 180000);
    if (result.code !== 0 || !result.stdout.trim().endsWith('Success')) throw new Error(result.stderr || result.stdout || 'APK install failed.');
  } finally {
    await adb.shell(`rm -f ${quoteShell(path)}`).catch(() => {});
  }
}
