import { $, connectAndroid, isWebUsb, moduleId, problem, show } from './common.mjs';
import { quoteShell } from '../protocols/adb.mjs';

const connect = $('rescue-connect'), status = $('rescue-status'), disable = $('disable');
const reboot = $('reboot'), disconnect = $('rescue-close');
const list = $('module-list'), section = $('rescue-modules');
const selectAll = $('select-all');
let session, busy = false;
const selected = () => [...list.querySelectorAll('input:checked')].map(box => box.value);

function buttons() {
  connect.disabled = !isWebUsb() || !!session || busy;
  disconnect.hidden = !session;
  disconnect.disabled = busy;
  reboot.disabled = busy;
  selectAll.disabled = busy;
  for (const box of list.querySelectorAll('input')) box.disabled = busy;
  disable.disabled = busy || !selected().length;
}

async function close() {
  await session?.close();
  session = undefined;
  section.hidden = true;
  reboot.hidden = true;
  list.replaceChildren();
}

const roots = '/data/adb/modules /data/adb/modules_update';
const listCommand = `for root in ${roots}; do
  [ -d "$root" ] && [ ! -L "$root" ] || continue
  for d in "$root"/*; do
    [ -d "$d" ] && [ ! -L "$d" ] && [ -f "$d/module.prop" ] && [ ! -e "$d/disable" ] || continue
    printf '%s\\n' "\${d##*/}"
  done
done`;

async function asRoot(script) {
  const result = await session.shell('su -c ' + quoteShell('test "$(id -u)" = 0 || exit 13; ' + script), 32768);
  if (result.code !== 0) throw new Error('Root command failed. Check shell authorization and the Magisk app; then reconnect to inspect the current module state.');
  return result.stdout;
}

async function listModules() {
  const output = await asRoot(listCommand);
  const lines = output.trimEnd().split('\n');
  if (output && lines.some(line => !moduleId.test(line))) throw new Error('Unexpected module name. For safety no changes were made by the module listing.');
  const modules = output ? [...new Set(lines)].sort() : [];
  list.replaceChildren();
  for (const module of modules) {
    const label = document.createElement('label');
    label.className = 'toggle';
    const box = document.createElement('input');
    box.type = 'checkbox';
    box.value = module;
    box.addEventListener('change', buttons);
    label.append(box, document.createTextNode(module));
    list.append(label);
  }
  section.hidden = modules.length === 0;
  return modules.length;
}

async function operation(action) {
  if (busy) return;
  busy = true;
  buttons();
  try { await action(); }
  catch (error) { await close(); show(status, problem(error), true); }
  finally { busy = false; buttons(); }
}

connect.addEventListener('click', () => operation(async () => {
  show(status, 'Select the Sonim phone in the USB chooser.');
  session = (await connectAndroid(status)).adb;
  show(status, 'Reading module names. If Magisk asks, authorize Shell on the phone.');
  const count = await listModules();
  show(status, count ? 'Select the modules to disable before restarting.' : 'No enabled modules found.');
  reboot.hidden = false;
}));
selectAll.addEventListener('click', () => {
  for (const box of list.querySelectorAll('input')) box.checked = true;
  buttons();
});
disable.addEventListener('click', () => operation(async () => {
  const ids = selected();
  if (!ids.length || ids.some(id => !moduleId.test(id))) throw new Error('Choose valid modules from the list.');
  show(status, 'Disabling selected modules. Do not disconnect yet.');
  // Work one ID at a time so there is no unbounded shell argument. A later
  // failure can leave earlier modules disabled; re-list before restarting.
  for (const id of ids) {
    await asRoot(`found=0; for root in ${roots}; do
      [ -d "$root" ] && [ ! -L "$root" ] || continue
      d="$root/"${quoteShell(id)}
      [ -d "$d" ] && [ ! -L "$d" ] && [ -f "$d/module.prop" ] || continue
      [ ! -L "$d/disable" ] || exit 14
      if [ ! -f "$d/disable" ]; then
        [ ! -e "$d/disable" ] && touch "$d/disable" || exit 15
      fi
      found=1
    done
    test "$found" = 1`);
  }
  await listModules();
  show(status, `${ids.length} module${ids.length === 1 ? '' : 's'} marked disabled. Restart Android to apply the change.`);
}));
reboot.addEventListener('click', () => operation(async () => {
  await session.reboot();
  await close();
  show(status, 'Restart requested. Wait for Android to finish booting.');
}));
disconnect.addEventListener('click', () => operation(async () => {
  await close();
  show(status, 'Disconnected.');
}));
window.addEventListener('pagehide', () => { void session?.close(); });
buttons();
