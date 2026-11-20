// Android boot header v0 and its AVB 1.0 BootSignature (AOSP BootSignature.java),
// which the XP3800's stock boot carries and its bootloader requires. Verified with
// WebCrypto: SHA256withRSA over the payload followed by the DER authenticatedAttributes.
import { bootBytes, sha256 } from './boot-image.mjs';

export const headerBytes = 1632;
// The public AOSP verity test certificate the XP3800's stock boot is signed with.
export const xp3800BootCertSha256 = '8ad127abae8285b582ea36745f220ab8fe397ffb3b068df19ca22d122c7b3b86';
const signature = (bytes, text) => [...text].every((c, i) => c.charCodeAt(0) === bytes[i]);
const fields = b => new DataView(b.buffer, b.byteOffset, b.byteLength);
const same = (a, b) => a.length === b.length && a.every((byte, i) => byte === b[i]);

export function parseBootHeader(bytes) {
  if (bytes.length < headerBytes || !signature(bytes, 'ANDROID!')) throw new Error('Not an Android boot image (no ANDROID! header).');
  const h = fields(bytes), word = i => h.getUint32(8 + i * 4, true);
  const [kernel, kernelAddr, ramdisk, ramdiskAddr, second, secondAddr, tagsAddr, page, dt, osVersion] = Array.from({ length: 10 }, (_, i) => word(i));
  if (page !== 2048 || !kernel || !ramdisk) throw new Error('Boot header has an unexpected page size or empty kernel or ramdisk.');
  const round = size => Math.ceil(size / page) * page;
  const payload = page + round(kernel) + round(ramdisk) + round(second) + round(dt);
  if (payload > bootBytes || payload > bytes.length) throw new Error('Boot header component sizes run past the image.');
  return {
    kernel, ramdisk, second, dt, page, payload,
    // Everything the bootloader uses besides the component sizes and the id hash.
    fixed: [kernelAddr, ramdiskAddr, secondAddr, tagsAddr, osVersion],
    text: bytes.slice(48, 576), extra: bytes.slice(608, headerBytes),
  };
}

function der(bytes, at, tag) {
  if (at + 2 > bytes.length || (tag !== undefined && bytes[at] !== tag)) throw new Error('Malformed boot signature.');
  let length = bytes[at + 1], start = at + 2;
  if (length & 0x80) {
    const count = length & 0x7f;
    if (!count || count > 3) throw new Error('Malformed boot signature length.');
    length = 0;
    for (let i = 0; i < count; i++) length = length * 256 + bytes[start + i];
    start += count;
  }
  if (start + length > bytes.length) throw new Error('Boot signature runs past the image.');
  return { tag: bytes[at], at, start, end: start + length };
}
function children(bytes, node) {
  const out = [];
  for (let at = node.start; at < node.end;) {
    const child = der(bytes, at);
    out.push(child);
    at = child.end;
  }
  if (out.length && out.at(-1).end !== node.end) throw new Error('Malformed boot signature.');
  return out;
}
const integer = (bytes, node) => {
  if (node.end - node.start > 7) throw new Error('Boot signature length is too large.');
  return bytes.subarray(node.start, node.end).reduce((value, byte) => value * 256 + byte, 0);
};

// Returns the payload length, the certificate's SHA-256 and where the signature ends.
// Throws unless the signature is structurally a /boot BootSignature over exactly the
// header-computed payload and verifies with its own certificate's RSA key.
export async function verifyBootSignature(image) {
  const header = parseBootHeader(image);
  const blob = image.subarray(header.payload);
  if (blob[0] !== 0x30) throw new Error('The boot image is not signed: no AVB 1.0 boot signature follows its payload. The XP3800 does not boot such an image.');
  const root = der(blob, 0, 0x30);
  const [version, certificate, algorithm, attributes, signed, ...rest] = children(blob, root);
  if (rest.length || version?.tag !== 2 || integer(blob, version) !== 1 || certificate?.tag !== 0x30 || algorithm?.tag !== 0x30
      || attributes?.tag !== 0x30 || signed?.tag !== 4) throw new Error('The boot image does not end in an AVB 1.0 boot signature.');
  const [target, length, ...more] = children(blob, attributes);
  if (more.length || target?.tag !== 0x13 || new TextDecoder().decode(blob.subarray(target.start, target.end)) !== '/boot'
      || length?.tag !== 2 || integer(blob, length) !== header.payload) throw new Error('The boot signature does not cover this image’s /boot payload.');
  const tbs = children(blob, certificate)[0];
  const parts = tbs?.tag === 0x30 ? children(blob, tbs) : [];
  const spki = parts[parts[0]?.tag === 0xa0 ? 6 : 5];
  if (spki?.tag !== 0x30) throw new Error('The boot signature’s certificate has no public key.');
  const key = await crypto.subtle.importKey('spki', blob.slice(spki.at, spki.end), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  const message = new Uint8Array(header.payload + attributes.end - attributes.at);
  message.set(image.subarray(0, header.payload));
  message.set(blob.subarray(attributes.at, attributes.end), header.payload);
  if (!await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, blob.slice(signed.start, signed.end), message)) {
    throw new Error('The boot signature does not verify.');
  }
  return { header, payload: header.payload, end: header.payload + root.end, certSha256: await sha256(blob.slice(certificate.at, certificate.end)) };
}

// A Magisk-patched image may be written only if it is this stock image patched:
// same header fields and signer, a different ramdisk, signed over exactly its
// payload, and nothing after the signature.
export async function checkPatchedBoot(patched, stock, { certSha256 = xp3800BootCertSha256 } = {}) {
  if (patched.length >= bootBytes) throw new Error('A patched image must be shorter than the 32 MiB partition.');
  const original = await verifyBootSignature(stock);
  if (original.certSha256 !== certSha256) throw new Error('The stock image is not signed with the XP3800’s boot certificate.');
  const result = await verifyBootSignature(patched);
  if (result.end !== patched.length) throw new Error('The patched image has data after its boot signature.');
  if (result.certSha256 !== original.certSha256) throw new Error('The patched image is signed with a different certificate from stock.');
  const a = original.header, b = result.header;
  if (!same(a.fixed, b.fixed) || !same(a.text, b.text) || !same(a.extra, b.extra) || a.second !== b.second || a.dt !== b.dt) {
    throw new Error('The patched image’s boot header does not match the stock image’s.');
  }
  const at = header => header.page + Math.ceil(header.kernel / header.page) * header.page;
  if (a.ramdisk === b.ramdisk && same(stock.subarray(at(a), at(a) + a.ramdisk), patched.subarray(at(b), at(b) + b.ramdisk))) {
    throw new Error('The patched image has the stock ramdisk; Magisk did not patch it.');
  }
  return result;
}
