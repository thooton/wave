// Recovery: write the saved stock boot partition back. Choosing the backup
// starts it: the file is checked, the device goes to EDL (over USB debugging,
// or by hand when Android can't be reached), the backup is written to boot and
// the whole partition read back.
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
} from "./common.mjs?v=redesign4";
import { BackupError, readBackup } from "./backup-file.mjs?v=redesign4";
import {
    chooseEdl,
    claimEdl,
    connectPhone,
    differs,
    knownAndroid,
    startProgrammer,
    waitForEdl,
} from "./device.mjs?v=redesign4";
import { isMagiskPatched } from "./magisk.mjs?v=redesign4";
import { FirehoseBootWriter } from "../protocols/firehose-write.mjs?v=redesign4";
import { fetchProgrammer, servedFiles } from "./served.mjs?v=redesign4";

const STEPS = [
    "Verify backup file",
    "Restart to EDL",
    "Start Sonim service programmer",
    "Verify device",
    "Write original boot partition",
    "Verify boot partition",
    "Restart to Android",
];
const log = logger($("log-box"), $("log"));
let items = checklist($("restore-list"), STEPS),
    busy = false,
    programmer,
    backup, // { manifest, image } once checked
    active,
    waiting;

const visible = (id, on) => ($(id).hidden = !on);
function say(text, ok = false) {
    show($("restore-status"), text);
    $("restore-status").classList.toggle("ok", ok);
}
function begin(item, detail = "") {
    active = item;
    mark(item, "active", detail);
}
function done(item, detail) {
    mark(item, "done", detail);
    if (active === item) active = undefined;
}
function render() {
    $("where").hidden = isWebUsb();
    $("choose").disabled = busy || !isWebUsb() || !programmer;
    for (const id of ["manual", "connect", "choose-edl"]) $(id).disabled = busy;
}
// Back to the start: the backup has to be chosen again.
function reset() {
    backup = undefined;
    waiting = undefined;
    for (const id of ["manual", "connect", "choose-edl", "guide", "edl-help"])
        visible(id, false);
    visible("choose", true);
}
async function task(action) {
    if (busy) return;
    busy = true;
    waiting = undefined;
    clearCallout($("restore-problem"));
    render();
    try {
        await action();
    } catch (error) {
        if (chooserClosed(error)) {
            if (active) mark(active, "waiting");
        } else {
            if (active) mark(active, "failed");
            log.add(`${error.message}${error.detail ? `\n${error.detail}` : ""}`);
            say("");
            callout($("restore-problem"), problem(error));
            if (error.code !== "claim") reset();
        }
    } finally {
        active = undefined;
        busy = false;
        render();
    }
}

// ---- 1. The backup ----
$("choose").addEventListener("click", () => $("backup-file").click());
$("backup-file").addEventListener("change", () =>
    task(async () => {
        const files = [...$("backup-file").files];
        $("backup-file").value = "";
        if (!files.length) return;
        log.clear();
        say("");
        items = checklist($("restore-list"), STEPS);
        begin(items[0]);
        try {
            backup = await readBackup(files);
        } catch (error) {
            if (!(error instanceof BackupError)) throw error;
            throw Object.assign(
                new Error(
                    error.kind === "stock"
                        ? "Invalid backup file (stock verification failed)"
                        : "Invalid Wave backup file",
                ),
                { detail: error.message },
            );
        }
        done(items[0], files.length === 1 ? files[0].name : "");
        log.add(`Backup: serial ${backup.manifest.serial}, saved ${backup.manifest.date}`);
        visible("choose", false);
        // A device this browser already knows restarts into EDL by itself.
        const known = await knownAndroid(backup.manifest.serial);
        if (known) await toEdl();
        else {
            mark(items[1], "waiting");
            say("Connect device via USB debugging, or start EDL manually");
            visible("connect", true);
            visible("manual", true);
        }
    }),
);

// ---- 2. EDL, over USB debugging or by hand ----
async function toEdl() {
    begin(items[1]);
    const { serial, fingerprint } = backup.manifest;
    let adb;
    try {
        ({ adb } = await connectPhone(say, { expect: { serial, fingerprint } }));
    } catch (error) {
        if (chooserClosed(error) || error.code === "other-device") throw error;
        log.add(error.message);
        mark(items[1], "waiting");
        active = undefined;
        say("Can’t connect via ADB, please start EDL manually:");
        return manual();
    }
    try {
        await adb.reboot("edl");
    } finally {
        await adb.close();
    }
    mark(items[1], "waiting");
    awaitEdl("Device rebooted to EDL, please select it in ‘Choose device’!");
}
$("connect").addEventListener("click", () =>
    task(async () => {
        visible("connect", false);
        visible("manual", false);
        await toEdl();
    }),
);
$("manual").addEventListener("click", () => {
    say("Can’t connect via ADB, please start EDL manually:");
    visible("connect", false);
    visible("manual", false);
    manual();
});

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
// Each step waits for Done or counts down.
async function manual() {
    const steps = [
        ["Unplug the USB cable and take the battery out.", "Done"],
        ["Wait for the device to fully power down.", 30],
        ["Hold both volume keys. While holding them, put the battery back in, then plug in the USB cable.", "Done"],
        ["Keep holding both volume keys…", 5, "Let go. The screen stays black in EDL."],
    ];
    const guide = $("guide");
    guide.replaceChildren(
        ...steps.map(([text]) => {
            const item = document.createElement("li");
            item.dataset.state = "pending";
            const body = document.createElement("div");
            body.textContent = text;
            item.append(body);
            return item;
        }),
    );
    visible("guide", true);
    for (const [i, [, wait, after]] of steps.entries()) {
        const item = guide.children[i],
            body = item.firstChild;
        item.dataset.state = "active";
        if (typeof wait === "string") {
            const row = document.createElement("div"),
                button = document.createElement("button");
            row.className = "row";
            button.type = "button";
            button.className = "primary";
            button.textContent = wait;
            row.append(button);
            body.append(row);
            button.focus();
            await new Promise((resolve) => button.addEventListener("click", resolve, { once: true }));
            row.remove();
        } else {
            const count = document.createElement("span");
            count.className = "count";
            body.append(" ", count);
            for (let n = wait; n > 0; n--) {
                count.textContent = n;
                await sleep(1000);
            }
            count.remove();
            if (after) body.textContent = after;
        }
        item.dataset.state = "done";
    }
    awaitEdl("Please select the device in ‘Choose device’");
}

function awaitEdl(status) {
    say(status);
    visible("choose-edl", true);
    visible("edl-help", true);
    $("choose-edl").focus();
    const token = (waiting = Symbol());
    waitForEdl(10 * 60 * 1000).then((device) => {
        if (device && waiting === token && !busy) task(() => write(device));
    });
}
$("choose-edl").addEventListener("click", () =>
    task(async () => {
        active = items[1];
        await write(await chooseEdl());
    }),
);

// ---- 3. Write and verify ----
async function write(device) {
    const [, edl, start, identity, writeItem, verify, restart] = items;
    const { manifest, image } = backup;
    for (const item of items.slice(1)) mark(item, "pending", "");
    begin(edl);
    say("");
    const link = await claimEdl(device);
    done(edl);
    for (const id of ["choose-edl", "edl-help", "guide"]) visible(id, false);
    begin(start);
    await startProgrammer(link, programmer);
    const writer = new FirehoseBootWriter(link);
    try {
        await writer.configure();
        await writer.readGpt();
        done(start);
        begin(identity);
        if (manifest.diskGuid && writer.diskGuid() !== manifest.diskGuid)
            throw Object.assign(new Error("Invalid backup file (device verification failed)"), {
                detail: `Disk ${writer.diskGuid()}, backup ${manifest.diskGuid}`,
            });
        const current = await writer.readBoot(percent(identity));
        const state = !differs(current, image)
            ? "already stock"
            : (await isMagiskPatched(current).catch(() => false))
              ? "patched by Magisk"
              : "modified";
        done(identity, state);
        log.add(`Boot partition before restoring: ${state}`);
        let last;
        const result = await writer.writeBoot(image, {
            onProgress(phase, at, total) {
                const item = phase === "verify" ? verify : writeItem;
                if (item !== last) {
                    if (last) done(last, "");
                    begin(item);
                    last = item;
                }
                if (phase !== "check") percent(item)(at, total);
            },
        });
        if (!result.written) {
            done(writeItem, "already stock");
            done(verify, "");
        } else done(verify, "");
    } catch (error) {
        if (writer.touched)
            throw Object.assign(new Error("Restoring failed, try again"), { detail: error.message });
        await writer.reset().catch(() => {});
        throw error;
    }
    begin(restart);
    await writer.reset().catch(() => log.add("Restart not acknowledged."));
    done(restart);
    backup = undefined;
    say("Successfully restored boot partition", true);
}

async function loadProgrammer() {
    try {
        const base = new URL("../files/", document.baseURI);
        programmer = await fetchProgrammer(base, (await servedFiles(base)).programmer);
    } catch (error) {
        log.add(error.message);
        callout($("restore-problem"), "Installer files damaged. Try reloading");
    } finally {
        render();
    }
}
render();
if (isWebUsb()) loadProgrammer();
