import { exact } from './webusb.mjs';
import { checkBootImage } from './boot-image.mjs';
import { findXp3800Boot, readGptHeader, sectorBytes } from './gpt.mjs';

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

export class FirehoseBootReader {
  #link;
  #xml = '';
  #configured = false;
  #busy = false;
  #limit = 4096;
  #decoder = new TextDecoder('utf-8', { fatal: true });
  constructor(link) { this.#link = link; }

  async #response(rawmode) {
    let logs = [];
    for (let i = 0; i < 100; i++) {
      const end = this.#xml.indexOf('</data>');
      if (end < 0) {
        this.#xml += this.#decoder.decode(await this.#link.read(4096), { stream: true }).replaceAll('\0', '');
        if (this.#xml.length > 16384) throw new Error('Oversized Firehose XML response.');
        continue;
      }
      const message = this.#xml.slice(0, end + 7).trim();
      this.#xml = this.#xml.slice(end + 7);
      const root = /^(?:<\?xml\s+[^<>]+\?>\s*)?<data>([\s\S]*)<\/data>$/.exec(message);
      if (!root) throw new Error('Malformed Firehose XML document.');
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
      if (response) {
        if (response.value !== 'ACK' || (rawmode !== undefined && response.rawmode !== rawmode)) {
          throw new Error(`Firehose did not acknowledge the requested mode${logs.length ? `: ${logs.at(-1)}` : '.'}`);
        }
        return { response, logs };
      }
    }
    throw new Error('No bounded Firehose response was received.');
  }

  async #exclusive(action) {
    if (this.#busy) throw new Error('A Firehose operation is already running.');
    this.#busy = true;
    try { return await action(); }
    catch (error) { await this.#link.close(); throw error; }
    finally { this.#busy = false; }
  }

  async configure() {
    return this.#exclusive(async () => {
      await this.#link.write(xml('<configure MemoryName="eMMC" Verbose="0" AlwaysValidate="0" MaxDigestTableSizeInBytes="2048" MaxPayloadSizeToTargetInBytes="1048576" ZLPAwareHost="1" SkipStorageInit="0" SkipWrite="1"/>'));
      const { response } = await this.#response();
      const limit = Number(response.MaxPayloadSizeFromTargetInBytes);
      if (response.MemoryName !== 'eMMC' || !['8909','MSM8909'].includes(response.TargetName)
          || !Number.isInteger(limit) || limit < sectorBytes || limit > 16384 || limit % sectorBytes) {
        throw new Error('Unexpected eMMC Firehose target or packet size.');
      }
      this.#limit = limit;
      await this.#link.write(xml('<getstorageinfo physical_partition_number="0"/>'));
      const { logs } = await this.#response();
      if (!logs.includes('SECTOR_SIZE_IN_BYTES=512')) throw new Error('The target did not report 512-byte sectors.');
      this.#configured = true;
    });
  }

  async #readSectors(start, count) {
    await this.#link.write(xml(`<read SECTOR_SIZE_IN_BYTES="512" num_partition_sectors="${count}" physical_partition_number="0" start_sector="${start}"/>`));
    await this.#response('true');
    if (this.#xml.trim()) throw new Error('Unexpected XML before raw Firehose data.');
    const data = await exact(this.#link, count * sectorBytes, this.#limit);
    await this.#response('false');
    return data;
  }

  async readBoot() {
    if (!this.#configured) throw new Error('Firehose eMMC has not been configured.');
    return this.#exclusive(async () => {
      // The only storage reads exposed by this tool are the GPT and boot.
      const header = readGptHeader(await this.#readSectors(1, 1));
      const { start, sectors } = findXp3800Boot(header, await this.#readSectors(header.entryLba, header.sectors));
      const image = await this.#readSectors(start, sectors);
      checkBootImage(image);
      return image;
    });
  }

  async reset() {
    if (!this.#configured) throw new Error('Firehose eMMC has not been configured.');
    try {
      await this.#exclusive(async () => {
        await this.#link.write(xml('<power value="reset"/>'));
        await this.#response();
      });
    } finally {
      await this.#link.close();
    }
  }
}
