// Every screen of Wave as a 240x320 frame, with what each key does there.
// app.html draws them all; app.html?frame=<id> draws one alone for its
// screenshot (shoot.sh). The helpers below are the pieces the app is built
// from, named as brief 07 should name them.

// ---------------------------------------------------------------- data
const ICONS = {
  'com.authenticator.xp3': 'authenticator.svg', 'com.books.xp3': 'books.svg', 'com.canvas.xp3': 'canvas.svg',
  'com.teslacharge.xp3': 'charge.svg', 'com.dictionary.xp3': 'dictionary.svg', 'com.flashcards.xp3': 'flashcards.svg',
  'com.list.xp3': 'list.svg', 'com.mail.xp3': 'mail.svg', 'com.musica.xp3': 'musica.svg', 'com.newyorker.xp3': 'newyorker.png',
  'com.places.xp3': 'places.svg', 'com.print.xp3': 'print.svg', 'com.serve.xp3': 'serve.svg', 'com.video.xp3': 'video.svg',
  'com.weather.xp3': 'weather.svg', 'com.wave.xp3': 'wave.svg', 'wave': 'wave.svg',
};

// state: none | installed | update | off (watchdog) ; inst = version on the phone
const P = {
  auth:   { id: 'com.authenticator.xp3', name: 'Authenticator', sum: 'Two-factor codes, as Authy shows them', v: '1.6.0', state: 'installed' },
  books:  { id: 'com.books.xp3', name: 'Books', sum: 'EPUBs and audiobooks from your library', v: '3.2.1', state: 'installed', size: '1.9 MB',
            date: 'Nov 12, 2026', note: 'Remember the place in each audiobook chapter.' },
  canvas: { id: 'com.canvas.xp3', name: 'Canvas', sum: 'Your school week, due dates and grades', v: '2.0.3', state: 'none' },
  charge: { id: 'com.teslacharge.xp3', name: 'Charge', sum: "Your Tesla's charge, climate and location", v: '2.4.0', inst: '2.3.1', state: 'update',
            date: 'Today', note: 'Set the charge limit from the climate page; show the time to full while charging.' },
  dict:   { id: 'com.dictionary.xp3', name: 'Dictionary', sum: 'Definitions, thesaurus and shorthand, offline', v: '1.9.2', state: 'installed' },
  flash:  { id: 'com.flashcards.xp3', name: 'Flashcards', sum: 'Study your Anki decks', v: '1.3.0', state: 'installed' },
  list:   { id: 'com.list.xp3', name: 'List', sum: 'Lists and reminders that sync', v: '2.2.0', state: 'installed' },
  mail:   { id: 'com.mail.xp3', name: 'Mail', sum: 'Gmail on the keypad, with threads', v: '1.8.0', state: 'none', size: '2.3 MB' },
  musica: { id: 'com.musica.xp3', name: 'Música', sum: 'Find, download and play music', v: '1.5.4', state: 'none' },
  nyer:   { id: 'com.newyorker.xp3', name: 'New Yorker', sum: "Today's stories and the magazine", v: '1.1.0', state: 'installed' },
  places: { id: 'com.places.xp3', name: 'Places', sum: 'Nearby places, hours and phone numbers', v: '1.4.1', state: 'installed' },
  wave:   { id: 'com.wave.xp3', name: 'Wave', sum: 'Apps and system mods for this phone', v: '1.0.0', state: 'installed' },
  msgs:   { id: 'messages_improved', name: 'Messages Improved', sum: 'Group MMS in the right thread, faster receiving', v: '1.2.0', state: 'none', module: 1,
            size: '4.1 MB', date: 'Yesterday', note: 'Prefetch recent messages when the inbox is opened.' },
  launch: { id: 'launcher_improved', name: 'Launcher Improved', sum: 'The home screen with an app drawer and a tray', v: '2.0.1', state: 'installed', module: 1 },
  music:  { id: 'music_improved', name: 'Music Improved', sum: 'The stock Music app, without its crashes', v: '1.0.4', inst: '1.0.2', state: 'update', module: 1,
            date: 'Nov 16, 2026', note: 'Keep the queue when the phone restarts.' },
  sqlite: { id: 'sqlite_upgrade', name: 'SQLite Upgrade', sum: 'A newer SQLite for the system', v: '3.45.1', state: 'none', module: 1, size: '0.9 MB' },
  argon:  { id: 'messages_argon', name: 'Messages (argon)', sum: '', v: '0.9.3', state: 'installed', module: 1 },
  snake:  { id: 'com.mkline.snake', name: 'Snake 97', sum: 'The snake game, as the old Nokias had it', v: '1.1.0', state: 'none' },
  tetrim: { id: 'com.mkline.tetrim', name: 'Tetrim', sum: 'Falling blocks for the d-pad', v: '0.4.0', state: 'none' },
  night:  { id: 'night_theme', name: 'Night Theme', sum: 'Dark system colours', v: '0.2.0', state: 'none' },
};

// The Updater's tiles: iOS system colours, picked by String.hashCode() of the id.
const TILES = ['#007AFF', '#34C759', '#FF9500', '#FF2D55', '#5856D6', '#5AC8FA', '#AF52DE', '#FF3B30'];
function javaHash(s) { let h = 0; for (const c of s) h = (Math.imul(31, h) + c.charCodeAt(0)) | 0; return h; }

function icon(p, size) {
  const id = typeof p === 'string' ? p : p.id;
  const name = typeof p === 'string' ? p : p.name;
  const src = ICONS[id];
  const inner = src ? `<img src="icons/${src}" alt="">`
    : `<div class="tile" style="background:${TILES[(javaHash(id) & 0x7fffffff) % 8]};font-size:${Math.round(size * 0.5)}px">${name[0]}</div>`;
  return `<div class="ic" style="width:${size}px;height:${size}px">${inner}</div>`;
}

// ---------------------------------------------------------------- glyphs
const G = {
  signal: `<svg width="13" height="12" viewBox="0 0 13 12"><path d="M12 0v12H0z" fill="#3C3C3C"/></svg>`,
  battery: `<svg width="8" height="13" viewBox="0 0 8 13"><rect x="2.5" y="0" width="3" height="2" fill="#3C3C3C"/><rect x="0.5" y="1.5" width="7" height="11" rx="1" fill="none" stroke="#3C3C3C"/><rect x="2" y="5" width="4" height="6" fill="#3C3C3C"/></svg>`,
  search: (c = '#8E8E93', s = 13) => `<svg width="${s}" height="${s}" viewBox="0 0 14 14"><circle cx="6" cy="6" r="4.6" fill="none" stroke="${c}" stroke-width="1.7"/><path d="M9.4 9.4l3.4 3.4" stroke="${c}" stroke-width="1.9" stroke-linecap="round"/></svg>`,
  tick: (c = '#fff') => `<svg width="11" height="11" viewBox="0 0 11 11"><path d="M2.3 5.8l2.1 2.1 4.3-4.6" fill="none" stroke="${c}" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>`,
  up: `<svg width="11" height="11" viewBox="0 0 11 11"><path d="M5.5 8.5V2.8M3 5l2.5-2.5L8 5" fill="none" stroke="#fff" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>`,
  folder: `<svg viewBox="0 0 30 30" width="100%" height="100%"><rect width="30" height="30" fill="#8E8E93"/><path d="M6 10.5a1.5 1.5 0 0 1 1.5-1.5h5l2 2h8a1.5 1.5 0 0 1 1.5 1.5v8a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 6 20.5z" fill="#fff"/></svg>`,
  info: (c) => `<svg width="14" height="14" viewBox="0 0 14 14"><circle cx="7" cy="7" r="6.2" fill="${c}"/><rect x="6.2" y="6" width="1.6" height="4.6" rx=".8" fill="#fff"/><circle cx="7" cy="3.9" r="1" fill="#fff"/></svg>`,
  warn: (c) => `<svg width="14" height="14" viewBox="0 0 14 14"><path d="M7 1.2l6.2 11H.8z" fill="${c}" stroke="${c}" stroke-linejoin="round"/><rect x="6.25" y="5" width="1.5" height="4" rx=".7" fill="#fff"/><circle cx="7" cy="10.4" r=".85" fill="#fff"/></svg>`,
  reboot: (c) => `<svg width="14" height="14" viewBox="0 0 14 14"><path d="M11.3 7.4A4.4 4.4 0 1 1 9.6 3.4" fill="none" stroke="${c}" stroke-width="1.6" stroke-linecap="round"/><path d="M8.2 1.6l2.6 1.7-1.7 2.6" fill="none" stroke="${c}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>`,
};

// Tab icons, 18px; the open tab gets the filled form, as iOS's do.
const TAB_ICONS = [
  (on) => `<svg width="22" height="18" viewBox="0 0 22 18"><path d="M2 7c2.5-3.5 5-3.5 7.5 0s5 3.5 7.5 0 3.5-2.5 3.5-2.5" fill="none" stroke="currentColor" stroke-width="${on ? 2.4 : 1.6}" stroke-linecap="round"/><path d="M2 13c2.5-3.5 5-3.5 7.5 0s5 3.5 7.5 0 3.5-2.5 3.5-2.5" fill="none" stroke="currentColor" stroke-width="${on ? 2.4 : 1.6}" stroke-linecap="round" opacity="${on ? 1 : .9}"/></svg>`,
  (on) => on
    ? `<svg width="18" height="18" viewBox="0 0 18 18"><rect x="3" y="1.5" width="12" height="3" rx="1" fill="currentColor" opacity=".55"/><rect x="2" y="5.5" width="14" height="3" rx="1" fill="currentColor" opacity=".8"/><rect x="1" y="9.5" width="16" height="7.5" rx="1.5" fill="currentColor"/></svg>`
    : `<svg width="18" height="18" viewBox="0 0 18 18"><rect x="3.5" y="2" width="11" height="2" rx=".8" fill="none" stroke="currentColor" stroke-width="1.2"/><rect x="2.5" y="6" width="13" height="2" rx=".8" fill="none" stroke="currentColor" stroke-width="1.2"/><rect x="1.5" y="10" width="15" height="6.5" rx="1.3" fill="none" stroke="currentColor" stroke-width="1.3"/></svg>`,
  (on) => on
    ? `<svg width="18" height="18" viewBox="0 0 18 18"><circle cx="9" cy="9" r="8.2" fill="currentColor"/><path d="M9 4.5v8M5.5 9.2L9 12.7l3.5-3.5" fill="none" stroke="#F9F9F9" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>`
    : `<svg width="18" height="18" viewBox="0 0 18 18"><circle cx="9" cy="9" r="7.8" fill="none" stroke="currentColor" stroke-width="1.3"/><path d="M9 4.5v8M5.5 9.2L9 12.7l3.5-3.5" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>`,
  (on) => `<svg width="18" height="18" viewBox="0 0 18 18"><circle cx="7.6" cy="7.6" r="5.6" fill="none" stroke="currentColor" stroke-width="${on ? 2.3 : 1.5}"/><path d="M11.8 11.8l4.4 4.4" stroke="currentColor" stroke-width="${on ? 2.6 : 1.7}" stroke-linecap="round"/></svg>`,
];
const TAB_NAMES = ['Wave', 'Sources', 'Installed', 'Search'];

// The App Store's download ring: a track, the arc, a stop square; or a spinner.
function ring(pct, size = 24) {
  const r = size / 2 - 1.5, c = 2 * Math.PI * r;
  if (pct < 0) return `<svg class="ring" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}"><circle cx="${size / 2}" cy="${size / 2}" r="${r}" fill="none" stroke="#D1D1D6" stroke-width="2"/><circle cx="${size / 2}" cy="${size / 2}" r="${r}" fill="none" stroke="#007AFF" stroke-width="2" stroke-dasharray="${c * .3} ${c}" transform="rotate(-90 ${size / 2} ${size / 2})" stroke-linecap="round"/></svg>`;
  return `<svg class="ring" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}"><circle cx="${size / 2}" cy="${size / 2}" r="${r}" fill="none" stroke="#D1D1D6" stroke-width="2"/><circle cx="${size / 2}" cy="${size / 2}" r="${r}" fill="none" stroke="#007AFF" stroke-width="2" stroke-dasharray="${c * pct / 100} ${c}" transform="rotate(-90 ${size / 2} ${size / 2})"/><rect x="${size / 2 - 3.5}" y="${size / 2 - 3.5}" width="7" height="7" rx="1" fill="#007AFF"/></svg>`;
}
function spinner(size = 14) {
  return `<svg width="${size}" height="${size}" viewBox="0 0 14 14"><circle cx="7" cy="7" r="5.5" fill="none" stroke="#D1D1D6" stroke-width="1.8"/><path d="M7 1.5A5.5 5.5 0 0 1 12.5 7" fill="none" stroke="#8E8E93" stroke-width="1.8" stroke-linecap="round"/></svg>`;
}

// ---------------------------------------------------------------- chrome
function status(mode) {
  return `<div class="status">${mode ? `<span class="mode">${mode}</span>` : ''}${G.signal}${G.battery}</div>`;
}
// b: {title, back, l, r, lOn, rOn, rOff, spin, field}
function bar(b) {
  if (!b) return '';
  let s = '<div class="bar">';
  if (b.field !== undefined) {
    s += `<div class="field${b.fieldOn ? ' on' : ''}">${G.search(b.fieldOn ? '#007AFF' : '#8E8E93')}${b.field || '<span class="ph">Names and descriptions</span>'}${b.fieldOn && !b.field ? '' : ''}</div>`;
    return s + '</div>';
  }
  // Back's target, named as on iOS; a name too long for its side becomes "Back".
  const back = b.back && (b.back.length > 9 ? 'Back' : b.back);
  if (back) s += `<div class="back">${back}</div>`;
  if (b.spin) s += `<div class="spin">${spinner()}</div>`;
  if (b.l) s += `<div class="act l${b.lOn ? ' on' : ''}">${b.l}</div>`;
  // The title is centred on the screen, clear of whatever is on its sides.
  const side = back ? (back.length <= 5 ? 58 : 76) : (b.l || b.r || b.spin ? 62 : 10);
  if (b.title) s += `<div class="title" style="left:${side}px;right:${side}px">${b.title}</div>`;
  if (b.r) s += `<div class="act r${b.rOn ? ' on' : ''}${b.rOff ? ' off' : ''}">${b.r}</div>`;
  return s + '</div>';
}
function tabbar(active, badge) {
  return '<div class="tabbar">' + TAB_NAMES.map((n, i) =>
    `<div class="tab${i === active ? ' on' : ''}">${TAB_ICONS[i](i === active)}${n}${i === 2 && badge ? `<span class="badge">${badge}</span>` : ''}</div>`).join('') + '</div>';
}
// f: {tab, badge, bar, page, grouped, scroll, over, mode, foot}
function screen(f) {
  const tabs = f.tab !== undefined && f.tab !== null;
  let page = f.page || '';
  if (f.scroll) page = `<div class="scroll" style="top:${-f.scroll}px">${page}</div>`;
  const pageStyle = f.pageBottom !== undefined ? ` style="bottom:${f.pageBottom}px"` : (f.bar ? '' : ' style="top:24px"');
  return `<div class="frame${tabs ? ' tabs' : ''}">${status(f.mode)}${bar(f.bar)}` +
    `<div class="page${f.grouped ? ' grouped' : ''}"${pageStyle}>${page}</div>` +
    (f.foot || '') + (tabs ? tabbar(f.tab, f.badge) : '') + (f.over || '') + '</div>';
}

// ---------------------------------------------------------------- rows
function badgeFor(p) {
  if (p.state === 'update') return `<span class="bdg upd">${G.up}</span>`;
  if (p.state === 'installed' || p.state === 'off') return `<span class="bdg inst">${G.tick()}</span>`;
  return '';
}
// A package as it is browsed (a section, All Packages, search, Featured):
// OK opens its details, so it ends in a chevron, and its state is a badge.
function browseRow(p, o = {}) {
  return `<div class="row pkg${o.f ? ' f' : ''}${o.last ? ' last' : ''}${o.dim ? ' dim' : ''}">` +
    `<div class="icw">${icon(p, 32)}${badgeFor(p)}</div>` +
    `<div class="tx"><div class="nm">${o.name || p.name}</div><div class="sb${o.subc ? ' ' + o.subc : ''}">${o.sub !== undefined ? o.sub : p.sum}</div></div>` +
    `<div class="chev"></div></div>`;
}
function capOf(p, o) {
  if (o.ring !== undefined) return ring(o.ring);
  // A mod can't be opened, nor can Wave open itself: those rows open their details.
  const word = o.cap || (p.state === 'update' ? 'UPDATE' : p.state === 'none' ? 'GET' : (p.module || p.id === 'com.wave.xp3') ? null : 'OPEN');
  if (!word) return '<div class="chev"></div>';
  return `<div class="cap${o.f ? ' press' : ''}">${word}</div>`;
}
// The Updater's row, for Installed: OK presses the capsule, which fills.
function updRow(p, o = {}) {
  return `<div class="urow${o.f ? ' f' : ''}${o.last ? ' last' : ''}"><div class="top">${icon(p, 40)}` +
    `<div class="tx"><div class="nm">${p.name}</div><div class="sb${o.subc ? ' ' + o.subc : ''}">${o.sub || p.date || 'Version ' + p.v}</div></div>` +
    capOf(p, o) + `</div>${p.note && !o.nonotes ? `<div class="notes">${p.note}</div>` : ''}</div>`;
}
// An installed package with nothing new: the Updater's row without notes.
function instRow(p, o = {}) {
  return `<div class="row pkg${o.f ? ' f' : ''}${o.last ? ' last' : ''}">${icon(p, 32)}` +
    `<div class="tx"><div class="nm">${p.name}</div><div class="sb${o.subc ? ' ' + o.subc : ''}">${o.sub || (p.module ? 'System mod, ' : 'Version ') + p.v}</div></div>` +
    capOf(p, o) + '</div>';
}
function linkRow(name, o = {}) {
  const ic = o.icon ? `<div class="ic" style="width:${o.size || 26}px;height:${o.size || 26}px">${o.icon}</div>` : '';
  return `<div class="row link${o.tall ? ' tall' : ''}${ic ? '' : ' noic'}${o.f ? ' f' : ''}${o.last ? ' last' : ''}">${ic}` +
    `<div class="tx"><div class="nm">${name}</div>${o.sub ? `<div class="sb${o.subc ? ' ' + o.subc : ''}">${o.sub}</div>` : ''}</div>` +
    (o.count !== undefined ? `<div class="count">${o.count}</div>` : '') + (o.right || '<div class="chev"></div>') + '</div>';
}
const img = (f) => `<img src="icons/${f}" alt="">`;
const H = (t, cls = '') => `<div class="h${cls ? ' ' + cls : ''}">${t}</div>`;
function menu(items, top, f = 0) {
  return `<div class="menu" style="top:${top}px">` + items.map((it, i) => {
    const red = it.startsWith('!'); const t = red ? it.slice(1) : it;
    return `<div class="${i === f ? 'f' : ''}${red ? ' red' : ''}">${t}</div>`;
  }).join('') + '</div>';
}
function dialog(o) {
  const buttons = o.buttons.map((b, i) => {
    const red = b.startsWith('!'); const t = red ? b.slice(1) : b;
    return `<span class="${i === o.f ? 'f' : ''}${red ? ' red' : ''}">${t}</span>`;
  }).join('');
  return `<div class="scrim"></div><div class="dialog" style="top:${o.top}px">` +
    (o.head ? `<div class="dh">${o.head.icon}<div><div class="dt">${o.head.title}</div><div class="s">${o.head.sub}</div></div></div>` : '') +
    (o.title ? `<div class="dt">${o.title}</div>` : '') +
    `<div class="db">${o.body}</div><div class="dbs">${buttons}</div></div>`;
}
function fact(label, val, o = {}) {
  return `<div class="row${o.last ? ' last' : ''}"><div class="nm">${label}</div><div class="val${o.blue ? ' blue' : ''}">${val}</div></div>`;
}
const toast = (t, bottom = 48) => `<div class="toast" style="bottom:${bottom}px">${t}</div>`;

// ---------------------------------------------------------------- page parts
const welcome = `<div class="row nr" style="height:58px">${icon('wave', 40)}<div class="tx" style="margin-left:10px"><div class="nm" style="font-size:15px;font-weight:500">Welcome to Wave</div><div class="sb">for the Sonim XP3800</div></div></div>`;
function rebootBanner(f, what = 'Messages Improved') {
  return `<div class="banner${f ? ' f' : ''}">${G.reboot('#007AFF')}<div class="tx"><b>Reboot to Finish</b><span style="color:#8E8E93">${what} starts after a reboot</span></div><div class="cap${f ? ' press' : ''}">REBOOT</div></div>`;
}
function homePage(o = {}) {
  const rootStatus = o.noRoot
    ? linkRow('Root access not granted', { icon: G.warn('#FF9500'), size: 14, f: true })
    : `<div class="row link"><div style="width:14px">${G.tick('#248A3D')}</div><div class="tx"><div class="nm">Root access granted</div></div></div>`;
  return rootStatus + (o.reboot ? rebootBanner(o.focus === 'reboot') : '') + welcome +
    H('This Phone') +
    `<div class="facts">` +
    fact('Model', 'XP3800') + fact('Android', '8.1, build 8A.0.5-11') + fact('Root', o.noRoot ? 'Not granted' : 'Magisk 24.0') + fact('Storage', '1.3 GB free', { last: true }) +
    `</div>` + H('Community') +
    `<div class="facts">` + fact('Code', 'github.com/thooton/wave', { blue: false }) + fact('Website', 'thooton.github.io/wave', { last: true }) + `</div><div class="gap"></div>`;
}
function sourcesPage(o = {}) {
  return linkRow('All Sources', { icon: G.folder, size: 30, tall: true, sub: 'Every package below', f: o.focus === 'all' }) +
    linkRow('Wave', { icon: img('wave.svg'), size: 30, tall: true, sub: o.refreshing ? 'Refreshing…' : 'github.com/thooton/wave_repository', f: o.focus === 'wave',
      right: o.refreshing ? `<div style="margin-right:2px">${spinner()}</div>` : undefined }) +
    linkRow('XP3 Games', { icon: icon({ id: 'xp3-games', name: 'XP3 Games' }, 30).replace(/^<div class="ic"[^>]*>|<\/div>$/g, ''), size: 30, tall: true, sub: 'github.com/mkline/xp3-games', f: o.focus === 'games' }) +
    (o.noTweaks ? '' : linkRow('Sonim Tweaks', { icon: icon({ id: 'sonim-tweaks', name: 'Sonim Tweaks' }, 30).replace(/^<div class="ic"[^>]*>|<\/div>$/g, ''), size: 30, tall: true,
      sub: o.tweaksErr ? 'gitlab.com answered 404' : 'gitlab.com/rpeters/sonim-tweaks', subc: o.tweaksErr ? 'red' : '',
      right: o.tweaksErr ? `<div style="margin-right:6px">${G.warn('#FF9500')}</div><div class="chev"></div>` : undefined, f: o.focus === 'tweaks', last: true }));
}
const APPS = [P.auth, P.books, P.canvas, P.charge, P.dict, P.flash, P.list, P.mail, P.musica, P.nyer, P.places];

function detHead(p, o = {}) {
  const word = o.cap;
  return `<div class="det-head">${icon(p, 56)}<div class="tx"><div class="nm">${p.name}</div><div class="sb">${o.by || 'thooton'}</div>` +
    (word ? `<div class="cap${o.press ? ' press' : ''}${o.off ? ' off' : ''}">${word}</div>` : '') +
    (o.after ? `<span class="after">${o.after}</span>` : '') + '</div></div>';
}
const line = (glyph, text, cls = '') => `<div class="det-line ${cls}">${glyph}<div>${text}</div></div>`;

// ---------------------------------------------------------------- the frames
const K = (lr, ud, ok, back, menu) => ({ lr, ud, ok, back, menu });
const TABS_LR = 'Previous / next tab (the tab keeps its place).';
const BACK_TOP = 'Closes Wave (with work queued, sends it to the back and the work carries on).';

const FRAMES = [];
function F(group, id, title, keys, html, note) { FRAMES.push({ group, id, title, keys, html, note }); }

// ---- Wave tab
F('Wave tab', 'wave-home', 'Wave tab: root access granted',
  K(TABS_LR, 'Up/Down scroll to the phone facts and community links.',
    'Nothing.', BACK_TOP, 'Refresh Sources.'),
  screen({ tab: 0, badge: 2, bar: { title: 'Home' }, page: homePage() }),
  'The green check shows when Wave has root. Add a Source is on the Sources tab.');
F('Wave tab', 'wave-home-no-root', 'Wave tab: root access not granted',
  K(TABS_LR, 'Down scrolls to the phone facts and community links.',
    'Opens the root instructions.', BACK_TOP, 'Refresh Sources.'),
  screen({ tab: 0, badge: 2, bar: { title: 'Home' }, page: homePage({ noRoot: true }) }),
  'The orange warning is the first focus stop.');
F('Wave tab', 'wave-home-more', 'Wave tab, scrolled: This Phone and Community',
  K(TABS_LR, 'Up/Down scroll.', 'Nothing.', BACK_TOP, 'Refresh Sources.'),
  screen({ tab: 0, badge: 2, bar: { title: 'Home' }, page: homePage(), scroll: 76 }),
  'The facts and links are text, not focus stops: nothing to press there.');
F('Wave tab', 'wave-home-reboot', 'Wave tab with a reboot pending',
  K(TABS_LR, 'Root is shown first; Reboot is the first focus stop.', 'On REBOOT: opens a confirmation dialog.', BACK_TOP, 'Reboot, Refresh Sources.'),
  screen({ tab: 0, badge: 2, bar: { title: 'Home' }, page: homePage({ reboot: true, focus: 'reboot' }) }),
  'Shown while a module change waits for a reboot. The same action heads Installed.');

// ---- Sources tab
F('Sources tab', 'sources', 'Sources',
  K(TABS_LR, 'Move one source; Up on All Sources goes to Add on the bar.', 'Opens the source (All Sources: every package, by section).', BACK_TOP,
    'Refresh Sources, Add a Source; on a source: Refresh, Copy Address, Remove (not on Wave\'s own).'),
  screen({ tab: 1, badge: 2, bar: { l: 'Refresh', title: 'Sources', r: 'Add' }, page: sourcesPage({ focus: 'wave' }) }));
F('Sources tab', 'sources-bar', 'Sources: Add on the bar',
  K('On the bar: between Refresh and Add.', 'Down: back to the list.', 'Add: opens Add a Source. Refresh: refreshes every source.', BACK_TOP, 'Refresh Sources, Add a Source.'),
  screen({ tab: 1, badge: 2, bar: { l: 'Refresh', title: 'Sources', r: 'Add', rOn: true }, page: sourcesPage({}) }),
  'The bar\'s links are reached with Up from the top row, as Update All is in the Updater.');
F('Sources tab', 'sources-refreshing', 'Sources while refreshing',
  K(TABS_LR, 'As on Sources.', 'As on Sources.', BACK_TOP, 'Add a Source (no Refresh while one runs).'),
  screen({ tab: 1, badge: 2, bar: { spin: true, title: 'Sources', r: 'Add' }, page: sourcesPage({ focus: 'all', refreshing: true }) }),
  'Refresh becomes the spinner on the left; each source shows Refreshing… until its fetch ends.');
F('Sources tab', 'sources-menu', 'Menu on a source',
  K('Nothing while the menu is open.', 'Move in the menu.', 'Does the item. Remove asks first.', 'Closes the menu.', 'Closes the menu.'),
  screen({ tab: 1, badge: 2, bar: { l: 'Refresh', title: 'Sources', r: 'Add' }, page: sourcesPage({ focus: 'games' }),
    over: menu(['Refresh', 'Copy Address', 'Add a Source', '!Remove Source'], 151, 0) }),
  'Over the row it is about, as the Updater\'s menu is.');
F('Sources tab', 'add-source', 'Add a Source: typing the address',
  K('Move the cursor in the address (the field uses them).', 'Down: to Add. Up: nothing.', 'Add: as Down then OK.', 'Deletes a character; on an empty field, back to Sources.',
    'Paste, Clear.'),
  screen({ tab: 1, badge: 2, mode: 'abc', bar: { back: 'Sources', title: 'Add a Source' }, page:
    `<div class="lab">Address</div><div class="urlf">https://github.com/mkline/xp3-ga<span class="t9">m</span><span class="caret"></span></div>` +
    `<div class="fieldnote">GitHub, GitLab, Codeberg or sourcehut</div>` +
    `<div style="height:16px"></div><div class="btn">Add</div>` }),
  'Starts as https://github.com/ with the cursor at the end. Keys 2 to 9 type by multi-tap (the m underlined is still taking taps), 1 types . / - _, # switches abc / 123, * opens the phone\'s symbols.');
F('Sources tab', 'add-source-button', 'Add a Source: on Add',
  K('Nothing.', 'Up: back into the address.', 'Fetches the source, then asks to trust it.', 'Back to Sources.', 'Paste, Clear.'),
  screen({ tab: 1, badge: 2, bar: { back: 'Sources', title: 'Add a Source' }, page:
    `<div class="lab">Address</div><div class="urlf rest">https://github.com/mkline/xp3-games</div>` +
    `<div class="fieldnote">GitHub, GitLab, Codeberg or sourcehut</div>` +
    `<div style="height:16px"></div><div class="btn press">Add</div>` }));
F('Sources tab', 'add-source-fetching', 'Add a Source: fetching it',
  K('Nothing.', 'Nothing.', 'Nothing.', 'Stops the fetch, back to the address.', 'Nothing.'),
  screen({ tab: 1, badge: 2, bar: { back: 'Sources', title: 'Add a Source' }, page:
    `<div class="lab">Address</div><div class="urlf rest">https://github.com/mkline/xp3-games</div>` +
    `<div class="fieldnote">&nbsp;</div><div style="height:16px"></div>` +
    `<div style="display:flex;justify-content:center;align-items:center;gap:7px;height:34px;font-size:13px;color:#8E8E93">${spinner()}Fetching</div>` }),
  'Wave fetches before asking, so the confirm can name what is being trusted.');
F('Sources tab', 'add-source-trust', 'Add a Source: trusting it with root',
  K('Between Cancel and Add Source.', 'Nothing.', 'Add Source: adds it and lists its packages. Cancel: back to the address.', 'Cancel.', 'Nothing.'),
  screen({ tab: 1, badge: 2, bar: { back: 'Sources', title: 'Add a Source' }, page:
    `<div class="lab">Address</div><div class="urlf rest">https://github.com/mkline/xp3-games</div>`,
    over: dialog({ top: 54, head: { icon: icon({ id: 'xp3-games', name: 'XP3 Games' }, 32), title: 'XP3 Games', sub: 'mkline, 12 packages' },
      body: 'Its packages install with root and can change anything on the phone. Add it only if you trust mkline.',
      buttons: ['Cancel', 'Add Source'], f: 1 }) }),
  'Name, maintainer and count come from the fetched wave.json.');
F('Sources tab', 'add-source-bad', 'Add a Source: not a source',
  K('Move the cursor.', 'Down: to Add.', 'Add: tries again.', 'Deletes a character.', 'Paste, Clear.'),
  screen({ tab: 1, badge: 2, mode: 'abc', bar: { back: 'Sources', title: 'Add a Source' }, page:
    `<div class="lab">Address</div><div class="urlf bad">https://github.com/mkline/xp3-game<span class="caret"></span></div>` +
    `<div class="fieldnote red">github.com answered 404: no repository here.</div>` +
    `<div style="height:16px"></div><div class="btn">Add</div>` }),
  'Other reasons, same place: "There\'s no wave.json at the top.", "Only https:// addresses.", "Needs a newer Wave.", and the network errors.');
F('Sources tab', 'source', 'A source: its sections',
  K(TABS_LR, 'Move one row: All Packages, each section, then the source\'s Featured.', 'Opens the section (or the package).', 'Back to Sources.',
    'Refresh, Copy Address, Remove Source.'),
  screen({ tab: 1, badge: 2, bar: { back: 'Sources', title: 'Wave' }, page:
    `<div class="row nr" style="height:52px">${icon('wave', 36)}<div class="tx" style="margin-left:9px"><div class="nm" style="font-weight:500">Wave</div><div class="sb">thooton, refreshed today</div></div></div>` +
    `<div style="height:1px;background:#C8C7CC;margin:0 10px"></div>` +
    linkRow('All Packages', { count: 19 }) + linkRow('Applications', { count: 16, f: true }) + linkRow('System', { count: 3, last: true }) +
    H('Featured') + browseRow(P.msgs) }));
F('Sources tab', 'section', 'A section: its packages',
  K(TABS_LR, 'Move one package. Holding Up/Down pages.', 'Opens the package\'s details.', 'Back to the source.',
    'On the package: Get / Update / Open, Details; Refresh.'),
  screen({ tab: 1, badge: 2, bar: { back: 'Wave', title: 'Applications' }, page:
    APPS.slice(0, 5).map((p, i) => browseRow(p, { f: i === 2 })).join('') }),
  'Alphabetical. Badges: grey tick installed, blue arrow update. No capsule: OK opens, so nothing on the row looks pressed but the row.');
F('Sources tab', 'section-menu', 'Menu on a package in a list',
  K('Nothing.', 'Move in the menu.', 'Get: queues it and opens the confirm.', 'Closes the menu.', 'Closes the menu.'),
  screen({ tab: 1, badge: 2, bar: { back: 'Wave', title: 'Applications' }, page:
    APPS.slice(0, 5).map((p, i) => browseRow(p, { f: i === 2 })).join(''),
    over: menu(['Get', 'Details', 'Refresh'], 153, 0) }),
  'The capsule\'s action without opening the details.');

// ---- details
const detBar = (back) => ({ back, title: 'Details' });
F('Package details', 'details-get', 'Details: not installed',
  K(TABS_LR, 'Scroll the page two lines at a time.', 'Presses GET: queues it and opens the confirm.', 'Back to the list.', 'Get, What\'s New, Copy Address.'),
  screen({ tab: 1, badge: 2, bar: detBar('Applications'), page:
    detHead(P.mail, { cap: 'GET', press: true, after: '2.3 MB' }) +
    `<div class="hair"></div><div class="det-p" style="padding-top:4px">Gmail on the keypad: threads, search, replies and attachments you can open. Bring your own Gmail token with a setup code.</div>` +
    H("What's New", 'sm') + `<div class="det-sub"><span>Version 1.8.0</span><span>Nov 14, 2026</span></div><div class="det-p">Open PDF attachments in Print.</div>` }),
  'The capsule is the page\'s one focus stop and is always pressed-looking; Up/Down only scroll.');
F('Package details', 'details-get-more', 'Details, scrolled: Information',
  K(TABS_LR, 'Scroll.', 'Presses GET (still on the bar-less capsule, now scrolled away; OK still works).', 'Back to the list.', 'Get, What\'s New, Copy Address.'),
  screen({ tab: 1, badge: 2, bar: detBar('Applications'), scroll: 150, page:
    detHead(P.mail, { cap: 'GET', press: true, after: '2.3 MB' }) +
    `<div class="hair"></div><div class="det-p" style="padding-top:4px">Gmail on the keypad: threads, search, replies and attachments you can open. Bring your own Gmail token with a setup code.</div>` +
    H("What's New", 'sm') + `<div class="det-sub"><span>Version 1.8.0</span><span>Nov 14, 2026</span></div><div class="det-p">Open PDF attachments in Print.</div>` +
    H('Information', 'sm') + `<div class="facts">` + fact('Version', '1.8.0') + fact('Size', '2.3 MB') + fact('Source', 'Wave') + fact('Section', 'Applications') +
    fact('Author', 'thooton') + fact('Needs', 'Print 1.2 or later') + fact('Id', 'com.mail.xp3', { last: true }) + `</div>` }),
  'Long pages keep the capsule pressable: it is the target of OK wherever the page is scrolled. Depends and conflicts are lines in Information.');
F('Package details', 'details-installed', 'Details: installed',
  K(TABS_LR, 'Scroll.', 'Presses OPEN: starts the app.', 'Back.', 'Open, Reinstall, Remove, What\'s New.'),
  screen({ tab: 2, badge: 2, bar: detBar('Installed'), page:
    detHead(P.books, { cap: 'OPEN', press: true, after: 'Version 3.2.1' }) +
    `<div class="hair"></div><div class="det-p" style="padding-top:4px">Read EPUBs and listen to audiobooks borrowed from your library, with the place kept in each.</div>` +
    H("What's New", 'sm') + `<div class="det-sub"><span>Version 3.2.1</span><span>Nov 12, 2026</span></div><div class="det-p">Remember the place in each audiobook chapter.</div>` }));
F('Package details', 'details-menu', 'Details: Menu, with Remove',
  K('Nothing.', 'Move in the menu.', 'Remove: queues the removal and opens the confirm.', 'Closes the menu.', 'Closes the menu.'),
  screen({ tab: 2, badge: 2, bar: detBar('Installed'), page:
    detHead(P.books, { cap: 'OPEN', press: true, after: 'Version 3.2.1' }) +
    `<div class="hair"></div><div class="det-p" style="padding-top:4px">Read EPUBs and listen to audiobooks borrowed from your library, with the place kept in each.</div>`,
    over: menu(['Open', 'Reinstall', '!Remove', "What's New"], 92, 2) }),
  'REMOVE is never a capsule; it is here, and goes through the confirm.');
F('Package details', 'details-update', 'Details: update available',
  K(TABS_LR, 'Scroll.', 'Presses UPDATE: queues it and opens the confirm.', 'Back.', 'Update, Open, Remove, What\'s New.'),
  screen({ tab: 2, badge: 2, bar: detBar('Installed'), page:
    detHead(P.charge, { cap: 'UPDATE', press: true, after: '2.3.1 → <span style="color:#007AFF">2.4.0</span>' }) +
    `<div class="hair"></div>` + H("What's New", 'sm') + `<div class="det-sub"><span>Version 2.4.0</span><span>Today</span></div>` +
    `<div class="det-p">Set the charge limit from the climate page; show the time to full while charging.</div>` +
    `<div class="det-sub"><span>Version 2.3.1</span><span>Nov 2, 2026</span></div><div class="det-p">Keep the token after a reboot.</div>` }),
  'What\'s New leads when there is an update; the description follows it.');
F('Package details', 'details-module', 'Details: a system mod',
  K(TABS_LR, 'Scroll.', 'Presses GET: the confirm shows what it replaces and pulls in.', 'Back.', 'Get, What\'s New.'),
  screen({ tab: 1, badge: 2, bar: detBar('System'), page:
    detHead(P.msgs, { cap: 'GET', press: true, after: '4.1 MB' }) +
    `<div class="hair"></div>` +
    line(G.reboot('#007AFF'), 'System mod, needs a reboot') +
    line(G.info('#8E8E93'), 'Replaces Messages (argon)') +
    line(G.info('#8E8E93'), "Not tested on this phone's firmware") +
    `<div class="det-p" style="padding-top:2px">The stock Messages app and its provider, fixed: group MMS land in the right thread.</div>` }),
  'Notes as lines with a glyph: reboot (blue), facts (grey). The firmware line appears only when requires.firmware doesn\'t list this build.');
F('Package details', 'details-unfit', "Details: doesn't suit this phone",
  K(TABS_LR, 'Scroll.', 'Nothing: the capsule is greyed.', 'Back.', "What's New, Copy Address."),
  screen({ tab: 1, badge: 2, bar: detBar('Games'), page:
    detHead(P.tetrim, { cap: 'GET', off: true, by: 'mkline', after: '0.6 MB' }) +
    `<div class="hair"></div>` + line(G.warn('#FF9500'), 'Needs Android 9; this phone has 8.1.', 'bad') +
    `<div class="det-p" style="padding-top:2px">Falling blocks for the d-pad, with a hold piece and a ghost.</div>` }),
  'Greyed with the reason (requires.device or requires.sdk). The package stays listed, dimmed, in its section.');
F('Package details', 'details-newer', 'Details: needs a newer Wave',
  K(TABS_LR, 'Scroll.', 'Nothing while greyed. (If Wave itself has an update, the line reads "Update Wave to install this" and Menu has Update Wave.)', 'Back.', 'Update Wave (when there is one).'),
  screen({ tab: 1, badge: 2, bar: detBar('Themes'), page:
    detHead(P.night, { cap: 'GET', off: true, by: 'rpeters', after: '0.2 MB' }) +
    `<div class="hair"></div>` + line(G.info('#8E8E93'), 'Needs a newer Wave') +
    `<div class="det-p" style="padding-top:2px">Dark system colours, for the settings and the dialer.</div>` }),
  'An unknown type, or requires.wave above this Wave\'s.');

// ---- Installed tab
const instUpdates = (o = {}) =>
  (o.reboot ? rebootBanner(o.focus === 'reboot') : '') +
  H('Updates') + updRow(P.charge, { f: o.focus === 'charge' }) + updRow(P.music, { f: o.focus === 'music', last: true }) +
  H('Up to Date') + instRow(P.auth) + instRow(P.books) + instRow(P.dict) + instRow(P.launch, { f: o.focus === 'launch' }) + instRow(P.wave, { last: true });
F('Installed tab', 'installed', 'Installed: with updates',
  K(TABS_LR, 'Move one package; Up on the top row goes to Update All.', 'Presses the capsule: UPDATE queues it and opens the confirm; OPEN starts the app. A row without one (a mod) opens its details.',
    BACK_TOP, 'Update / Open, Details, Remove, Reinstall, What\'s New, Update All (n), Refresh.'),
  screen({ tab: 2, badge: 2, bar: { title: 'Installed', r: 'Update All' }, page: instUpdates({ focus: 'charge' }) }),
  'The Updater\'s screen: a filled capsule means OK presses it. The badge on the tab counts updates.');
F('Installed tab', 'installed-updateall', 'Installed: on Update All',
  K('Nothing.', 'Down: back to the list.', 'Queues every update and opens the confirm.', BACK_TOP, 'As on a row.'),
  screen({ tab: 2, badge: 2, bar: { title: 'Installed', r: 'Update All', rOn: true }, page: instUpdates({}) }));
F('Installed tab', 'installed-more', 'Installed, scrolled: Up to Date',
  K(TABS_LR, 'Move one package.', 'OPEN starts the app; a mod (no capsule) opens its details.', BACK_TOP, 'Open, Details, Remove, Reinstall, What\'s New.'),
  screen({ tab: 2, badge: 2, bar: { title: 'Installed', r: 'Update All' }, page: instUpdates({ focus: 'launch' }), scroll: 226 }),
  'Up-to-date rows drop the notes; mods show "System mod, version" and a chevron.');
F('Installed tab', 'installed-none', 'Installed: nothing to update',
  K(TABS_LR, 'Move one package.', 'OPEN starts the app; a mod opens its details.', BACK_TOP, 'Open, Details, Remove, Reinstall, What\'s New, Refresh.'),
  screen({ tab: 2, bar: { title: 'Installed' }, page:
    H('Up to Date') + [P.auth, P.books, P.charge, P.dict].map((p, i) => instRow(Object.assign({}, p, { state: 'installed', v: p.v }), { f: i === 0 })).join('') }),
  'No Update All, no heading for Updates, no badge.');
F('Installed tab', 'installed-reboot', 'Installed: reboot pending',
  K(TABS_LR, 'The banner is the top row.', 'REBOOT: reboots now.', BACK_TOP, 'Reboot, Refresh.'),
  screen({ tab: 2, badge: 2, bar: { title: 'Installed', r: 'Update All' }, page: instUpdates({ reboot: true, focus: 'reboot' }) }));
F('Installed tab', 'installed-menu', 'Installed: Menu on a row',
  K('Nothing.', 'Move in the menu.', 'Does the item.', 'Closes the menu.', 'Closes the menu.'),
  screen({ tab: 2, badge: 2, bar: { title: 'Installed', r: 'Update All' }, page: instUpdates({ focus: 'charge' }),
    over: menu(['Update', 'Details', "What's New", '!Remove', 'Update All (2)'], 94, 1) }));

// ---- Search tab
F('Search tab', 'search-empty', 'Search: empty',
  K(TABS_LR, 'Nothing (no results yet).', 'Nothing.', 'On an empty field: closes Wave, as on any tab\'s top.', 'Nothing.'),
  screen({ tab: 3, badge: 2, mode: 'abc', bar: { field: '<span class="caret" style="margin:0 2px 0 0"></span><span class="ph">Names and descriptions</span>', fieldOn: true }, page: '' }),
  'The field takes the bar\'s place and has the keys as soon as the tab opens.');
F('Search tab', 'search-typing', 'Search: typing',
  K(TABS_LR, 'Down: into the results.', 'Into the results (as Down).', 'Deletes a character.', 'Clear.'),
  screen({ tab: 3, badge: 2, mode: 'abc', bar: { field: 'mu<span class="t9">s</span><span class="caret"></span>', fieldOn: true }, page:
    browseRow(P.musica) + browseRow(P.music) + browseRow({ id: 'com.mkline.musicquiz', name: 'Music Quiz', sum: 'Name the song from four notes', state: 'none' }, { last: true }) }),
  'Live: results follow each key, across every source, matching name, summary and description. The cursor stays at the end, so Left/Right stay free for tabs.');
F('Search tab', 'search-results', 'Search: results',
  K(TABS_LR, 'Move one result; Up on the first goes back into the field.', 'Opens the package\'s details.', 'Back into the field.', 'Get / Update / Open, Details.'),
  screen({ tab: 3, badge: 2, bar: { field: 'mus' }, page:
    browseRow(P.musica, { f: true }) + browseRow(P.music) + browseRow({ id: 'com.mkline.musicquiz', name: 'Music Quiz', sum: 'Name the song from four notes', state: 'none' }, { last: true }) }));
F('Search tab', 'search-none', 'Search: nothing found',
  K(TABS_LR, 'Nothing.', 'Nothing.', 'Deletes a character.', 'Clear.'),
  screen({ tab: 3, badge: 2, mode: 'abc', bar: { field: 'xyzzy<span class="caret"></span>', fieldOn: true }, page:
    `<div class="msg"><div class="t">No Results</div></div>` }));

// ---- queue
const confirmPage = `${H('Install', 'sm')}` +
  `<div class="qrow c">${icon(P.msgs, 30)}<div class="tx"><div class="nm">Messages Improved</div><div class="sb">1.2.0, 4.1 MB</div></div></div>` +
  `<div class="qrow c">${icon(P.sqlite, 30)}<div class="tx"><div class="nm">SQLite Upgrade</div><div class="sb blue">Needed by Messages Improved</div></div></div>` +
  H('Remove', 'sm') +
  `<div class="qrow c last">${icon(P.argon, 30)}<div class="tx"><div class="nm">Messages (argon)</div><div class="sb">Replaced by Messages Improved</div></div></div>`;
F('The queue', 'queue-confirm', 'Confirm: the plan',
  K('Between Cancel and Confirm.', 'Scroll the plan when it is longer than the screen.', 'Confirm: starts. Cancel: empties the queue.', 'As Cancel.', 'Nothing.'),
  screen({ bar: { title: 'Confirm' }, page: confirmPage, pageBottom: 77,
    foot: `<div class="foot"><div class="sum">5.0 MB to download, then a reboot</div><div class="two"><div class="btn">Cancel</div><div class="btn press">Confirm</div></div></div>` }),
  'Modal: no tab bar. The dependency and the replaced package are named with why. Focus starts on Confirm. (SQLite Upgrade stands in for any dependency.)');
F('The queue', 'queue-progress', 'Working',
  K('Nothing.', 'Scroll the list.', 'Nothing.', 'Sends Wave to the back; the work carries on.', 'Nothing.'),
  screen({ bar: { title: 'Installing' }, page:
    `<div class="qrow">${icon(P.sqlite, 30)}<div class="tx"><div class="nm">SQLite Upgrade</div><div class="sb" style="color:#248A3D">Installed</div></div>${G.tick('#248A3D')}</div>` +
    `<div class="qrow">${icon(P.msgs, 30)}<div class="tx"><div class="nm">Messages Improved</div><div class="sb blue">Downloading, 2.6 of 4.1 MB</div></div>${ring(63)}</div>` +
    `<div class="qrow last dim">${icon(P.argon, 30)}<div class="tx"><div class="nm">Messages (argon)</div><div class="sb">To remove</div></div>${ring(-1)}</div>` }),
  'One at a time, in the plan\'s order; the line under each says the step (Downloading, Checking, Installing, Removing).');
F('The queue', 'queue-done', 'Done',
  K('Nothing.', 'Scroll the list.', 'Done: back where the queue started.', 'As Done.', 'Nothing.'),
  screen({ bar: { title: 'Done' }, page:
    `<div class="qrow">${icon(P.mail, 30)}<div class="tx"><div class="nm">Mail</div><div class="sb" style="color:#248A3D">Installed 1.8.0</div></div>${G.tick('#248A3D')}</div>` +
    `<div class="qrow last">${icon(P.charge, 30)}<div class="tx"><div class="nm">Charge</div><div class="sb" style="color:#248A3D">Updated to 2.4.0</div></div>${G.tick('#248A3D')}</div>`, pageBottom: 51,
    foot: `<div class="foot"><div class="btn press">Done</div></div>` }));
F('The queue', 'queue-reboot', 'Done: Reboot Now or Later',
  K('Between Later and Reboot Now.', 'Scroll the list.', 'Reboot Now: reboots. Later: back; the Reboot row waits on Wave and Installed.', 'As Later.', 'Nothing.'),
  screen({ bar: { title: 'Done' }, page:
    `<div class="qrow">${icon(P.sqlite, 30)}<div class="tx"><div class="nm">SQLite Upgrade</div><div class="sb" style="color:#248A3D">Installed 3.45.1</div></div>${G.tick('#248A3D')}</div>` +
    `<div class="qrow">${icon(P.msgs, 30)}<div class="tx"><div class="nm">Messages Improved</div><div class="sb" style="color:#248A3D">Installed 1.2.0</div></div>${G.tick('#248A3D')}</div>` +
    `<div class="qrow last">${icon(P.argon, 30)}<div class="tx"><div class="nm">Messages (argon)</div><div class="sb" style="color:#248A3D">Removed</div></div>${G.tick('#248A3D')}</div>`, pageBottom: 77,
    foot: `<div class="foot"><div class="sum">They start after a reboot</div><div class="two"><div class="btn">Later</div><div class="btn press">Reboot Now</div></div></div>` }));

// ---- no root
F('No root', 'no-root', 'No root',
  K('Nothing.', 'Nothing.', 'Check Again: tries su again.', 'Closes Wave.', 'Nothing.'),
  screen({ page:
    `<div class="msg" style="padding-bottom:10px">${icon('wave', 56)}<div class="t" style="margin-top:12px">This Phone Isn't Rooted</div>` +
    `<div class="d">See the rooting guide at</div><div class="big">thooton.github.io/wave</div>` +
    `<div class="btn press">Check Again</div></div>` }),
  'The only screen without root: no tabs, no bar (nothing to say there).');

// ---- errors
F('Errors', 'error-network', 'No network (nothing fetched yet)',
  K(TABS_LR, 'Nothing.', 'Try Again: refreshes.', BACK_TOP, 'Refresh Sources.'),
  screen({ tab: 1, bar: { title: 'Sources', r: 'Add' }, page:
    `<div class="msg"><div class="t">Cannot Load Sources</div><div class="d">No network.</div><div class="btn press">Try Again</div></div>` }),
  'The Updater\'s message screen, with its action as a button. Home is unchanged by source errors.');
F('Errors', 'error-network-toast', 'No network (sources already fetched)',
  K(TABS_LR, 'As on Sources.', 'As on Sources.', BACK_TOP, 'As on Sources.'),
  screen({ tab: 1, badge: 2, bar: { l: 'Refresh', title: 'Sources', r: 'Add' }, page: sourcesPage({ focus: 'wave' }),
    over: toast("Couldn't refresh: No network.") }),
  'With a list already there, the list stays and a toast says why, as the Updater does.');
F('Errors', 'error-source', "A source that won't refresh",
  K(TABS_LR, 'Move one source.', 'Opens the source as last fetched.', BACK_TOP, 'Refresh, Copy Address, Remove Source.'),
  screen({ tab: 1, badge: 2, bar: { l: 'Refresh', title: 'Sources', r: 'Add' }, page: sourcesPage({ focus: 'tweaks', tweaksErr: true }) }),
  'Its last good copy stays usable; the row says why in red until a refresh works, and the source\'s page says it in full ("Couldn\'t refresh: gitlab.com answered 404.").');
F('Errors', 'error-hash', 'A download that fails its hash',
  K('Nothing.', 'Nothing.', 'OK: closes the alert; the queue goes on with the next package.', 'As OK.', 'Nothing.'),
  screen({ bar: { title: 'Installing' }, page:
    `<div class="qrow">${icon(P.mail, 30)}<div class="tx"><div class="nm">Mail</div><div class="sb red">Not installed</div></div></div>` +
    `<div class="qrow last">${icon(P.charge, 30)}<div class="tx"><div class="nm">Charge</div><div class="sb">Waiting</div></div>${ring(-1)}</div>`,
    over: dialog({ top: 96, title: "Mail wasn't installed", body: "The download doesn't match the source. It was deleted; nothing on the phone changed.", buttons: ['OK'], f: 0 }) }),
  'The Updater\'s alert. Other reasons in its words: cut short, wrong size, "The download is X, not Y.", signed with a different key.');
F('Errors', 'error-watchdog', 'A mod the watchdog turned off',
  K('Between Remove It and OK.', 'Nothing.', 'OK: closes. Remove It: queues its removal and opens the confirm.', 'As OK.', 'Nothing.'),
  screen({ tab: 0, badge: 2, bar: { title: 'Home' }, page: homePage({}),
    over: dialog({ top: 62, head: { icon: icon(P.launch, 32), title: 'Launcher Improved', sub: 'Turned off' },
      body: "The phone didn't finish starting with version 2.0.1, so Wave turned it off. The phone is as it was before it.", buttons: ['!Remove It', 'OK'], f: 1 }) }),
  'Shown once, on the first open after the boot that turned it off. Afterwards its Installed row says "Turned off" in red; Menu there has Turn On and Remove.');
F('Errors', 'error-watchdog-row', 'The turned-off mod in Installed',
  K(TABS_LR, 'Move one package.', 'Opens its details.', BACK_TOP, 'Turn On, Remove, Details.'),
  screen({ tab: 2, bar: { title: 'Installed' }, page:
    H('Up to Date') + instRow(P.auth) + instRow(P.books) + instRow(P.launch, { f: true, sub: 'Turned off: boot failed', subc: 'red' }) + instRow(P.wave, { last: true }) }));

if (typeof module !== 'undefined') module.exports = { FRAMES };
