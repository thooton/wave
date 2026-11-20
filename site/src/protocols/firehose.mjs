import { exact } from './webusb.mjs?v=redesign5';
import { checkBootImage } from './boot-image.mjs?v=redesign5';
import { parseXp3800Gpt, readGptHeader, sectorBytes } from './gpt.mjs?v=redesign5';

const encoder = new TextEncoder();
const xml = element => encoder.encode(`<?xml version="1.0" encoding="UTF-8" ?><data>${element}</data>`);

// This subset accepts only complete Firehose response/log elements. It does
// not evaluate DTDs, entities or XML from any external source.
function attributes(text) {
  const out = Object.create(null);
  while (text.length) {
    const attr = /^\s+([A-Za-z][A-Za-z0-9_]*)="([^"<>]*)"/.exec(text);
    if (!attr || Object.hasOwn(out, attr[1])) throw new Error('Malformed Firehose XML attribute.');
    out[attr[1]] = attr[2];
    text = text.slice(attr[0].length);
  }
  return out;
}

// The framing shared by the read-only backup reader and the boot writer
// (firehose-write.mjs, only in the rooting build). It sends only what its
// callers ask for; it has no program, erase or patch request of its own.
export class FirehoseChannel {
  #link;
  #xml = '';
  #busy = false;
  #decoder = new TextDecoder('utf-8', { fatal: true });
  #catalog;
  limit = 4096;
  toTarget = 0;
  header;
  constructor(link) { this.#link = link; }
  get link() { return this.#link; }

  async response(rawmode, configureLog = false, timeoutMs) {
    let logs = [];
    for (let i = 0; i < 100; i++) {
      const end = this.#xml.indexOf('</data>');
      if (end < 0) {
        this.#xml += this.#decoder.decode(await this.#link.read(4096, timeoutMs), { stream: true }).replaceAll('\0', '');
        if (this.#xml.length > 16384) throw new Error('Firehose XML response is too large.');
        continue;
      }
      const message = this.#xml.slice(0, end + 7).trim();
      this.#xml = this.#xml.slice(end + 7);
      const root = /^(?:<\?xml\s+[^<>]+\?>\s*)?<data>([\s\S]*)<\/data>$/.exec(message);
      if (!root) throw new Error('Malformed Firehose XML.');
      let content = root[1].trim();
      let response;
      while (content) {
        const element = /^<(log|response)([^<>]*?)\s*\/>/.exec(content);
        if (!element) throw new Error('Unexpected Firehose XML element.');
        const attrs = attributes(element[2]);
        if (element[1] === 'response') {
          if (response) throw new Error('Duplicate Firehose response.');
          response = attrs;
        } else logs.push(attrs.value ?? '');
        content = content.slice(element[0].length).trimStart();
      }
      if (configureLog) {
        if (response || !logs.some(line => line.startsWith('logbuf@'))) throw new Error('Expected MSM8909 post-configure log.');
        return { logs };
      }
      if (response) {
        if (response.value !== 'ACK' || (rawmode !== undefined && response.rawmode !== rawmode)) {
          throw new Error(`Firehose did not acknowledge the requested mode${logs.length ? `: ${logs.at(-1)}` : '.'}`);
        }
        return { response, logs };
      }
    }
    throw new Error('No bounded Firehose response received.');
  }

  get idle() { return !this.#xml.trim(); }

  async exclusive(action) {
    if (this.#busy) throw new Error('Firehose operation already running.');
    this.#busy = true;
    try { return await action(); }
    catch (error) { await this.#link.close(); throw error; }
    finally { this.#busy = false; }
  }

  async configure(skipWrite) {
    await this.#link.write(xml(`<configure MemoryName="eMMC" Verbose="0" AlwaysValidate="0" MaxDigestTableSizeInBytes="2048" MaxPayloadSizeToTargetInBytes="1048576" ZLPAwareHost="1" SkipStorageInit="0" SkipWrite="${skipWrite ? 1 : 0}"/>`));
    const { response } = await this.response();
    const limit = Number(response.MaxPayloadSizeFromTargetInBytes), toTarget = Number(response.MaxPayloadSizeToTargetInBytes);
    if (response.MemoryName !== 'eMMC' || !['8909', 'MSM8909'].includes(response.TargetName)
        || !Number.isInteger(limit) || limit < sectorBytes || limit > 16384 || limit % sectorBytes) {
      throw new Error('Unexpected eMMC Firehose target or packet size.');
    }
    this.limit = limit;
    this.toTarget = Number.isInteger(toTarget) && toTarget >= sectorBytes && toTarget % sectorBytes === 0 ? Math.min(toTarget, 1048576) : 0;
    // The signed MSM8909 programmer sends a second log after its configure
    // ACK. Drain it before OUT; otherwise the device can block the next OUT
    // while it waits for us to consume its pending IN transfer.
    await this.response(undefined, true);
    // Match the desktop reference: initialize the selected eMMC LUN by
    // reading its GPT header before requesting storage information.
    this.header = readGptHeader(await this.readSectors(1, 1));
    await this.#link.write(xml('<getstorageinfo physical_partition_number="0"/>'));
    const { logs } = await this.response();
    if (!logs.includes('SECTOR_SIZE_IN_BYTES=512')) throw new Error('Target did not report 512-byte sectors.');
  }

  async readSectors(start, count, onProgress) {
    await this.#link.write(encoder.encode(`<?xml version="1.0" ?><data><read SECTOR_SIZE_IN_BYTES="512" num_partition_sectors="${count}" physical_partition_number="0" start_sector="${start}"/>\n</data>`));
    await this.response('true');
    if (!this.idle) throw new Error('Unexpected XML before raw Firehose data.');
    const data = await exact(this.#link, count * sectorBytes, this.limit, undefined, onProgress);
    await this.response('false');
    return data;
  }

  async layout() {
    const { entryLba, sectors } = this.header;
    this.#catalog ??= parseXp3800Gpt(this.header, await this.readSectors(entryLba, sectors));
    return this.#catalog;
  }

  async reset() {
    try {
      await this.exclusive(async () => {
        await this.#link.write(xml('<power value="reset"/>'));
        await this.response();
      });
    } finally {
      await this.#link.close();
    }
  }
}

// Read-only: configures with SkipWrite="1" and reads only the GPT and boot.
export class FirehoseBootReader {
  #channel;
  #configured = false;
  constructor(link) { this.#channel = new FirehoseChannel(link); }

  async configure() {
    await this.#channel.exclusive(() => this.#channel.configure(true));
    this.#configured = true;
  }

  async readGpt() {
    if (!this.#configured) throw new Error('Firehose eMMC not configured.');
    return this.#channel.exclusive(() => this.#channel.layout());
  }

  async readBoot(onProgress) {
    if (!this.#configured) throw new Error('Firehose eMMC not configured.');
    return this.#channel.exclusive(async () => {
      // The only storage reads exposed by this tool are the GPT and boot.
      const { boot: { start, sectors } } = await this.#channel.layout();
      const image = await this.#channel.readSectors(start, sectors, onProgress);
      checkBootImage(image);
      return image;
    });
  }

  // The eMMC's GPT disk GUID; the backup file records it to recognise the phone in EDL.
  diskGuid() {
    if (!this.#configured) throw new Error('Firehose eMMC not configured.');
    return this.#channel.header.diskGuid;
  }

  async reset() {
    if (!this.#configured) throw new Error('Firehose eMMC not configured.');
    await this.#channel.reset();
  }
}
