import { Adb } from "../protocols/adb.mjs";
import {
    claimBulk,
    pickDevice,
    SONIM_ADB_FILTER,
} from "../protocols/webusb.mjs";

export const $ = (id) => document.getElementById(id);
export const moduleId = /^[A-Za-z][A-Za-z0-9._-]{0,63}$/;
export const isWebUsb = () =>
    globalThis.isSecureContext && !!globalThis.navigator?.usb;

export function show(status, message, error = false) {
    status.textContent = message;
    status.toggleAttribute("data-error", error);
}

export async function connectAndroid(status) {
    const device = await pickDevice([SONIM_ADB_FILTER]);
    const adb = await Adb.connect(
        await claimBulk(device, { filter: SONIM_ADB_FILTER }),
        {
            onAuthorization: () =>
                show(
                    status,
                    "Please accept the USB debugging prompt on the device!",
                ),
        },
    );
    try {
        const result = await adb.shell(
            `getprop ro.product.model; getprop ro.build.version.sdk; getprop ro.product.cpu.abi; getprop ro.build.fingerprint; getprop sys.boot_completed`,
        );
        if (result.code !== 0)
            throw new Error(
                "Could not read Android compatibility information. Try waiting for Android to finish booting?",
            );
        const [model, sdk, abi, fingerprint, booted] =
            result.stdout
                .trimEnd()
                .split("\n")
                .map((line) => line.trim());
        if (
            model !== "XP3800" ||
            sdk !== "27" ||
            abi !== "armeabi-v7a" ||
            !fingerprint?.startsWith("Sonim/") ||
            booted !== "1"
        )
            throw new Error(
                "Expected a fully booted Sonim XP3800 with Android 8.1 and 32-bit ARM.",
            );
        return { adb, fingerprint, serial: device.serialNumber ?? "" };
    } catch (error) {
        await adb.close();
        throw error;
    }
}

export const chooserClosed = (error) => error?.name === "ChooserClosed";

export function problem(error) {
    if (error?.name === "NotFoundError")
        return "No device selected, or the device disconnected.";
    if (["SecurityError", "NetworkError"].includes(error?.name))
        return "The browser could not claim the USB interface. Close other USB tools and check the USB guide.";
    return error?.message || "Device operation failed.";
}
