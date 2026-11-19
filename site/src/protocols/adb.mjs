import { exact } from './webusb.mjs';
import { newAdbCredential } from './adb-credential.mjs';

const text = new TextEncoder();
const maxData = 256 * 1024;
const maxOutput = 1024 * 1024;
const version = 0x01000001;
const AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
const empty = new Uint8Array();
const view = data => new DataView(data.buffer, data.byteOffset, data.byteLength);
const ascii = data => String.fromCharCode(...data);
const commandWord = name => view(text.encode(name)).getUint32(0, true);
const commands = new Map(['CNXN', 'AUTH', 'OPEN', 'OKAY', 'WRTE', 'CLSE'].map(name => [commandWord(name), name]));
const decode = bytes => new TextDecoder().decode(bytes);
const sum = data => data.reduce((acc, byte) => (acc + byte) >>> 0, 0);

export function adbHeader(name, arg0, arg1, payload, checksum = true) {
  const header = new Uint8Array(24);
  const fields = view(header);
  const command = commandWord(name);
  [command, arg0, arg1, payload.length, checksum ? sum(payload) : 0, command ^ -1]
    .forEach((value, i) => fields.setUint32(i * 4, value, true));
  return header;
}

function syncHeader(tag, size) {
  const header = new Uint8Array(8);
  header.set(text.encode(tag));
  view(header).setUint32(4, size, true);
  return header;
}

export const quoteShell = string => `'${string.replaceAll("'", "'\\''")}'`;

class Stream {
  constructor(adb, local, remote) {
    Object.assign(this, { adb, local, remote, closed: false });
    this.buffer = empty;
  }
  async receive() {
    const packet = await this.adb.read();
    if (packet.arg1 !== this.local || packet.arg0 !== this.remote) throw new Error('ADB stream ID mismatch.');
    if (packet.command === 'CLSE') {
      this.closed = true;
      this.adb.retire(this.local, this.remote);
      await this.adb.write('CLSE', this.local, this.remote);
    } else if (packet.command === 'WRTE') {
      await this.adb.write('OKAY', this.local, this.remote);
    }
    return packet;
  }
  async #bufferWrite(payload) {
    if (this.buffer.length + payload.length > maxOutput + maxData) throw new Error('ADB output exceeds the receive buffer limit.');
    const combined = new Uint8Array(this.buffer.length + payload.length);
    combined.set(this.buffer);
    combined.set(payload, this.buffer.length);
    this.buffer = combined;
  }
  async send(bytes) {
    for (let offset = 0; offset < bytes.length; offset += this.adb.payloadLimit) {
      await this.adb.write('WRTE', this.local, this.remote, bytes.subarray(offset, offset + this.adb.payloadLimit));
      while (true) {
        const packet = await this.receive();
        if (packet.command === 'OKAY') break;
        if (packet.command !== 'WRTE') throw new Error('ADB stream closed before acknowledging data.');
        await this.#bufferWrite(packet.payload);
      }
    }
  }
  async next() {
    if (this.buffer.length) {
      const bytes = this.buffer;
      this.buffer = empty;
      return bytes;
    }
    if (this.closed) return null;
    const packet = await this.receive();
    if (packet.command === 'CLSE') return null;
    if (packet.command !== 'WRTE' || !packet.payload.length) throw new Error('Unexpected ADB stream packet.');
    return packet.payload;
  }
  async take(size) {
    const bytes = new Uint8Array(size);
    for (let at = 0; at < size;) {
      const part = await this.next();
      if (!part) throw new Error('ADB stream closed prematurely.');
      const count = Math.min(part.length, size - at);
      bytes.set(part.subarray(0, count), at);
      this.buffer = part.subarray(count);
      at += count;
    }
    return bytes;
  }
  async drain() {
    if (await this.next() !== null) throw new Error('ADB service returned unexpected trailing data.');
  }
}

// One operation at a time. A framing/transport error closes the whole session
// rather than attempting to use a stream whose packet boundaries are unknown.
export class Adb {
  #link;
  #nextId = 0;
  #busy = false;
  #checksum = true;
  #trace;
  #retired;
  constructor(link, trace) {
    this.#link = link;
    this.#trace = trace;
    this.payloadLimit = 4096;
    this.features = new Set();
  }
  static async connect(link, { credential, onAuthorization, trace } = {}) {
    const adb = new Adb(link, trace);
    try {
      // No delayed-ACK feature: each WRTE needs its own explicit OKAY.
      await adb.write('CNXN', version, maxData, text.encode('host::features=shell_v2\0'));
      let attempts = 0;
      while (true) {
        const packet = await adb.read(attempts === 2 ? 90000 : undefined);
        if (packet.command === 'CNXN') {
          if (packet.arg0 < 0x01000000 || packet.arg1 < 4096) throw new Error('Unsupported ADB protocol version or packet size.');
          adb.#checksum = packet.arg0 < version;
          adb.payloadLimit = Math.min(packet.arg1, maxData);
          const banner = decode(packet.payload).replace(/\0$/, '');
          if (!banner.startsWith('device::')) throw new Error('Expected Android in normal device mode.');
          const features = /(?:^|[;:])features=([^;]*)/.exec(banner)?.[1];
          adb.features = new Set(features?.split(','));
          return adb;
        }
        if (packet.command !== 'AUTH' || packet.arg0 !== AUTH_TOKEN || packet.arg1 !== 0 || packet.payload.length !== 20) {
          throw new Error('Unexpected ADB handshake packet.');
        }
        if (!credential) credential = await newAdbCredential();
        if (attempts === 0) await adb.write('AUTH', AUTH_SIGNATURE, 0, await credential.sign(packet.payload));
        else if (attempts === 1) {
          onAuthorization?.();
          await adb.write('AUTH', AUTH_RSAPUBLICKEY, 0, credential.publicKey);
        } else throw new Error('The phone did not authorize USB debugging.');
        attempts++;
      }
    } catch (error) {
      await adb.close();
      throw error;
    }
  }
  async close() { await this.#link.close(); }
  async write(name, a, b, data = empty) {
    this.#trace?.('out', name, a, b, data);
    await this.#link.write(adbHeader(name, a, b, data, this.#checksum));
    if (data.length) await this.#link.write(data, { terminate: true });
  }
  retire(local, remote) { this.#retired = { local, remote, duplicates: 0 }; }
  async read(timeoutMs) {
    let packet;
    do {
      packet = await this.#readPacket(timeoutMs);
      const retired = this.#retired;
      // Oreo adbd may emit a second CLSE after our acknowledgement. Ignore
      // only a matching empty close for the immediately preceding stream.
      if (!retired || packet.command !== 'CLSE' || packet.arg0 !== retired.remote || packet.arg1 !== retired.local
          || packet.payload.length || ++retired.duplicates > 2) return packet;
    } while (true);
  }
  async #readPacket(timeoutMs) {
    const fields = view(await exact(this.#link, 24, 24, timeoutMs));
    const word = fields.getUint32(0, true);
    const command = commands.get(word);
    const arg0 = fields.getUint32(4, true), arg1 = fields.getUint32(8, true), len = fields.getUint32(12, true);
    const checksum = fields.getUint32(16, true);
    if (!command || fields.getUint32(20, true) !== ((word ^ -1) >>> 0) || len > this.payloadLimit) throw new Error('Invalid ADB packet header.');
    const payload = await exact(this.#link, len);
    // Android can negotiate the skip-checksum variant before AUTH. A nonzero
    // checksum from a legacy peer must always match the payload.
    if ((checksum || (command !== 'AUTH' && command !== 'CNXN' && this.#checksum)) && sum(payload) !== checksum) {
      throw new Error('Incorrect ADB packet checksum.');
    }
    this.#trace?.('in', command, arg0, arg1, payload);
    return { command, arg0, arg1, payload };
  }
  async #run(service, use, { announceOpen } = {}) {
    if (this.#busy) throw new Error('An ADB operation is already running.');
    this.#busy = true;
    try {
      const local = ++this.#nextId;
      await this.write('OPEN', local, 0, text.encode(service + '\0'));
      const packet = await this.read();
      if (packet.command !== 'OKAY' || packet.arg1 !== local || !packet.arg0) throw new Error(`Phone refused ADB service ${service.split(':')[0]}.`);
      announceOpen?.();
      return await use(new Stream(this, local, packet.arg0));
    } catch (error) {
      await this.close();
      throw error;
    } finally {
      this.#busy = false;
    }
  }
  async shell(command, limit = maxOutput) {
    if (!this.features.has('shell_v2')) throw new Error('Phone does not report shell_v2; cannot check the exit status.');
    if (!command || text.encode(command).length > 2048 || command.includes('\0')) throw new Error('Invalid ADB shell command.');
    return this.#run(`shell,v2,raw:${command}`, async stream => {
      let code, total = 0;
      const stdout = new TextDecoder(), stderr = new TextDecoder();
      let out = '', err = '';
      while (code === undefined) {
        const frame = await stream.take(5);
        const len = view(frame).getUint32(1, true);
        if ((total += len) > limit || ![1, 2, 3].includes(frame[0]) || (frame[0] === 3 && len !== 1)) throw new Error('Invalid or oversized ADB shell output.');
        const chunk = await stream.take(len);
        if (frame[0] === 1) out += stdout.decode(chunk, { stream: true });
        if (frame[0] === 2) err += stderr.decode(chunk, { stream: true });
        if (frame[0] === 3) code = chunk[0];
      }
      await stream.drain();
      return { code, stdout: out + stdout.decode(), stderr: err + stderr.decode() };
    });
  }
  async #syncStart(stream, mode, path) {
    if (!path.startsWith('/') || path.includes('\0') || path.includes(',') || text.encode(path).length > 900) throw new Error('Invalid ADB sync path.');
    const arg = text.encode(mode === 'SEND' ? `${path},33188` : path);
    const request = new Uint8Array(8 + arg.length);
    request.set(syncHeader(mode, arg.length));
    request.set(arg, 8);
    await stream.send(request);
  }
  async #syncReply(stream) {
    const header = await stream.take(8);
    const tag = ascii(header.subarray(0, 4)), size = view(header).getUint32(4, true);
    if (tag === 'FAIL') {
      if (size > 4096) throw new Error('Invalid ADB sync failure frame.');
      throw new Error(`ADB sync: ${decode(await stream.take(size))}`);
    }
    return { tag, size };
  }
  async #syncEnd(stream) {
    await stream.send(syncHeader('QUIT', 0));
    await stream.drain();
  }
  async push(path, data, seconds = Math.floor(Date.now() / 1000)) {
    if (!(data instanceof Uint8Array) || data.length > 128 * 1024 ** 2) throw new Error('Unsupported ADB push data or size.');
    return this.#run('sync:', async stream => {
      await this.#syncStart(stream, 'SEND', path);
      for (let offset = 0; offset < data.length; offset += 65536) {
        const part = data.subarray(offset, offset + 65536);
        const packet = new Uint8Array(8 + part.length);
        packet.set(syncHeader('DATA', part.length));
        packet.set(part, 8);
        await stream.send(packet);
      }
      await stream.send(syncHeader('DONE', seconds));
      const { tag, size } = await this.#syncReply(stream);
      if (tag !== 'OKAY' || size !== 0) throw new Error('Unexpected ADB push acknowledgement.');
      await this.#syncEnd(stream);
    });
  }
  async pull(path, maxBytes = 64 * 1024 ** 2) {
    return this.#run('sync:', async stream => {
      const chunks = [];
      let total = 0;
      await this.#syncStart(stream, 'RECV', path);
      while (true) {
        const { tag, size } = await this.#syncReply(stream);
        if (tag === 'DONE' && size === 0) break;
        if (tag !== 'DATA' || size < 1 || size > 65536 || (total += size) > maxBytes) throw new Error('Unexpected or oversized ADB pull data.');
        chunks.push(await stream.take(size));
      }
      await this.#syncEnd(stream);
      const all = new Uint8Array(total);
      let offset = 0;
      for (const chunk of chunks) { all.set(chunk, offset); offset += chunk.length; }
      return all;
    });
  }
  async install(apk) {
    const path = `/data/local/tmp/wave-install-${crypto.randomUUID()}.apk`;
    try {
      await this.push(path, apk);
      const result = await this.shell(`pm install -r ${quoteShell(path)}`);
      if (result.code !== 0 || !result.stdout.trim().endsWith('Success')) throw new Error(result.stderr || result.stdout || 'APK install failed.');
    } finally {
      await this.shell(`rm -f ${quoteShell(path)}`).catch(() => {});
    }
  }
  async reboot() {
    let opened = false;
    try {
      await this.#run('reboot:', stream => stream.drain(), { announceOpen: () => { opened = true; } });
    } catch (error) {
      if (!opened || error.name !== 'NotFoundError') throw error;
    } finally {
      await this.close();
    }
  }
}
