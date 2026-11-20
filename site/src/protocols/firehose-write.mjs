// Writes the XP3800's boot partition, and nothing else. Only in the rooting build.
// Every write is checked before and after: the partition must hold exactly what
// the caller says it holds (the phone's own stock backup when rooting), the image
// must be that backup patched and signed, or a full stock image, and the whole
// partition is read back and compared byte for byte.
import { bootBytes, checkBootImage } from './boot-image.mjs?v=redesign3';
import { checkPatchedBoot } from './boot-signature.mjs?v=redesign3';
import { FirehoseChannel } from './firehose.mjs?v=redesign3';
import { sectorBytes } from './gpt.mjs?v=redesign3';

const encoder = new TextEncoder();
const differs = (a, b) => a.length !== b.length || a.some((byte, i) => byte !== b[i]);

export class WriteCheckError extends Error {
  constructor(message, written) {
    super(message);
    this.name = 'WriteCheckError';
    this.written = written;
  }
}

export class FirehoseBootWriter {
  #channel;
  #configured = false;
  constructor(link) { this.#channel = new FirehoseChannel(link); }

  async configure() {
    await this.#channel.exclusive(() => this.#channel.configure(false));
    if (!this.#channel.toTarget) throw new Error('The programmer did not report a usable write size.');
    this.#configured = true;
  }

  #ready() { if (!this.#configured) throw new Error('Firehose eMMC is not configured.'); }

  async readGpt() {
    this.#ready();
    return this.#channel.exclusive(() => this.#channel.layout());
  }

  diskGuid() {
    this.#ready();
    return this.#channel.header.diskGuid;
  }

  async readBoot(onProgress) {
    this.#ready();
    return this.#channel.exclusive(async () => {
      const { boot } = await this.#channel.layout();
      return this.#channel.readSectors(boot.start, boot.sectors, onProgress);
    });
  }

  async #program(start, data, onProgress) {
    const channel = this.#channel, link = channel.link;
    const sectors = data.length / sectorBytes;
    await link.write(encoder.encode(`<?xml version="1.0" ?><data>\n<program SECTOR_SIZE_IN_BYTES="512" num_partition_sectors="${sectors}" physical_partition_number="0" start_sector="${start}"/>\n</data>`));
    await channel.response('true');
    if (!channel.idle) throw new Error('Unexpected XML before the Firehose write.');
    // As bkerler/edl: chunks of the target's maximum, each ended with a ZLP.
    for (let at = 0; at < data.length; at += channel.toTarget) {
      await link.write(data.subarray(at, Math.min(at + channel.toTarget, data.length)), { terminate: true });
      onProgress?.(Math.min(at + channel.toTarget, data.length), data.length);
    }
    const { response } = await channel.response(undefined, false, 60000);
    if (response.rawmode !== undefined && response.rawmode !== 'false') throw new Error('Firehose didn\'t end the write.');
  }

  // True once a program request has been sent in this session.
  touched = false;

  // current: the whole partition as it must be now (required for a patched image,
  // which is shorter than the partition and must be a signed patch of it).
  // Returns the partition before and after; `after` has been compared with what
  // was meant to be there. A failed comparison throws a WriteCheckError and keeps
  // the session open, so the caller can reset or write the stock image back.
  async writeBoot(image, { current, onProgress, certSha256 } = {}) {
    this.#ready();
    if (image.length === bootBytes) checkBootImage(image);
    else {
      if (!current) throw new Error('Patched images can only be written over the stock image they were made from.');
      await checkPatchedBoot(image, current, { certSha256 });
    }
    if (current) checkBootImage(current);
    const progress = phase => (done, total) => onProgress?.(phase, done, total);
    const { boot } = await this.readGpt();
    const length = Math.ceil(image.length / sectorBytes) * sectorBytes;
    if (length > boot.sectors * sectorBytes) throw new Error('Image is larger than the boot partition.');
    const before = await this.readBoot(progress('check'));
    if (current && differs(before, current)) {
      throw new WriteCheckError('Boot partition no longer contains the backup checked by this page. Nothing was written.', false);
    }
    const data = new Uint8Array(length);
    data.set(image);
    const expected = before.slice();
    expected.set(data);
    if (!differs(before, expected)) return { before, after: before, written: false };
    this.touched = true;
    const after = await this.#channel.exclusive(async () => {
      await this.#program(boot.start, data, progress('write'));
      return this.#channel.readSectors(boot.start, boot.sectors, progress('verify'));
    });
    if (differs(after, expected)) throw new WriteCheckError('Boot partition read-back doesn\'t match what was written.', true);
    return { before, after, written: true };
  }

  async reset() {
    this.#ready();
    await this.#channel.reset();
  }
}
