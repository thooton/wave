// The installer: Get ready, Back up, Root, Finish. Each step's checklist is
// its body; every item runs in order and proves the one before it. Progress is
// kept in localStorage and the two images in IndexedDB, so a reload resumes;
// both go at the end or on Start over. Only firehose-write.mjs writes, and
// only to boot, after the backup has been read twice, checked, saved to disk
// and read back from disk.
import {
    $,
    callout,
    checklist,
    chooserClosed,
    clearCallout,
    isWebUsb,
    logger,
    mark,
    percent,
    problem,
    show,
} from "./common.mjs?v=redesign5";
import { makeBackupManifest } from "./backup-manifest.mjs?v=redesign5";
import { backupName, makeBackupFile } from "./backup-file.mjs?v=redesign5";
import {
    claimEdl,
    connectPhone,
    differs,
    findPhone,
    isAndroid,
    isEdl,
    restartFromEdl,
    startProgrammer,
    waitForAndroid,
    waitForDevice,
} from "./device.mjs?v=redesign5";
import {
    MAGISK,
    WAVE_PACKAGE,
    checkMagiskApk,
    installApk,
    installedVersion,
    isMagiskPatched,
    patchOnPhone,
} from "./magisk.mjs?v=redesign5";
import { sha256 } from "../protocols/boot-image.mjs?v=redesign5";
import {
    verifyBootSignature,
    xp3800BootCertSha256,
} from "../protocols/boot-signature.mjs?v=redesign5";
import { FirehoseBootReader } from "../protocols/firehose.mjs?v=redesign5";
import {
    FirehoseBootWriter,
    WriteCheckError,
} from "../protocols/firehose-write.mjs?v=redesign5";
import {
    fetchProgrammer,
    fetchServed,
    servedFiles,
} from "./served.mjs?v=redesign5";

const KEY = "wave-rooting-v2";
const PHASES = ["intro", "ready", "backup", "root", "finish"];
const SERVED = new URL("files/", document.baseURI);
const log = logger($("log-box"), $("log"));
let state = load(),
    view = state.reached ?? "intro",
    busy = false,
    staged = {},
    programmer,
    active, // the checklist item running now
    waiting; // token for an automatic EDL pick-up
const fetched = {};

function load() {
    try {
        const value = JSON.parse(localStorage.getItem(KEY));
        if (value && PHASES.includes(value.reached)) return value;
    } catch {}
    return {};
}
function save(changes) {
    state = { ...state, ...changes };
    localStorage.setItem(KEY, JSON.stringify(state));
}

// ---- IndexedDB: the stock backup and the patched image, until the end ----
function db() {
    return new Promise((resolve, reject) => {
        const request = indexedDB.open("wave-rooting", 1);
        request.onupgradeneeded = () =>
            request.result.createObjectStore("images");
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
    });
}
async function store(mode, use) {
    const handle = await db();
    try {
        return await new Promise((resolve, reject) => {
            const tx = handle.transaction("images", mode),
                request = use(tx.objectStore("images"));
            tx.oncomplete = () => resolve(request?.result);
            tx.onerror = tx.onabort = () =>
                reject(tx.error ?? new Error("Browser storage error."));
        });
    } finally {
        handle.close();
    }
}
const putImage = (name, bytes) => store("readwrite", (s) => s.put(bytes, name));
async function getImage(name, hash) {
    const bytes = await store("readonly", (s) => s.get(name));
    if (!(bytes instanceof Uint8Array) || (await sha256(bytes)) !== hash)
        throw new Error("Browser storage was cleared. Start over");
    return bytes;
}
const dropImages = () =>
    new Promise((resolve) => {
        const request = indexedDB.deleteDatabase("wave-rooting");
        request.onsuccess = request.onerror = request.onblocked = resolve;
    });

// ---- Checklists ----
const READY = ["Sonim XP3800 on Android 8.1", "Verify not rooted", "Verify installer files"];
const BACKUP = [
    "Restart to EDL",
    "Start Sonim service programmer",
    "Read boot partition",
    "Verify read data",
    "Verify stock",
    "Restart to Android",
    "Save file",
    "Verify file",
];
const rootSteps = () => [
    "Wait for Android",
    "Patch boot image (Magisk 24.0)",
    "Sign patched boot image",
    `Install Magisk 24.0, Wave ${staged.wave?.version ?? ""}`.trim(),
    "Restart to EDL",
    "Verify boot partition",
    "Write patched boot image",
    "Verify patched boot image",
    "Restart to Android",
    "Verify root",
];
let lists = {};
function begin(item, detail = "") {
    active = item;
    mark(item, "active", detail);
}
function done(item, detail) {
    mark(item, "done", detail);
    if (active === item) active = undefined;
}
// Rebuild a step's list from the saved progress: the first `count` are done.
function restore(name, labels, count) {
    const items = checklist($(`${name}-list`), labels);
    items.slice(0, count).forEach((item) => mark(item, "done"));
    return (lists[name] = items);
}

// ---- Rendering ----
const reachedIndex = () => PHASES.indexOf(state.reached ?? "intro");
function render() {
    const supported = isWebUsb();
    const ready = !!(programmer && staged.magisk && staged.wave);
    $("where").hidden = supported;
    $("start").disabled = busy || !supported || !ready;
    $("start-over").hidden = !state.reached;
    $("start-over").disabled = busy;
    for (const button of document.querySelectorAll("[data-step]")) {
        const name = button.dataset.step,
            index = PHASES.indexOf(name),
            current = name === view;
        $(`step-${name}`).hidden = !current;
        if (current) button.setAttribute("aria-current", "step");
        else button.removeAttribute("aria-current");
        const past =
            index < reachedIndex() || (name === "finish" && !!state.finished);
        button.toggleAttribute("data-done", past);
        button.toggleAttribute("data-past", past);
        button.disabled = busy || current || index > reachedIndex();
    }
    for (const group of document.querySelectorAll("[data-actions]"))
        group.hidden = group.dataset.actions !== view;
    for (const button of document.querySelectorAll(".forward button"))
        if (button.id !== "start") button.disabled = busy;
}
function go(name) {
    view = name;
    if (PHASES.indexOf(name) > reachedIndex()) save({ reached: name });
    render();
    $(`step-${name}`).querySelector("h1, h2").focus();
}
for (const button of document.querySelectorAll("[data-step]"))
    button.addEventListener("click", () => go(button.dataset.step));
const visible = (id, on) => ($(id).hidden = !on);
for (const help of document.querySelectorAll(".edl-help"))
    help.replaceChildren($("edl-help").content.cloneNode(true));
const edlHelp = (name, on) => {
    const help = $(`step-${name}`).querySelector(".edl-help");
    if (help) help.hidden = !on;
};
function say(name, text, ok = false) {
    show($(`${name}-status`), text);
    $(`${name}-status`).classList.toggle("ok", ok);
    $(`${name}-status`).classList.remove("tick");
}

// Runs one action of a step. Errors become one line in that step's box; the
// item that was running is marked failed and the rest of its list is dropped.
async function task(name, action) {
    if (busy) return;
    busy = true;
    waiting = undefined;
    clearCallout($(`${name}-problem`));
    render();
    try {
        await action();
    } catch (error) {
        if (active) mark(active, chooserClosed(error) ? "waiting" : "failed");
        active = undefined;
        if (!chooserClosed(error)) {
            log.add(`${error.message}${error.detail ? `\n${error.detail}` : ""}`);
            say(name, "");
            if (!error.shown) callout($(`${name}-problem`), problem(error));
            recover[name]?.(error);
        } else if (name === "ready") visible("ready-list", false);
    } finally {
        busy = false;
        render();
    }
}

// After a failure: offer the step's one button again, as "Try again". It finds
// the phone in whichever mode it is now in, so a phone left in EDL is fine.
const recover = {
    ready() {
        $("connect").textContent = "Try again";
    },
    backup() {
        if (state.backup) return;
        $("backup").textContent = "Try again";
        visible("backup", true);
    },
    root() {
        if (["broken", "done"].includes(state.root)) return;
        $("root").textContent = "Try again";
        visible("root", true);
    },
    finish() {
        $("open-wave").textContent = "Try again";
    },
};

// ---- The phone, in whichever mode a step needs ----
// Each step has one button. It finds the phone in Android or EDL (a connected
// one this browser may already use, else the chooser listing both modes),
// moves it to the mode the step needs (adb's reboot for EDL, the programmer's
// reset for Android), then runs the step. After a restart the phone is picked
// up by itself if this browser may already use it in the new mode. EDL has no
// serial number, so the browser doesn't remember it: then the same button,
// now "Continue", asks for it.
const BUTTON = { ready: "connect", backup: "backup", root: "root", finish: "open-wave" };
const inMode = (device, mode) =>
    mode === "edl" ? isEdl(device) : isAndroid(device, state.serial);
async function withPhone(name, mode, item, run, device) {
    // Straight from the click, before any slow work: the chooser needs it.
    device ??= await findPhone(state.serial);
    if (inMode(device, mode)) return run(device);
    begin(item);
    if (isEdl(device)) {
        say(name, "Restarting to Android");
        await restartFromEdl(device, programmer);
    } else {
        const { adb } = await connect(name, device);
        try {
            await adb.reboot("edl");
        } finally {
            await adb.close();
        }
    }
    awaitPhone(name, mode, item, run);
}
function awaitPhone(name, mode, item, run) {
    const edl = mode === "edl";
    mark(item, "waiting");
    if (active === item) active = undefined;
    if (edl) edlHelp(name, true);
    say(
        name,
        edl
            ? "Device restarted to EDL, please press ‘Continue’ and select it!"
            : "Waiting for Android to start. If it isn’t found, press ‘Continue’",
    );
    $(BUTTON[name]).textContent = "Continue";
    visible(BUTTON[name], true);
    const token = (waiting = Symbol());
    waitForDevice((d) => inMode(d, mode), (edl ? 10 : 5) * 60 * 1000).then((device) => {
        if (device && waiting === token && !busy)
            task(name, () => withPhone(name, mode, item, run, device));
    });
}

// ---- Installer files ----
async function loadStaged() {
    try {
        staged = await servedFiles(SERVED);
        programmer = await fetchProgrammer(SERVED, staged.programmer);
        if (!staged.magisk || !staged.wave) throw new Error("missing APK");
    } catch (error) {
        log.add(error.message);
        show($("installer-status"), "Installer files damaged. Try reloading", true);
    } finally {
        render();
    }
}
async function apkFor(name) {
    if (!fetched[name]) {
        let bytes;
        try {
            bytes = await fetchServed(SERVED, staged[name]);
            if (name === "magisk") await checkMagiskApk(bytes);
            else if (bytes[0] !== 0x50 || bytes[1] !== 0x4b)
                throw new Error("Wave file is not an APK");
        } catch (error) {
            throw Object.assign(new Error("Installer files damaged. Try reloading"), {
                detail: error.message,
            });
        }
        fetched[name] = bytes;
    }
    return fetched[name];
}

// Status lines from connectPhone; the USB debugging prompt gets its own box.
const sayFor = (name) => (message) => {
    if (/Allow USB debugging/.test(message) && $(`${name}-prompt`))
        visible(`${name}-prompt`, true);
    else say(name, message);
};
const connect = (name, device) =>
    connectPhone(sayFor(name), {
        device,
        expect: state.serial
            ? { serial: state.serial, fingerprint: state.fingerprint }
            : undefined,
    }).finally(() => $(`${name}-prompt`) && visible(`${name}-prompt`, false));

// ---- Get ready ----
function readyView() {
    const passed = reachedIndex() > PHASES.indexOf("ready");
    visible("ready-list", passed);
    if (passed) restore("ready", READY, READY.length);
    visible("connect", !passed);
    visible("ready-next", passed);
    say("ready", passed ? "Device successfully connected" : "", passed);
}
$("start").addEventListener("click", () => go("ready"));
// Any phone may be identified here, so forget the last one first.
function identify(device) {
    save({ serial: undefined, fingerprint: undefined });
    const [model] = (lists.ready = checklist($("ready-list"), READY));
    visible("ready-list", true);
    return withPhone("ready", "android", model, checkDevice, device);
}
$("connect").addEventListener("click", () => task("ready", () => identify()));
// A device this site may already use, plugged in while Get ready is open.
navigator.usb?.addEventListener("connect", (event) => {
    if (
        view === "ready" &&
        !busy &&
        !$("connect").hidden &&
        event.device.vendorId === 0x1d9c
    )
        task("ready", () => identify(event.device));
});
async function checkDevice(device) {
    say("ready", "");
    log.clear();
    const [model, rooted, files] = (lists.ready = checklist($("ready-list"), READY));
    visible("ready-list", true);
    begin(model);
    save({ serial: undefined, fingerprint: undefined });
    const { adb, serial, fingerprint } = await connect("ready", device);
    try {
        done(model, `serial ${serial}`);
        begin(rooted);
        const su = await adb.shell("command -v su; ls /sbin/magisk /sbin/su 2>/dev/null");
        if (su.stdout.trim())
            throw new Error(
                "Device already rooted. To start from scratch, use Recovery to restore stock and then try again",
            );
        done(rooted);
    } finally {
        await adb.close();
    }
    begin(files);
    await Promise.all([apkFor("magisk"), apkFor("wave")]);
    done(files, `Magisk 24.0, Wave ${staged.wave.version ?? ""}`.trim());
    await dropImages();
    save({ serial, fingerprint, backup: undefined, root: undefined, finished: false });
    log.add(`XP3800, serial ${serial}\n${fingerprint}`);
    say("ready", "Device successfully connected", true);
    visible("connect", false);
    visible("ready-next", true);
}
$("ready-next").addEventListener("click", () => go("backup"));

// ---- Back up ----
function backupView() {
    const count = { read: 6, saved: 8 }[state.backup] ?? 0;
    restore("backup", BACKUP, count);
    visible("backup", !state.backup);
    visible("save", state.backup === "read");
    visible("backup-next", state.backup === "saved");
    visible("backup-card", state.backup === "saved");
    $("backup-file").textContent = state.savedName ?? "";
    if (state.backup === "read") readyToSave();
    else say("backup", "");
}
function readyToSave() {
    say("backup", "Backup ready to save!", true);
    $("backup-status").classList.add("tick");
}
// The one button: Back up, then Continue while waiting for EDL, or Try again.
$("backup").addEventListener("click", () =>
    task("backup", () => {
        const items = (lists.backup = checklist($("backup-list"), BACKUP));
        visible("backup-card", false);
        return withPhone("backup", "edl", items[0], readBackup);
    }),
);
async function readBackup(device) {
    // Every attempt starts from a whole list.
    lists.backup = checklist($("backup-list"), BACKUP);
    const [edl, start, first, second, stock, restart] = lists.backup;
    begin(edl);
    say("backup", "");
    visible("backup", false);
    const link = await claimEdl(device);
    done(edl);
    begin(start);
    await startProgrammer(link, programmer);
    const reader = new FirehoseBootReader(link);
    let image, diskGuid;
    try {
        await reader.configure();
        await reader.readGpt();
        diskGuid = reader.diskGuid();
        done(start);
        begin(first);
        image = await reader.readBoot(percent(first));
        done(first, "");
        begin(second);
        const again = await reader.readBoot(percent(second));
        if (differs(image, again))
            throw new Error("Backup verification failed. Please try again");
        done(second, "");
    } catch (error) {
        await reader.reset().catch(() => {});
        throw error;
    }
    begin(stock);
    const signature = await verifyBootSignature(image).catch(() => ({}));
    if (signature.certSha256 !== xp3800BootCertSha256 || (await isMagiskPatched(image))) {
        await reader.reset().catch(() => {});
        throw new Error("Invalid boot partition (not stock)");
    }
    done(stock);
    begin(restart);
    await reader.reset().catch(() => log.add("Restart not acknowledged."));
    done(restart);
    const hash = await sha256(image);
    await putImage("stock", image);
    log.add(`Stock boot: ${image.length} bytes, SHA-256 ${hash}, disk ${diskGuid}`);
    save({ backup: "read", stockSha: hash, diskGuid, date: new Date().toISOString() });
    visible("save", true);
    readyToSave();
}
$("save").addEventListener("click", () =>
    task("backup", async () => {
        const [, , , , , , write, verify] = lists.backup;
        if (!window.showSaveFilePicker)
            throw new Error(
                "Unable to save file. Perhaps enable brave://flags/#file-system-access-api if exists?",
            );
        const image = await getImage("stock", state.stockSha);
        const manifest = makeBackupManifest(
            {
                serial: state.serial,
                fingerprint: state.fingerprint,
                source: "android-usb",
                diskGuid: state.diskGuid,
            },
            state.stockSha,
            new Date(state.date),
        );
        const file = makeBackupFile(manifest, image);
        let handle;
        try {
            handle = await window.showSaveFilePicker({
                suggestedName: backupName(manifest),
                types: [
                    {
                        description: "Wave backup",
                        accept: { "application/octet-stream": [".wavebackup"] },
                    },
                ],
            });
        } catch (error) {
            if (error?.name === "AbortError") return;
            throw Object.assign(
                new Error(
                    "Unable to save file. Perhaps enable brave://flags/#file-system-access-api if exists?",
                ),
                { detail: error?.message },
            );
        }
        say("backup", "");
        begin(write);
        const writable = await handle.createWritable();
        await writable.write(file);
        await writable.close();
        done(write);
        begin(verify);
        const saved = new Uint8Array(await (await handle.getFile()).arrayBuffer());
        if ((await sha256(saved)) !== (await sha256(file)))
            throw new Error("Saved file verification failed, try again");
        done(verify);
        save({ backup: "saved", savedName: handle.name });
        $("backup-file").textContent = handle.name;
        visible("backup-card", true);
        visible("save", false);
        visible("backup-next", true);
    }),
);
$("backup-next").addEventListener("click", () => go("root"));

// ---- Root ----
function rootView() {
    const count = { patched: 4, written: 9, done: 10 }[state.root] ?? 0;
    const items = restore("root", rootSteps(), count);
    visible("root", state.root !== "done" && state.root !== "broken");
    $("root").textContent =
        state.root === "written" ? "Try again" : state.root === "patched" ? "Continue" : "Root";
    visible("root-next", state.root === "done");
    edlHelp("root", state.root === "patched");
    say("root", state.root === "done" ? "Successfully rooted device" : "", state.root === "done");
    // Patched and installed, not yet written: Continue picks up from EDL.
    if (state.root === "patched") mark(items[4], "waiting");
    if (state.root === "broken") brokenCallout();
}
function brokenCallout() {
    callout(
        $("root-problem"),
        "Backup restoration failed. The boot partition may be incomplete. It is recommended to restore from Recovery",
        { danger: true, actions: [["Open Recovery", () => (location.href = "recovery/")]] },
    );
}
// The one button: Root, then Continue while waiting for a mode, or Try again.
// Where the flow stands decides which mode the phone must be in.
$("root").addEventListener("click", () =>
    task("root", () => {
        if (state.root === "written") {
            const items = restore("root", rootSteps(), 8);
            return withPhone("root", "android", items[8], verifyRoot);
        }
        if (state.root === "patched") {
            const items = restore("root", rootSteps(), 4);
            return withPhone("root", "edl", items[4], writeRoot);
        }
        const items = (lists.root = checklist($("root-list"), rootSteps()));
        return withPhone("root", "android", items[0], patchRoot);
    }),
);
async function patchRoot(device) {
    visible("root", false);
    const items = (lists.root = checklist($("root-list"), rootSteps()));
    const [boot, patch, sign, install, edl] = items;
    begin(boot);
    const stock = await getImage("stock", state.stockSha);
    const [magisk, wave] = await Promise.all([apkFor("magisk"), apkFor("wave")]);
    const { adb } = await connect("root", device);
    try {
        done(boot);
        begin(patch);
        let patched;
        try {
            patched = await patchOnPhone(adb, {
                stock,
                apk: magisk,
                log: log.add,
                onStage: () => {
                    done(patch);
                    begin(sign);
                },
            });
        } catch (error) {
            if (/^(Insufficient storage|File copying failed|Unsupported device)/.test(error.message))
                throw error;
            throw Object.assign(
                new Error("Boot image patching failed, try again. Details are in log"),
                { detail: error.message },
            );
        }
        done(sign);
        begin(install);
        await installApk(adb, magisk, "/data/local/tmp/wave-magisk.apk", "Magisk");
        if ((await installedVersion(adb, MAGISK.package)) !== MAGISK.version)
            throw new Error("Couldn't install Magisk. Version 24.0 did not install.");
        await installApk(adb, wave, "/data/local/tmp/wave-app.apk", "Wave");
        if (!(await installedVersion(adb, WAVE_PACKAGE)))
            throw new Error(`Couldn't install Wave. The bundled APK is not ${WAVE_PACKAGE}.`);
        done(install);
        await putImage("patched", patched);
        save({ root: "patched", patchedSha: await sha256(patched) });
        begin(edl);
        await adb.reboot("edl");
    } finally {
        await adb.close();
    }
    awaitPhone("root", "edl", edl, writeRoot);
}
async function writeRoot(device) {
    const items = (lists.root = restore("root", rootSteps(), 4));
    const [edl, check, write, verify] = items.slice(4);
    begin(edl);
    say("root", "");
    visible("root", false);
    const stock = await getImage("stock", state.stockSha),
        patched = await getImage("patched", state.patchedSha);
    const link = await claimEdl(device);
    edlHelp("root", false);
    await startProgrammer(link, programmer);
    const writer = new FirehoseBootWriter(link);
    const phases = { check, write, verify };
    let last;
    const onProgress = (map) => (phase, at, total) => {
        const item = map[phase];
        if (item !== last) {
            if (last) done(last, "");
            begin(item);
            last = item;
        }
        percent(item)(at, total);
    };
    try {
        await writer.configure();
        await writer.readGpt();
        // The phone in EDL has no serial: its eMMC disk GUID must be the backup's.
        if (state.diskGuid && writer.diskGuid() !== state.diskGuid)
            throw Object.assign(
                new Error("Wrong device in EDL (not the one backed up). Connect that device and try again"),
                { code: "other-device", detail: `Disk ${writer.diskGuid()}, backup ${state.diskGuid}` },
            );
        done(edl);
        await writer.writeBoot(patched, { current: stock, onProgress: onProgress(phases) });
        done(verify, "");
    } catch (error) {
        if (error instanceof WriteCheckError && error.written) {
            log.add(error.message);
            mark(verify, "failed");
            const back = addItem(verify, "Restore backup"),
                again = addItem(back, "Verify backup");
            last = undefined;
            try {
                await writer.writeBoot(stock, {
                    onProgress: onProgress({ check: back, write: back, verify: again }),
                });
                done(again, "");
            } catch (restoreError) {
                log.add(restoreError.message);
                save({ root: "broken" });
                brokenCallout();
                throw Object.assign(restoreError, { shown: true });
            }
            await writer.reset().catch(() => {});
            save({ root: undefined });
            $("root").textContent = "Try again";
            visible("root", true);
            throw new Error("Verifying patched image failed. Please try again");
        }
        if (writer.touched) {
            save({ root: "broken" });
            brokenCallout();
            throw Object.assign(error, { shown: true });
        }
        await writer.reset().catch(() => {});
        // Nothing was written; the patched image still fits the right phone.
        if (error.code !== "other-device") save({ root: undefined });
        $("root").textContent = "Try again";
        visible("root", true);
        if (error instanceof WriteCheckError)
            throw new Error("Boot partition verification failed (changed since backup). Try again");
        throw error;
    }
    save({ root: "written" });
    begin(items[8]);
    await writer.reset().catch(() => log.add("Restart not acknowledged."));
    // Android was chosen in Get ready (it has a serial), so it comes back by itself.
    const phone = await waitForAndroid(state.serial);
    if (!phone) throw new Error("Device did not reconnect. Unplug and reconnect device, try again");
    await verifyRoot(phone);
}
function addItem(after, label) {
    const [item] = checklist(document.createElement("ol"), [label]);
    after.after(item);
    return item;
}
async function verifyRoot(device) {
    const items = lists.root ?? restore("root", rootSteps(), 8);
    const [restart, check] = items.slice(8);
    begin(restart);
    visible("root", false);
    const { adb } = await connect("root", device);
    try {
        done(restart);
        begin(check);
        const su = await adb.shell("ls -l /sbin/su");
        if (su.code !== 0 || !/->\s*\.?\/?magisk/.test(su.stdout)) {
            $("root").textContent = "Try again";
            visible("root", true);
            throw new Error("Root not detected. Restart device and try again");
        }
        done(check);
    } finally {
        await adb.close();
    }
    save({ root: "done" });
    visible("root", false);
    visible("root-next", true);
    say("root", "Successfully rooted device", true);
}
$("root-next").addEventListener("click", () => go("finish"));

// ---- Finish ----
function finishView() {
    const [open] = (lists.finish = checklist($("finish-list"), ["Open Wave"]));
    if (state.finished) mark(open, "done");
    say("finish", state.finished ? "Installation complete" : "", state.finished);
    visible("finish-note", !!state.finished);
}
// The one button: Open Wave, then Continue while waiting for Android, or Try again.
$("open-wave").addEventListener("click", () =>
    task("finish", () => withPhone("finish", "android", lists.finish[0], openWave)),
);
async function openWave(device) {
    const [open] = lists.finish;
    begin(open);
    $("open-wave").textContent = "Open Wave";
    const { adb } = await connect("finish", device);
    try {
        const result = await adb.shell(
            `monkey -p ${WAVE_PACKAGE} -c android.intent.category.LAUNCHER 1`,
        );
        if (
            result.code !== 0 ||
            /No activities found|monkey aborted/i.test(result.stdout + result.stderr)
        )
            throw new Error(
                "Couldn't open Wave. Try setting it as the Push-to-Talk key in settings",
            );
    } finally {
        await adb.close();
    }
    done(open);
    // The backup now lives only in the file the user saved.
    await dropImages();
    save({ finished: true, stockSha: undefined, patchedSha: undefined });
    say("finish", "Installation complete", true);
    visible("finish-note", true);
}

$("start-over").addEventListener("click", async () => {
    if (!confirm("Really start over?")) return;
    busy = true;
    render();
    await dropImages();
    localStorage.removeItem(KEY);
    location.reload();
});

readyView();
backupView();
rootView();
finishView();
render();
if (isWebUsb()) loadStaged().then(() => busy || rootView());
