import test from 'node:test';
import assert from 'node:assert/strict';
import { descriptors } from '../setup/descriptors.mjs';
import { makeSetupFile } from '../setup/payload.mjs';

const catalog = Object.fromEntries(descriptors.map(d => [d.app, d]));
const fake = 'A'.repeat(32);
const direct = {
  authenticator: {},
  canvas: { baseUrl: 'https://study.example.org', accessToken: fake },
  charge: { refreshToken: fake },
  flashcards: {},
  mail: { email: 'reader@example.org', clientId: '123-test.apps.googleusercontent.com',
    clientSecret: fake, refreshToken: fake },
  places: { key: fake },
};

test('each of the six checked-in descriptors generates the right setup payload and filename', () => {
  assert.deepEqual(Object.keys(catalog), Object.keys(direct));
  for (const [app, descriptor] of Object.entries(catalog)) {
    const { filename, text } = makeSetupFile(descriptor, 'settings', direct[app]);
    assert.equal(filename, `wave-setup-${app}.json`);
    assert.deepEqual(JSON.parse(text), { wave: 1, app, settings: direct[app] });
    if (descriptor.delta.supported) {
      const delta = { server: 'https://setup.example.org:443/relay', token: fake };
      const result = makeSetupFile(descriptor, 'delta', delta, '  My phone  ');
      assert.deepEqual(JSON.parse(result.text), { wave: 1, app, delta, label: 'My phone' });
    }
  }
});

test('trims all values and drops empty optional settings rather than serializing defaults', () => {
  assert.deepEqual(JSON.parse(makeSetupFile(catalog.charge, 'settings', {
    refreshToken: `  ${fake}  `, country: ' '
  }, ' ').text).settings, { refreshToken: fake });
  assert.throws(() => makeSetupFile(catalog.places, 'settings', { key: ' ' }), /required/);
  assert.throws(() => makeSetupFile(catalog.places, 'settings', { key: fake, extra: '' }), /Unknown/);
  assert.throws(() => makeSetupFile(catalog.places, 'settings', { key: 123 }), /text/);
  assert.throws(() => makeSetupFile(catalog.places, 'settings', { key: fake }, '😊'.repeat(31)), /label/);
});

test('requires precisely one complete Canvas authentication alternative', () => {
  const base = { baseUrl: 'https://study.example.org' };
  const cookie = { cookieUrl: 'https://cookie.example.org/cookie', cookieToken: fake };
  assert.throws(() => makeSetupFile(catalog.canvas, 'settings', base), /exactly one/);
  assert.throws(() => makeSetupFile(catalog.canvas, 'settings', { ...base, cookieToken: fake }), /exactly one/);
  assert.throws(() => makeSetupFile(catalog.canvas, 'settings', { ...base, ...cookie, accessToken: fake }), /exactly one/);
  assert.deepEqual(JSON.parse(makeSetupFile(catalog.canvas, 'settings', { ...base, ...cookie }).text).settings,
    { ...base, ...cookie });
});

test('rejects invalid field formats and enforces the UTF-8 limit before download', () => {
  assert.throws(() => makeSetupFile(catalog.places, 'settings', { key: fake + '!#' }), /expected format/);
  assert.throws(() => makeSetupFile(catalog.places, 'settings', { key: fake.repeat(2) }), /too long/);
  const descriptor = {
    format: 1, app: 'test',
    settings: ['a', 'b', 'c'].map(name => ({ name, label: name, pattern: 'é|x+' }))
  };
  assert.throws(() => makeSetupFile(descriptor, 'settings', { a: 'xxy' }), /expected format/);
  for (const s of descriptor.settings) delete s.pattern;
  const values = Object.fromEntries(descriptor.settings.map(s => [s.name, '☃'.repeat(1000)]));
  assert.throws(() => makeSetupFile(descriptor, 'settings', values), /8 KB/);
});

test('managed setup is allowed only for supported apps and well-formed credentials', () => {
  assert.throws(() => makeSetupFile(catalog.charge, 'delta', { server: 'https://example.org', token: fake }), /not supported/);
  for (const server of ['http://example.org', 'https://user:password@example.org',
    'https://example.org/?key=123', 'https://example.org/#tag', 'https://example.org:999999',
    'https://example.org/path\\other', 'https://%00']) {
    assert.throws(() => makeSetupFile(catalog.mail, 'delta', { server, token: fake }), /HTTPS server/);
  }
  for (const token of ['A B', 'a☃', 'A'.repeat(501), '']) {
    assert.throws(() => makeSetupFile(catalog.mail, 'delta', { server: 'https://example.org', token }), /token/);
  }
});


test('descriptors that require a managed server never generate a direct payload', () => {
  const descriptor = { format: 1, app: 'test', direct: false, settings: [], delta: { supported: true } };
  assert.throws(() => makeSetupFile(descriptor, 'settings', {}), /requires managed setup/);
  const delta = { server: 'https://test.example.org', token: fake };
  assert.deepEqual(JSON.parse(makeSetupFile(descriptor, 'delta', delta).text), { wave: 1, app: 'test', delta });
});
