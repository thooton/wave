// Android Debug Bridge AUTH signs an already hashed SHA-1 token. SubtleCrypto
// hashes its input again; we instead construct the PKCS#1 v1.5 DigestInfo and
// use a blinded RSA private operation. The page keeps the key in its own
// IndexedDB, like adb's ~/.android/adbkey, so "Always allow" survives reloads.
const encoder = new TextEncoder();
const radix = 1n << 32n;
const width = 256;
const sha1Info = [0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03,
  0x02, 0x1a, 0x05, 0x00, 0x04, 0x14];

function integer(bytes) {
  let out = 0n;
  for (const byte of bytes) out = (out << 8n) | BigInt(byte);
  return out;
}

function component(value) {
  return integer(Uint8Array.from(atob(value.replaceAll('-', '+').replaceAll('_', '/')), c => c.charCodeAt(0)));
}

function littleEndian(value, bytes) {
  for (let i = 0; i < bytes.length; i++, value >>= 8n) bytes[i] = Number(value & 255n);
}

function inverse(a, modulus) {
  let old = modulus, coef = 1n, oldCoef = 0n;
  while (a) {
    const quotient = old / a;
    [old, a] = [a, old % a];
    [oldCoef, coef] = [coef, oldCoef - quotient * coef];
  }
  if (old !== 1n) throw new Error('RSA inverse does not exist.');
  return (oldCoef % modulus + modulus) % modulus;
}

function power(base, exponent, modulus) {
  let out = 1n;
  for (; exponent; exponent >>= 1n, base = base * base % modulus) {
    if (exponent & 1n) out = out * base % modulus;
  }
  return out;
}

// A new private key as JWK (n, e, d), for adbCredential and for storing.
export async function newAdbKey() {
  const key = await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-1' }, true, ['sign', 'verify']);
  const { n, e, d } = await crypto.subtle.exportKey('jwk', key.privateKey);
  return { n, e, d };
}

export async function newAdbCredential() {
  return adbCredential(await newAdbKey());
}

// Separate constructor for interoperability tests (never import desktop keys on the page).
export function adbCredential(jwk) {
  const [n, e, d] = ['n', 'e', 'd'].map(name => component(jwk[name]));
  if ((n >> 2047n) !== 1n || e !== 65537n) throw new Error('Expected a 2048-bit ADB key.');
  const structure = new Uint8Array(4 + 4 + width + width + 4);
  const metadata = new DataView(structure.buffer);
  metadata.setUint32(0, width / 4, true);
  metadata.setUint32(4, Number(radix - inverse(n % radix, radix)), true);
  littleEndian(n, structure.subarray(8, 8 + width));
  littleEndian((1n << 4096n) % n, structure.subarray(8 + width, 8 + 2 * width));
  metadata.setUint32(8 + 2 * width, Number(e), true);
  return {
    publicKey: encoder.encode(`${btoa(String.fromCharCode(...structure))} wave-browser@webusb\0`),
    sign(token) {
      if (token.length !== 20) throw new Error('Invalid ADB auth token size.');
      const padded = new Uint8Array(width).fill(255);
      padded[0] = 0;
      padded[1] = 1;
      padded[width - token.length - sha1Info.length - 1] = 0;
      padded.set(sha1Info, width - token.length - sha1Info.length);
      padded.set(token, width - token.length);
      const m = integer(padded);
      const r = 2n + integer(crypto.getRandomValues(new Uint8Array(width))) % (n - 2n);
      const blinded = m * power(r, e, n) % n;
      const signed = power(blinded, d, n) * inverse(r, n) % n;
      if (power(signed, e, n) !== m) throw new Error('ADB auth signing failed.');
      const signature = new Uint8Array(width);
      littleEndian(signed, signature);
      return signature.reverse();
    },
  };
}
