// The recovery page: one pane at a time for Rescue (turn off Magisk modules)
// and Stock boot (write the saved stock image back). Those scripts own the
// device work; this one only switches panes.
import './stock.mjs?v=device-copy';
import './rescue.mjs';
import { $, isWebUsb } from './common.mjs';

const steps = [...document.querySelectorAll('[data-step]')];
const actions = [...document.querySelectorAll('[data-actions]')];
const names = steps.map(step => step.dataset.step);
// Links use the pane's id (#step-modules), so the page check can verify them.
const fromHash = () => location.hash.replace(/^#(?:step-)?/, '');
let view = names.includes(fromHash()) ? fromHash() : 'intro';

function render() {
  for (const step of steps) {
    const selected = step.dataset.step === view;
    $(`step-${step.dataset.step}`).hidden = !selected;
    if (selected) step.setAttribute('aria-current', 'step');
    else step.removeAttribute('aria-current');
    step.disabled = selected;
  }
  for (const group of actions) group.hidden = group.dataset.actions !== view;
}
function select(next) {
  view = next;
  history.replaceState(null, '', next === 'intro' ? location.pathname : `#step-${next}`);
  render();
  $(`step-${view}`).querySelector('h1, h2').focus();
}

for (const step of steps) step.addEventListener('click', () => select(step.dataset.step));
for (const button of document.querySelectorAll('[data-go]')) button.addEventListener('click', () => select(button.dataset.go));
window.addEventListener('hashchange', () => { if (names.includes(fromHash())) select(fromHash()); });
$('where').hidden = isWebUsb();
render();
