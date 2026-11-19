// XP3800 USB transports. Only the selected vendor-specific bulk interface is claimed.
export const SONIM_ADB_FILTER = { vendorId: 0x1d9c, classCode: 0xff, subclassCode: 0x42, protocolCode: 1 };

export function pickDevice(filters) {
  if (!globalThis.isSecureContext || !globalThis.navigator?.usb) {
    throw new Error('WebUSB needs a secure context and a supported browser. See the USB guide.');
  }
  // Invoke directly inside the button click; waiting here can consume user activation.
  return navigator.usb.requestDevice({ filters });
}

export async function claimBulk(device, { filter, timeoutMs = 15000, blockSize = 16384 }) {
  if (device.vendorId !== filter.vendorId || (filter.productId !== undefined && device.productId !== filter.productId)) {
    throw new Error('Unexpected USB vendor or product ID.');
  }
  await device.open();
  try {
    if (!device.configuration) await device.selectConfiguration(device.configurations[0].configurationValue);
    const candidates = [];
    for (const iface of device.configuration.interfaces) {
      for (const alt of iface.alternates) {
        if (alt.interfaceClass !== (filter.classCode ?? 0xff)
            || (filter.subclassCode !== undefined && alt.interfaceSubclass !== filter.subclassCode)
            || (filter.protocolCode !== undefined && alt.interfaceProtocol !== filter.protocolCode)) continue;
        const inputs = alt.endpoints.filter(ep => ep.type === 'bulk' && ep.direction === 'in');
        const outputs = alt.endpoints.filter(ep => ep.type === 'bulk' && ep.direction === 'out');
        if (inputs.length === 1 && outputs.length === 1) {
          candidates.push({ iface, alt, input: inputs[0], output: outputs[0] });
        }
      }
    }
    if (candidates.length !== 1) throw new Error('Could not uniquely identify the USB debugging or service interface.');
    const { iface, alt, input, output } = candidates[0];
    await device.claimInterface(iface.interfaceNumber);
    if (iface.alternate.alternateSetting !== alt.alternateSetting) {
      await device.selectAlternateInterface(iface.interfaceNumber, alt.alternateSetting);
    }

    let closed = false;
    async function close() {
      if (closed) return;
      closed = true;
      // USB disconnection invalidates the handle even if close() rejects.
      if (device.opened) await device.close().catch(() => {});
    }
    async function transfer(job, ms) {
      if (closed) throw new Error('USB connection is closed.');
      let timer;
      try {
        return await Promise.race([
          job(),
          new Promise((_, reject) => {
            timer = setTimeout(() => reject(new Error('USB transfer timed out. Reconnect before trying again.')), ms);
          }),
        ]);
      } catch (error) {
        // Do not reuse a connection after a partial transaction or timeout.
        void close();
        throw error;
      } finally {
        clearTimeout(timer);
      }
    }
    return {
      close,
      packetSize: input.packetSize,
      async read(length, ms = timeoutMs) {
        const result = await transfer(() => device.transferIn(input.endpointNumber, length), ms);
        if (result.status !== 'ok' || !result.data) {
          void close();
          throw new Error(`USB input failed (${result.status}).`);
        }
        return new Uint8Array(result.data.buffer, result.data.byteOffset, result.data.byteLength);
      },
      async write(bytes, { terminate = false } = {}) {
        for (let offset = 0; offset < bytes.length; offset += blockSize) {
          const part = bytes.subarray(offset, offset + blockSize);
          const result = await transfer(() => device.transferOut(output.endpointNumber, part), timeoutMs);
          if (result.status !== 'ok' || result.bytesWritten !== part.length) {
            void close();
            throw new Error(`USB output incomplete (${result.status}).`);
          }
        }
        if (terminate && bytes.length % output.packetSize === 0) {
          const result = await transfer(() => device.transferOut(output.endpointNumber, new Uint8Array()), timeoutMs);
          if (result.status !== 'ok') {
            void close();
            throw new Error(`USB output termination failed (${result.status}).`);
          }
        }
      },
    };
  } catch (error) {
    await device.close().catch(() => {});
    throw error;
  }
}

// exact() is for length-delimited binary phases. For the last USB request,
// ask for exactly the remaining bytes so it cannot consume the next frame.
export async function exact(link, size, max = 16384, timeoutMs, onProgress) {
  const result = new Uint8Array(size);
  let blanks = 0;
  for (let pos = 0; pos < size;) {
    const chunk = await link.read(Math.min(size - pos, max), timeoutMs);
    if (!chunk.length && ++blanks > 3) throw new Error('USB returned no more data.');
    if (chunk.length > size - pos) throw new Error('USB returned excess data.');
    result.set(chunk, pos);
    pos += chunk.length;
    if (chunk.length) {
      blanks = 0;
      onProgress?.(pos, size);
    }
  }
  return result;
}
