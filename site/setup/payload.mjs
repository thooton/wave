// See spec/setup-payload.md. Never include user-entered text in errors.
const SERVER = /^https:\/\/[^/?#@\\\s]+(?:\/[^?#\\\s]*)?$/;
const TOKEN = /^[!-~]{1,500}$/;
const APP_ID = /^[a-z][a-z0-9]{0,29}$/;
const BYTES = 8192;

function matches(pattern, text) {
  return new RegExp('^(?:' + pattern + ')(?![\\s\\S])').test(text);
}

function field(values, key) {
  if (!Object.hasOwn(values, key)) return '';
  if (typeof values[key] !== 'string') throw new Error('All settings must be text.');
  return values[key].trim();
}

function direct(descriptor, values) {
  const fields = new Map(descriptor.settings.map(setting => [setting.name, setting]));
  if (Object.keys(values).some(key => !fields.has(key))) throw new Error('Unknown setting.');
  const settings = Object.create(null);
  for (const [key, spec] of fields) {
    const value = field(values, key);
    if (!value && spec.required) throw new Error(`${spec.label} is required.`);
    if (!value) continue;
    if (value.length > (spec.max ?? 2000)) throw new Error(`${spec.label} is too long.`);
    if (spec.pattern && !matches(spec.pattern, value)) {
      throw new Error(`${spec.label} does not match the expected format.`);
    }
    settings[key] = value;
  }
  for (const { label, sets } of descriptor.alternatives ?? []) {
    const complete = sets.filter(set => set.every(key => Object.hasOwn(settings, key)));
    const touched = sets.filter(set => set.some(key => Object.hasOwn(settings, key)));
    if (complete.length !== 1 || touched.length !== 1) {
      const choices = sets.map(set => set.map(key => fields.get(key).label).join(' + ')).join('; ');
      throw new Error(`${label}: give exactly one of: ${choices}.`);
    }
  }
  return settings;
}

function isServerAddress(server) {
  // The phone parses this with java.net.URI, which is stricter than WHATWG URL.
  if (/%(?![0-9A-Fa-f]{2})|[\x00-\x20\x7f"<>^`{|}]/.test(server)) return false;
  const authority = server.slice('https://'.length).split('/')[0];
  const ipv6WithPort = /^\[[0-9a-fA-F:.]+\](?::[0-9]+)?$/;
  const dnsWithPort = /^(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\.)*[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\.?(?::[0-9]+)?$/;
  return (dnsWithPort.test(authority) || ipv6WithPort.test(authority)) && URL.canParse(server);
}

function delta(descriptor, values) {
  if (!descriptor.delta.supported) throw new Error('Managed setup is not supported for this app.');
  const server = field(values, 'server');
  const token = field(values, 'token');
  if (server.length > 300 || !SERVER.test(server) || !isServerAddress(server)) {
    throw new Error('Enter an HTTPS server address without a login, query or fragment.');
  }
  if (!TOKEN.test(token)) throw new Error('Enter a token of up to 500 printable ASCII characters, without spaces.');
  return { server, token };
}

export function makeSetupFile(descriptor, backend, values, label = '') {
  if (descriptor.format !== 1 || !APP_ID.test(descriptor.app)) throw new Error('Unsupported app descriptor.');
  if (backend !== 'settings' && backend !== 'delta') throw new Error('Unsupported setup mode.');
  const payload = { wave: 1, app: descriptor.app };
  if ((label = label.trim())) {
    if (label.length > 60) throw new Error('The setup label is too long.');
    payload.label = label;
  }
  payload[backend] = backend === 'delta' ? delta(descriptor, values) : direct(descriptor, values);
  const text = JSON.stringify(payload);
  if (new TextEncoder().encode(text).length > BYTES) throw new Error('The setup file exceeds 8 KB.');
  return { filename: `wave-setup-${descriptor.app}.json`, text };
}
