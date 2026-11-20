// Shared by the installer and Recovery: element lookup, the step checklists,
// one-line problems, and turning device errors into the words the pages use.
export const $ = (id) => document.getElementById(id);
export const isWebUsb = () =>
    globalThis.isSecureContext && !!globalThis.navigator?.usb;

export function show(status, message, error = false) {
    status.textContent = message;
    status.toggleAttribute("data-error", error);
}

export const chooserClosed = (error) => error?.name === "ChooserClosed";

// The sentence a person sees; the raw error goes to the log.
export function problem(error) {
    const message = error?.message ?? "";
    if (/USB debugging is not authorized/.test(message))
        return "USB debugging denied. Try again";
    if (
        ["NetworkError", "NotFoundError", "AbortError"].includes(error?.name) ||
        /^USB (transfer timed out|connection closed|input failed|output)|disconnected|transferIn|transferOut/i.test(
            message,
        )
    )
        return "Connection lost. Try again";
    if (error?.name === "SecurityError")
        return "Device can’t be accessed. See “Can’t find device?” below, then try again";
    return message || "Something went wrong. Try again";
}

// ---- Checklists: the body of each step ----
// <ol class="checklist"> of <li data-state="pending|active|waiting|done|failed">.
export function checklist(list, labels) {
    list.replaceChildren(
        ...labels.map((label) => {
            const item = document.createElement("li");
            item.dataset.state = "pending";
            const text = document.createElement("span"),
                detail = document.createElement("span");
            text.textContent = label;
            detail.className = "detail";
            item.append(text, detail);
            return item;
        }),
    );
    return [...list.children];
}
export function mark(item, state, detail) {
    item.dataset.state = state;
    if (detail !== undefined) item.querySelector(".detail").textContent = detail;
}
export const percent = (item) => (done, total) =>
    mark(item, "active", `${Math.floor((done / total) * 100)}%`);

// ---- Problems: one line, optionally with buttons ----
export function callout(box, text, { danger = false, actions = [] } = {}) {
    const inner = document.createElement("div");
    inner.className = "callout" + (danger ? " danger" : "");
    inner.setAttribute("role", "alert");
    const line = document.createElement("p");
    line.textContent = text;
    inner.append(line);
    if (actions.length) {
        const row = document.createElement("div");
        row.className = "row";
        for (const [label, handler] of actions) {
            const button = document.createElement("button");
            button.type = "button";
            button.className = "primary";
            button.textContent = label;
            button.addEventListener("click", handler);
            row.append(button);
        }
        inner.append(row);
    }
    box.replaceChildren(inner);
    box.hidden = false;
}
export function clearCallout(box) {
    box.replaceChildren();
    box.hidden = true;
}

// ---- Log: raw detail behind "Details are in log" ----
export function logger(details, pre) {
    return {
        add(text) {
            pre.textContent += (pre.textContent ? "\n" : "") + text;
            details.hidden = false;
        },
        clear() {
            pre.textContent = "";
            details.hidden = true;
        },
    };
}
