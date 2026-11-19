import { sha256 } from '../../boot-image.mjs';
import { FirehoseBootReader } from '../../firehose.mjs';
import { checkProgrammer, startReadOnlyProgrammer } from '../../sahara.mjs';
import { claimBulk, pickDevice } from '../../webusb.mjs';

const filter = { vendorId: 0x05c6, productId: 0x9008, classCode: 0xff, subclassCode: 0xff, protocolCode: 0xff };
const $ = id => document.getElementById(id);
const status = $('status');
const button = $('read');
const download = $('download');
const note = text => { status.textContent += '\n' + text; };
let imageUrl;

async function selectDevice() {
  if (new URL(location.href).searchParams.get('policy') !== '1') return pickDevice([filter]);
  // Hardware QA only. Managed USB permission is scoped to the target ID and
  // the origin of this isolated page; exactly one device must be present.
  const matches = (await navigator.usb.getDevices()).filter(d => d.vendorId === filter.vendorId && d.productId === filter.productId);
  if (matches.length !== 1) throw new Error('Expected exactly one EDL device permitted by the local test policy.');
  return matches[0];
}

if (isSecureContext && navigator.usb) {
  button.disabled = false;
  status.textContent = 'Ready. Use only the reserved phone and its verified programmer.';
}
button.addEventListener('click', async () => {
  let link, client, ready = false, reset = false;
  button.disabled = true;
  delete status.dataset.done;
  download.hidden = true;
  if (imageUrl) URL.revokeObjectURL(imageUrl);
  status.textContent = 'Checking inputs…';
  try {
    const file = $('loader').files[0];
    const expected = $('expected').value.trim().toLowerCase();
    if (!file || file.size !== 285524) throw new Error('Select the XP3800 service programmer.');
    if (expected && !/^[a-f0-9]{64}$/.test(expected)) throw new Error('Expected SHA-256 must have 64 hexadecimal characters.');
    // Invoke picker synchronously from the click; do not claim USB before
    // validating the data we are about to send to the device.
    const device = await selectDevice();
    note(`USB device selected via ${new URL(location.href).searchParams.get('policy') === '1' ? 'managed policy' : 'chooser'}.`);
    const loader = new Uint8Array(await file.arrayBuffer());
    await checkProgrammer(loader);
    link = await claimBulk(device, { filter, timeoutMs: 5000 });
    note(`Claimed USB interface, packet size ${link.packetSize} bytes.`);
    await startReadOnlyProgrammer(link, loader);
    note('Sahara handoff accepted.');
    client = new FirehoseBootReader(link);
    await client.configure();
    ready = true;
    const gpt = await client.readGpt();
    note(`GPT validated: ${gpt.partitions.length} partitions, boot starts at sector ${gpt.boot.start}.`);
    note(gpt.partitions.map(p => `${p.name}: ${p.start}, ${p.sectors} sectors`).join('\n'));
    const image = await client.readBoot();
    const digest = await sha256(image);
    note(`Boot read: ${image.byteLength} bytes; SHA-256 ${digest}`);
    await client.reset();
    reset = true;
    note('Reset acknowledged.');
    if (expected && digest !== expected) throw new Error('Backup does not match the original SHA-256.');
    imageUrl = URL.createObjectURL(new Blob([image], { type: 'application/octet-stream' }));
    download.href = imageUrl;
    download.hidden = false;
  } catch (error) {
    note(`${error.name}: ${error.message}`);
    if (ready && !reset) {
      try { await client.reset(); reset = true; note('Reset acknowledged.'); } catch { /* closed or out of sync */ }
    }
    if (link && !reset) note('Android reset not confirmed; verify the phone mode before releasing the reservation.');
  } finally {
    await link?.close();
    status.dataset.done = 'true';
    button.disabled = false;
  }
});
