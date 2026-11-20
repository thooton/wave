// Connecting to the XP3800 for the rooting and stock-boot pages: Android over
// adb (waiting for boot, checking it is the expected phone) and EDL with the
// pinned programmer.
import { validateIdentity } from "./backup-manifest.mjs?v=redesign4";
import { Adb } from "../protocols/adb.mjs?v=redesign4";
import { adbCredential, newAdbKey } from "../protocols/adb-credential.mjs?v=redesign4";
import { startReadOnlyProgrammer } from "../protocols/sahara.mjs?v=redesign4";
import {
    claimBulk,
    pickDevice,
    SONIM_ADB_FILTER,
} from "../protocols/webusb.mjs?v=redesign4";

export const EDL = { vendorId: 0x05c6, productId: 0x9008 },
    DUMP = { vendorId: 0x05c6, productId: 0x900e };
export const differs = (a, b) =>
    a.length !== b.length || a.some((byte, i) => byte !== b[i]);
let credential;

// The adb key lives in this site's IndexedDB, so "Always allow" on the device
// keeps working after a reload, and on the Recovery page too.
function keyStore(mode, use) {
    return new Promise((resolve, reject) => {
        const open = indexedDB.open("wave-adb", 1);
        open.onupgradeneeded = () => open.result.createObjectStore("keys");
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
            const tx = open.result.transaction("keys", mode),
                request = use(tx.objectStore("keys"));
            tx.oncomplete = () => {
                open.result.close();
                resolve(request.result);
            };
            tx.onerror = tx.onabort = () => {
                open.result.close();
                reject(tx.error);
            };
        };
    });
}
async function loadCredential() {
    let jwk = await keyStore("readonly", (s) => s.get("adb")).catch(() => {});
    if (!jwk?.n || !jwk?.d) {
        jwk = await newAdbKey();
        await keyStore("readwrite", (s) => s.put(jwk, "adb")).catch(() => {});
    }
    return adbCredential(jwk);
}

const isAndroid = (d, serial) =>
    d.vendorId === SONIM_ADB_FILTER.vendorId &&
    (!serial || d.serialNumber === serial);
const isEdl = (d) =>
    d.vendorId === EDL.vendorId &&
    [EDL.productId, DUMP.productId].includes(d.productId);

// Resolves once a device matching `test` that this site may already use is
// connected (after a restart), or with undefined after timeoutMs.
export function waitForDevice(test, timeoutMs) {
    return new Promise((resolve) => {
        let done = false;
        const finish = (device) => {
            if (done) return;
            done = true;
            clearTimeout(timer);
            clearInterval(poll);
            navigator.usb.removeEventListener("connect", onConnect);
            resolve(device);
        };
        const look = async () => {
            const device = (await navigator.usb.getDevices()).find(test);
            if (device) finish(device);
        };
        const onConnect = (event) => test(event.device) && finish(event.device);
        navigator.usb.addEventListener("connect", onConnect);
        const poll = setInterval(look, 1000);
        const timer = setTimeout(() => finish(undefined), timeoutMs);
        look();
    });
}
export const waitForAndroid = (serial, timeoutMs = 240000) =>
    waitForDevice((d) => isAndroid(d, serial), timeoutMs);
export const waitForEdl = (timeoutMs) => waitForDevice(isEdl, timeoutMs);
export async function knownAndroid(serial) {
    return (await navigator.usb.getDevices()).find((d) => isAndroid(d, serial));
}

async function androidDevice(serial) {
    // A phone already chosen in this browser comes back after a restart.
    const known = (await navigator.usb.getDevices()).filter((d) =>
        isAndroid(d, serial),
    );
    if (known.length === 1) return known[0];
    return pickDevice([SONIM_ADB_FILTER]);
}

const settled = "Android started; reconnecting.";
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
// The XP3800 drops off USB and comes back once more just after boot completes
// (Android settles its USB configuration), and a link can break mid-command then.
const transient = (error) =>
    error.usbDevice &&
    (["NetworkError", "NotFoundError", "AbortError"].includes(error.name) ||
        /cancelled|timed out|disconnected|closed/i.test(error.message));

// expect: { serial, fingerprint } the phone must have, if known.
export async function connectPhone(say, options = {}) {
    for (let attempt = 0; ; attempt++) {
        try {
            return await connectOnce(say, options);
        } catch (error) {
            if (attempt >= 4 || !transient(error)) throw error;
            say(
                error.message === settled
                    ? "Android started. Reconnecting."
                    : "Device reconnected. Reconnecting.",
            );
            await sleep(5000);
        }
    }
}

async function connectOnce(say, { expect }) {
    const device = await androidDevice(expect?.serial);
    try {
        return await connectTo(device, say, { expect });
    } catch (error) {
        error.usbDevice = true;
        throw error;
    }
}

async function connectTo(device, say, { expect }) {
    credential ??= await loadCredential();
    const adb = await Adb.connect(
        await claimBulk(device, { filter: SONIM_ADB_FILTER }),
        {
            credential,
            onAuthorization: () =>
                say("Please accept “Allow USB debugging?” on the device!"),
        },
    );
    try {
        for (let i = 0; ; i++) {
            const booted = await adb.shell("getprop sys.boot_completed");
            if (booted.stdout.trim() === "1") break;
            if (i >= 90)
                throw new Error(
                    "Device did not reconnect. Unplug and reconnect device, try again",
                );
            if (i === 0) say("Waiting for Android to start.");
            await sleep(2000);
            // Once it has started, give USB time to settle, then start afresh.
            if (
                (
                    await adb.shell("getprop sys.boot_completed")
                ).stdout.trim() === "1"
            ) {
                await adb.close();
                await sleep(8000);
                throw Object.assign(new Error(settled), {
                    name: "NetworkError",
                });
            }
        }
        const result = await adb.shell(
            `getprop ro.product.model; getprop ro.build.version.sdk; getprop ro.product.cpu.abi; getprop ro.build.fingerprint; getprop ro.build.version.release`,
        );
        const [model, sdk, abi, fingerprint, release] = result.stdout
            .trimEnd()
            .split("\n")
            .map((line) => line.trim());
        if (
            result.code !== 0 ||
            model !== "XP3800" ||
            sdk !== "27" ||
            abi !== "armeabi-v7a" ||
            !fingerprint?.startsWith("Sonim/")
        ) {
            throw new Error(
                `Unsupported device (required Sonim XP3800 on Android 8.1, got ${model || "unknown"} on Android ${release || "unknown"})`,
            );
        }
        const serial = device.serialNumber ?? "";
        validateIdentity(serial, fingerprint);
        if (
            expect &&
            (serial !== expect.serial || fingerprint !== expect.fingerprint)
        ) {
            throw Object.assign(
                new Error("Invalid backup file (device verification failed)"),
                { code: "other-device" },
            );
        }
        return { adb, serial, fingerprint };
    } catch (error) {
        await adb.close();
        throw error;
    }
}

async function edlDevice() {
    const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(
        location.hostname,
    );
    if (loopback && new URL(location.href).searchParams.get("policy") === "1") {
        // Hardware QA only: a managed policy grants exactly one EDL device to a loopback origin.
        const matches = (await navigator.usb.getDevices()).filter(
            (d) =>
                d.vendorId === EDL.vendorId &&
                [EDL.productId, DUMP.productId].includes(d.productId),
        );
        if (matches.length !== 1)
            throw new Error(
                "Loopback QA policy must grant exactly one EDL device.",
            );
        return matches[0];
    }
    return pickDevice([EDL, DUMP]);
}

export const chooseEdl = edlDevice;

// device: an EDL device from chooseEdl or waitForEdl. Returns a claimed link.
export async function claimEdl(device) {
    if (device.productId === DUMP.productId)
        throw new Error(
            "Crash-dump mode detected (something went wrong!) Try unplugging, taking the battery out, putting it back in, and then trying again",
        );
    // Just after EDL appears, opening it can lose a race with the OS; retry briefly.
    for (let attempt = 1; ; attempt++) {
        try {
            return await claimBulk(device, { filter: EDL, timeoutMs: 8000 });
        } catch (error) {
            if (attempt < 5) {
                await device.close().catch(() => {});
                await new Promise((resolve) => setTimeout(resolve, 1500));
                continue;
            }
            throw Object.assign(
                new Error(
                    "Device in EDL can't be accessed. See “Can’t find device?” below, then try again",
                ),
                { code: "claim", detail: error?.message },
            );
        }
    }
}

// programmer: bytes already checked with checkProgrammer. Leaves Firehose running on link.
export async function startProgrammer(link, programmer) {
    if (!programmer) throw new Error("Installer files damaged. Try reloading");
    try {
        await startReadOnlyProgrammer(link, programmer);
    } catch (error) {
        await link.close();
        throw Object.assign(
            new Error(
                "Device not responding in EDL. Try unplugging, taking the battery out, putting it back in, and then trying again",
            ),
            { detail: error.message },
        );
    }
}
