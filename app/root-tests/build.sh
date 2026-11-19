#!/bin/sh
# Builds what app/ROOT-TESTING.md installs on a rooted test phone, into
# app/root-tests/build/: the two test modules (zips), a code-free test app
# (com.wave.xp3.testapp, two versions, signed with the shared key), and a
# test plan for each check, pointing at http://127.0.0.1:8765/ (served from
# the build folder and forwarded to the phone with `adb reverse`).
#
#   app/root-tests/build.sh
#   (cd app/root-tests/build && python3 -m http.server 8765)
#   adb -s <serial> reverse tcp:8765 tcp:8765
set -eu

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
out=$here/build
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}
tools=$sdk/build-tools/35.0.1
jar=$sdk/platforms/android-35/android.jar
base=http://127.0.0.1:8765

rm -rf "$out"
mkdir -p "$out/tmp"

module() { # id version code
    d=$out/tmp/$1-$2
    cp -R "$here/$1" "$d"
    cp -R "$here/META-INF" "$d/"
    sed -i "s/^version=.*/version=$2/; s/^versionCode=.*/versionCode=$3/" "$d/module.prop"
    (cd "$d" && zip -qr -X "$out/$1-$2.zip" .)
}

apk() { # version code
    d=$out/tmp/app-$1
    mkdir -p "$d"
    cat > "$d/AndroidManifest.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.wave.xp3.testapp">
    <application android:hasCode="false" android:label="Wave Test App" />
</manifest>
EOF
    "$tools/aapt2" link -o "$d/unsigned.apk" -I "$jar" --manifest "$d/AndroidManifest.xml" \
        --min-sdk-version 27 --target-sdk-version 27 --version-code "$2" --version-name "$1"
    "$tools/zipalign" -f 4 "$d/unsigned.apk" "$d/aligned.apk"
    "$tools/apksigner" sign --ks "$repo/signing/debug.keystore" --ks-pass pass:android \
        --key-pass pass:android --ks-key-alias androiddebugkey --out "$out/testapp-$1.apk" "$d/aligned.apk"
}

module wave_test_ok 1.0 1
module wave_test_ok 1.1 2
module wave_test_broken 1.0 1
apk 1.0 1
apk 2.0 2
rm -f "$out"/*.idsig
# A large file, for cutting the network mid-download.
head -c 30000000 /dev/urandom > "$out/big.zip"
rm -rf "$out/tmp"

python3 - "$out" "$base" <<'EOF'
import hashlib, json, os, sys
out, base = sys.argv[1], sys.argv[2]

def pkg(id, type, version, file, sha=None, **extra):
    data = open(os.path.join(out, file), 'rb').read()
    p = {"id": id, "type": type, "name": id, "version": version,
         "download": {"url": base + "/" + file, "size": len(data),
                      "sha256": sha or hashlib.sha256(data).hexdigest()}}
    p.update(extra)
    return p

def plan(name, requests, packages):
    with open(os.path.join(out, name + ".json"), "w") as f:
        json.dump({"source": "root-tests", "requests": [{"op": o, "id": i} for o, i in requests],
                   "packages": packages}, f, indent=1)

app1 = pkg("com.wave.xp3.testapp", "apk", "1.0", "testapp-1.0.apk")
app2 = pkg("com.wave.xp3.testapp", "apk", "2.0", "testapp-2.0.apk")
ok1 = pkg("wave_test_ok", "module", "1.0", "wave_test_ok-1.0.zip")
ok11 = pkg("wave_test_ok", "module", "1.1", "wave_test_ok-1.1.zip")
broken = pkg("wave_test_broken", "module", "1.0", "wave_test_broken-1.0.zip")

plan("apk-install", [("install", app1["id"])], [app1])
plan("apk-update", [("install", app2["id"])], [app2])
plan("apk-remove", [("remove", app1["id"])], [app1])
plan("module-install", [("install", "wave_test_ok")], [ok1])
plan("module-update", [("install", "wave_test_ok")], [ok11])
plan("module-remove", [("remove", "wave_test_ok")], [ok1])
plan("broken-install", [("install", "wave_test_broken")], [broken])
plan("broken-remove", [("remove", "wave_test_broken")], [broken])
plan("bad-hash", [("install", app1["id"])], [pkg(app1["id"], "apk", "1.0", "testapp-1.0.apk", sha="0" * 64)])
plan("bad-package", [("install", "com.wave.xp3.testother")],
     [pkg("com.wave.xp3.testother", "apk", "1.0", "testapp-1.0.apk")])
plan("big-download", [("install", "wave_test_big")], [pkg("wave_test_big", "module", "1.0", "big.zip")])
plan("no-space", [("install", "wave_test_huge")],
     [{"id": "wave_test_huge", "type": "module", "version": "1.0",
       "download": {"url": base + "/big.zip", "size": 500 * 1024 * 1024, "sha256": "0" * 64}}])
EOF
ls -la "$out"
