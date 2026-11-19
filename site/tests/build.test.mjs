import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, existsSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const script = name => fileURLToPath(new URL('../scripts/' + name, import.meta.url));

test('bundle stays inside its allowlist and resolves links beneath the project prefix', () => {
  const temp = mkdtempSync(join(tmpdir(), 'wave-site-'));
  const out = join(temp, 'wave');
  try {
    const run = name => spawnSync('python3', [script(name), out], {encoding: 'utf8'});
    const build = run('build.py');
    assert.equal(build.status, 0, build.stderr);
    const check = run('check_pages.py');
    assert.equal(check.status, 0, check.stderr);
    for (const excluded of ['scripts', 'tests', '.git', 'AGENTS.md', '.oak', 'mockups']) {
      assert.equal(existsSync(join(out, excluded)), false, excluded);
    }
    for (const excluded of ['src/protocols/sahara.mjs', 'src/protocols/firehose.mjs', 'public-assets']) {
      assert.equal(existsSync(join(out, excluded)), false, excluded);
    }
    for (const page of ['jailbreak', 'rescue', 'restore']) {
      assert.equal(existsSync(join(out, page, 'index.html')), true, page);
    }
    assert.notEqual(run('build.py').status, 0, 'never replace a directory');
    const index = join(out, 'index.html');
    writeFileSync(index, readFileSync(index, 'utf8') + '<a href="../bad">bad</a>');
    assert.notEqual(run('check_pages.py').status, 0, 'missing local link must fail CI');
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
});
