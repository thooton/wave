#!/usr/bin/env python3
"""Make the six setup files with the site's real setup page, in headless Chromium (no phone involved).

    python3 tools/qa/setup/generate.py OUT_DIR [--site STAGED_DIR]

Builds the site into a scratch folder unless --site names a staged `wave/` parent, serves it on 127.0.0.1:18753,
fills each app's form with apps.py's fake values, presses the page's own download button, and checks the file
against the payload the generator should have produced. Fails if the page requested anything but its own static assets and blob downloads.
Needs `chromium` and the Python package `websocket-client`.
"""
import argparse, functools, http.server, json, shutil, subprocess, sys, tempfile, threading, time, urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from apps import APPS  # noqa: E402

ROOT = Path(__file__).resolve().parents[3]
PORT = 18753


def main():
    import websocket
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('out', type=Path)
    parser.add_argument('--site', type=Path, help='parent of a staged wave/ folder (default: build site/ now)')
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob('wave-setup-*.json'):
        old.unlink()
    temp = Path(tempfile.mkdtemp(prefix='wave-setup-gen-'))
    try:
        stage = args.site.resolve() if args.site else temp / 'site'
        if not args.site:
            subprocess.run([sys.executable, str(ROOT / 'site/scripts/build.py'), str(stage / 'wave')], check=True, capture_output=True)
        handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(stage))
        handler.log_message = lambda *a, **k: None
        server = http.server.ThreadingHTTPServer(('127.0.0.1', PORT), handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        origin = f'http://127.0.0.1:{PORT}'
        profile = temp / 'profile'
        chrome = subprocess.Popen(['chromium', '--headless=new', '--disable-gpu', '--disable-extensions', '--no-first-run',
                                   '--no-default-browser-check', '--disable-background-networking', f'--user-data-dir={profile}',
                                   '--remote-debugging-port=0', 'about:blank'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            for _ in range(200):
                if (profile / 'DevToolsActivePort').exists():
                    break
                time.sleep(.05)
            port = int((profile / 'DevToolsActivePort').read_text().splitlines()[0])
            url = next(t for t in json.load(urllib.request.urlopen(f'http://127.0.0.1:{port}/json/list')) if t['type'] == 'page')['webSocketDebuggerUrl']
            ws = websocket.create_connection(url, suppress_origin=True, timeout=10)
            state = {'seq': 0, 'network': []}

            def cdp(method, **params):
                state['seq'] += 1
                ws.send(json.dumps({'id': state['seq'], 'method': method, 'params': params}))
                while True:
                    reply = json.loads(ws.recv())
                    if reply.get('id') == state['seq']:
                        assert 'error' not in reply, reply
                        return reply['result']
                    if reply.get('method') == 'Network.requestWillBeSent':
                        state['network'].append(reply['params']['request']['url'])

            def ev(expression):
                reply = cdp('Runtime.evaluate', expression=expression, returnByValue=True, awaitPromise=True, userGesture=True)
                assert not reply.get('exceptionDetails'), reply['exceptionDetails']
                return reply['result'].get('value')

            cdp('Runtime.enable'); cdp('Network.enable')
            cdp('Browser.setDownloadBehavior', behavior='allow', downloadPath=str(out))
            cdp('Page.navigate', url=origin + '/wave/setup/')
            for _ in range(200):
                time.sleep(.05)
                if ev('document.readyState === "complete" && !!document.querySelector("#app") && document.querySelector("#app").options.length === 7'):
                    break
            else:
                raise SystemExit('The setup page did not load with its seven choices.')
            state['network'].clear()
            for app, info in APPS.items():
                values, managed = info['values'], info['managed']
                ev('{ const sel = document.querySelector("#app"); sel.value = %s; sel.dispatchEvent(new Event("change")); }' % json.dumps(app))
                if managed:
                    ev('document.querySelector("#managed").open = true; document.querySelector("#use-managed").click()')
                ev('for (const [key, val] of Object.entries(%s)) document.getElementById(`setting-${key}`).value = val;' % json.dumps(values))
                ev('document.querySelector("button[type=submit]").click()')
                path = out / f'wave-setup-{app}.json'
                for _ in range(200):
                    if path.exists():
                        break
                    time.sleep(.05)
                data = json.loads(path.read_text())
                assert data == {'wave': 1, 'app': app, 'delta' if managed else 'settings': values}, (app, sorted(data))
                print('generated', path.name, 'keys', sorted(data))
            bad = [u for u in state['network'] if not u.startswith((f'blob:{origin}/', f'{origin}/wave/assets/'))]
            assert not bad, bad
            print('the page made no requests besides its own static assets and blob downloads')
        finally:
            chrome.terminate()
            chrome.wait(5)
            server.shutdown()
    finally:
        shutil.rmtree(temp, ignore_errors=True)


if __name__ == '__main__':
    main()
