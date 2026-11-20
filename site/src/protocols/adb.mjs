import { exact } from './webusb.mjs';
import { newAdbCredential } from './adb-credential.mjs';

const text = new TextEncoder();
const maxData = 256 * 1024;
const maxOutput = 1024 * 1024;
const version = 0x01000001;
const AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
const empty = new Uint8Array();
const view = data => new DataView(data.buffer, data.byteOffset, data.byteLength);
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
    if (this.buffer.length + payload.length > maxOutput + maxData) throw new Error('ADB output exceeds the buffer limit.');
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
        if (packet.command !== 'WRTE') throw new Error('ADB stream closed before data was acknowledged.');
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
      if (!part) throw new Error('ADB stream closed too early.');
      const count = Math.min(part.length, size - at);
      bytes.set(part.subarray(0, count), at);
      this.buffer = part.subarray(count);
      at += count;
    }
    return bytes;
  }
  async drain() {
    if (await this.next() !== null) throw new Error('ADB service returned unexpected extra data.');
  }
}

// One operation at a time. A framing/transport error closes the whole session
// rather than attempting to use a stream whose packet boundaries are unknown.
export class Adb {
  #link;
  #nextId = 0;
  #busy = false;
  #checksum = true;
  #connected = false;
  #trace;
  #retired;
  #timeoutMs;
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
      // Until CNXN, accept a previous session's largest packets so they can be skipped.
      adb.payloadLimit = maxData;
      let signed = 0, offered = false, stale = 0;
      while (true) {
        // After offering the key, wait for the owner to answer the prompt.
        const packet = await adb.read(offered ? 90000 : undefined);
        // adbd may still flush packets from an earlier session on this device
        // (a late CLSE, the end of a stream) before it answers CNXN.
        if (['OKAY', 'WRTE', 'CLSE'].includes(packet.command) && ++stale <= 64) continue;
        if (packet.command === 'CNXN') {
          if (packet.arg0 < 0x01000000 || packet.arg1 < 4096) throw new Error('Unsupported ADB protocol version or packet size.');
          adb.#checksum = packet.arg0 < version;
          adb.payloadLimit = Math.min(packet.arg1, maxData);
          const banner = decode(packet.payload).replace(/\0$/, '');
          if (!banner.startsWith('device::')) throw new Error('Device should be in normal Android mode.');
          const features = /(?:^|[;:])features=([^;]*)/.exec(banner)?.[1];
          adb.features = new Set(features?.split(','));
          adb.#connected = true;
          return adb;
        }
        if (packet.command !== 'AUTH' || packet.arg0 !== AUTH_TOKEN || packet.arg1 !== 0 || packet.payload.length !== 20) {
          throw new Error('Unexpected ADB handshake packet.');
        }
        if (!credential) credential = await newAdbCredential();
        // A token left over from an abandoned handshake fails once, so sign
        // twice before offering the key; adbd sends a new token each time.
        if (signed < 2 || (offered && signed < 6)) {
          signed++;
          await adb.write('AUTH', AUTH_SIGNATURE, 0, await credential.sign(packet.payload));
        } else if (!offered) {
          offered = true;
          onAuthorization?.();
          await adb.write('AUTH', AUTH_RSAPUBLICKEY, 0, credential.publicKey);
        } else throw new Error('USB debugging is not authorized on the device.');
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
  async read(timeoutMs = this.#timeoutMs) {
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
    // Before CNXN, a stale packet from an earlier skip-checksum session may carry none.
    if ((checksum || (command !== 'AUTH' && command !== 'CNXN' && this.#checksum && this.#connected)) && sum(payload) !== checksum) {
      throw new Error('Incorrect ADB packet checksum.');
    }
    this.#trace?.('in', command, arg0, arg1, payload);
    return { command, arg0, arg1, payload };
  }
  // timeoutMs: the longest wait for any one packet while the service runs
  // (the USB link's own default otherwise), for commands that work silently.
  async runService(service, use, { announceOpen, timeoutMs } = {}) {
    if (this.#busy) throw new Error('An ADB operation is already running.');
    this.#busy = true;
    this.#timeoutMs = timeoutMs;
    try {
      const local = ++this.#nextId;
      await this.write('OPEN', local, 0, text.encode(service + '\0'));
      const packet = await this.read();
      if (packet.command !== 'OKAY' || packet.arg1 !== local || !packet.arg0) throw new Error(`Device refused ADB service ${service.split(':')[0]}.`);
      announceOpen?.();
      return await use(new Stream(this, local, packet.arg0));
    } catch (error) {
      await this.close();
      throw error;
    } finally {
      this.#busy = false;
      this.#timeoutMs = undefined;
    }
  }
  async shell(command, limit = maxOutput, timeoutMs) {
    if (!this.features.has('shell_v2')) throw new Error('Device does not report shell_v2; cannot check exit status.');
    if (!command || text.encode(command).length > 2048 || command.includes('\0')) throw new Error('Invalid ADB shell command.');
    return this.runService(`shell,v2,raw:${command}`, async stream => {
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
    }, { timeoutMs });
  }
  // target: '' restarts Android; 'edl' restarts into Qualcomm EDL (05c6:9008).
  async reboot(target = '') {
    if (!['', 'edl'].includes(target)) throw new Error('Unsupported reboot target.');
    let opened = false;
    try {
      await this.runService(`reboot:${target}`, stream => stream.drain(), { announceOpen: () => { opened = true; } });
    } catch (error) {
      // Once adbd has accepted reboot:, the phone drops off USB. Chromium on
      // Linux reports that as NetworkError (a transfer error), not only
      // NotFoundError, and either means the restart is under way.
      if (!opened || !['NotFoundError', 'NetworkError'].includes(error.name)) throw error;
    } finally {
      await this.close();
    }
  }
}
