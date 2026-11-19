import { descriptors } from './descriptors.mjs';
import { makeSetupFile } from './payload.mjs';

const byId = id => document.getElementById(id);
const form = byId('generator');
const app = byId('app');
const managed = byId('use-managed');
const fields = byId('settings');
const status = byId('status');
const catalog = new Map(descriptors.map(descriptor => [descriptor.app, descriptor]));
let backend;
let current = new Map();
let downloadUrl;
let revokeTimer;

function node(tag, text) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  return element;
}

function report(text = '', error = false) {
  status.textContent = text;
  status.toggleAttribute('data-error', error);
}

function clearFields() {
  for (const input of current.values()) input.value = '';
  byId('nickname').value = '';
  report();
}

function revokeDownload() {
  clearTimeout(revokeTimer);
  if (downloadUrl) URL.revokeObjectURL(downloadUrl);
  downloadUrl = null;
}

function addField(target, spec) {
  const input = node('input');
  input.id = `setting-${spec.name}`;
  input.type = spec.secret ? 'password' : 'text';
  input.required = Boolean(spec.required);
  input.autocomplete = spec.secret ? 'new-password' : 'off';
  input.spellcheck = false;
  input.autocapitalize = 'off';
  const label = node('label', spec.label);
  label.htmlFor = input.id;
  if (!spec.required && !spec.group) label.append(node('span', 'Optional'));
  if (spec.default !== undefined) input.placeholder = `Default: ${spec.default}`;
  else if (spec.example && !spec.secret) input.placeholder = spec.example;
  target.append(label, input);
  if (spec.obtain) {
    const help = node('small', spec.obtain);
    help.id = `${input.id}-help`;
    input.setAttribute('aria-describedby', help.id);
    target.append(help);
  }
  current.set(spec.name, input);
}

function render(resetMode = false) {
  clearFields();
  fields.replaceChildren();
  current = new Map();
  const descriptor = catalog.get(app.value);
  byId('app-fields').hidden = !descriptor;
  byId('folder-wrap').hidden = !descriptor;
  byId('about').textContent = descriptor?.about ?? '';
  if (!descriptor) return;
  if (resetMode) {
    byId('managed').open = false;
    managed.checked = false;
  }
  byId('managed').hidden = !descriptor.delta.supported;
  backend = descriptor.delta.supported && managed.checked ? 'delta' : 'settings';
  byId('folder').textContent = `Android/data/${descriptor.package}/files/`;
  const noFile = backend === 'settings' && descriptor.settings.length === 0;
  byId('output').hidden = noFile;
  const limits = byId('limits');
  limits.replaceChildren();
  if (backend === 'delta') {
    if (descriptor.delta.about) {
      const paragraph = node('p', descriptor.delta.about);
      paragraph.className = 'setting-help';
      fields.append(paragraph);
    }
    addField(fields, { name: 'server', label: 'Server address', required: true,
      example: 'https://example.com', obtain: 'The HTTPS address supplied by your command center administrator.' });
    addField(fields, { name: 'token', label: 'Access token', required: true, secret: true,
      obtain: 'Use the token supplied for your own managed account.' });
  } else {
    if (noFile) {
      const paragraph = node('p', 'No setup file is needed. Choose “On this phone only” in the app.');
      paragraph.className = 'setting-help';
      fields.append(paragraph);
    }
    const groups = new Map();
    for (const spec of descriptor.settings) {
      let target = fields;
      if (spec.group) {
        target = groups.get(spec.group);
        if (!target) {
          target = node('fieldset');
          target.append(node('legend', spec.group));
          fields.append(target);
          groups.set(spec.group, target);
        }
      }
      addField(target, spec);
    }
    for (const text of descriptor.limits ?? []) limits.append(node('li', text));
  }
  limits.hidden = !limits.childElementCount;
}

form.addEventListener('submit', event => {
  event.preventDefault();
  const descriptor = catalog.get(app.value);
  if (!descriptor || byId('output').hidden) return;
  let file;
  try {
    const values = Object.fromEntries([...current].map(([key, input]) => [key, input.value]));
    file = makeSetupFile(descriptor, backend, values, byId('nickname').value);
  } catch (error) {
    report(error.message, true);
    return;
  }
  revokeDownload();
  downloadUrl = URL.createObjectURL(new Blob([file.text], { type: 'application/json' }));
  const link = node('a');
  link.download = file.filename;
  link.href = downloadUrl;
  document.body.append(link);
  link.click();
  link.remove();
  revokeTimer = setTimeout(revokeDownload, 10000);
  clearFields();
  report(`Check your downloads for ${file.filename}. The values on this page have been cleared.`);
});

app.addEventListener('change', () => render(true));
managed.addEventListener('change', () => render());
byId('clear').addEventListener('click', clearFields);
// Clear anything left in the DOM before it can enter the back/forward cache.
window.addEventListener('pagehide', () => { clearFields(); revokeDownload(); });
for (const descriptor of descriptors) app.add(new Option(descriptor.name, descriptor.app));
render(true);
byId('loading').hidden = true;
form.hidden = false;
