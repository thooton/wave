import { sha256 } from './boot-image.mjs?v=redesign5';

// The only programmer accepted by this diagnostic client is the known
// Sonim-signed MSM8909 image; no arbitrary image upload or command mode.
const programmerSha256 = 'e6f2dbcfbed3ca72f23b4e1f0a53c510a620108eee2ac6b18a7835f1440e2767';
const programmerSize = 285524;

export async function checkProgrammer(bytes) {
  if (bytes.length !== programmerSize || await sha256(bytes) !== programmerSha256) {
    throw new Error('The recognized Sonim-signed MSM8909 service programmer was not detected. No USB data was sent.');
  }
}

function frame(command, ...words) {
  const bytes = new Uint8Array((2 + words.length) * 4);
  const fields = new DataView(bytes.buffer);
  [command, bytes.length, ...words].forEach((w, i) => fields.setUint32(i * 4, w, true));
  return bytes;
}

async function receive(link) {
  for (let attempts = 0; attempts < 4; attempts++) {
    const packet = await link.read(1024);
    if (!packet.length) continue;
    if (packet.length < 8 || packet.length % 4) throw new Error('Invalid Sahara packet.');
    const view = new DataView(packet.buffer, packet.byteOffset, packet.byteLength);
    const word = i => view.getUint32(i * 4, true);
    if (word(1) !== packet.length) throw new Error('Invalid Sahara packet length.');
    return { command: word(0), length: packet.length, word };
  }
  throw new Error('Sahara sent no packet.');
}

async function saharaExchange(link, image) {
  const hello = await receive(link);
  if (hello.command !== 1 || hello.length !== 48 || hello.word(2) !== 2 || hello.word(3) > 1 || hello.word(5) !== 0) {
    throw new Error('Expected XP3800 Sahara v2 startup hello. Disconnect and check USB mode.');
  }
  await link.write(frame(2, 2, 1, 0, 0, 1, 2, 3, 4, 5, 6));
  for (let count = 0; count < 512; count++) {
    const packet = await receive(link);
    if (packet.command === 4 && packet.length === 16) {
      if (packet.word(2) !== 13 || packet.word(3) !== 0 || count === 0) throw new Error('Sahara rejected the signed programmer.');
      await link.write(frame(5));
      const done = await receive(link);
      if (done.command !== 6 || done.length !== 12 || done.word(2) !== 0) throw new Error('Sahara did not complete the programmer handoff.');
      return;
    }
    if (packet.command !== 3 || packet.length !== 20 || packet.word(2) !== 13) {
      throw new Error('Unexpected Sahara command. Only XP3800 image transfer is supported.');
    }
    const offset = packet.word(3), length = packet.word(4);
    if (!length || length > 16384 || offset + length > image.length) throw new Error('Sahara requested bytes outside the programmer.');
    // The target requested an exact byte count; do not append a ZLP here.
    await link.write(image.subarray(offset, offset + length));
  }
  throw new Error('Too many Sahara requests.');
}

export async function startReadOnlyProgrammer(link, image) {
  await checkProgrammer(image);
  await saharaExchange(link, image);
  // Sahara ACKs before jumping into the programmer. Give its USB loop time
  // to start before sending the first Firehose request.
  await new Promise(resolve => setTimeout(resolve, 300));
}
