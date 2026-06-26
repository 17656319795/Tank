const storageKey = "tank-battle-session";
const legacyStorageKey = "tank-battle-session";

function saveSession(session) {
    const serialized = JSON.stringify(session);
    sessionStorage.setItem(storageKey, serialized);
    localStorage.removeItem(legacyStorageKey);
}

function getSession() {
    let raw = sessionStorage.getItem(storageKey);
    if (!raw) {
        raw = localStorage.getItem(legacyStorageKey);
        if (raw) {
            sessionStorage.setItem(storageKey, raw);
            localStorage.removeItem(legacyStorageKey);
        }
    }
    if (!raw) {
        return null;
    }
    try {
        return JSON.parse(raw);
    } catch (error) {
        sessionStorage.removeItem(storageKey);
        localStorage.removeItem(legacyStorageKey);
        return null;
    }
}

function clearSession() {
    sessionStorage.removeItem(storageKey);
    localStorage.removeItem(legacyStorageKey);
}

async function apiFetch(url, options = {}) {
    const session = getSession();
    const headers = Object.assign({
        "Content-Type": "application/json"
    }, options.headers || {});
    if (session && session.token) {
        headers["X-Auth-Token"] = session.token;
    }

    const response = await fetch(url, Object.assign({}, options, { headers }));
    const payload = await response.json().catch(() => ({ success: false, message: "接口响应异常" }));
    if (response.status === 401) {
        clearSession();
        if (!location.pathname.endsWith("/index.html") && location.pathname !== "/") {
            showToast("登录状态已失效，请重新登录", "error");
            setTimeout(() => location.href = "/index.html", 400);
        }
        throw new Error("登录状态已失效，请重新登录");
    }
    if (!response.ok || payload.success === false) {
        const message = payload && payload.message ? payload.message : "请求失败";
        throw new Error(message);
    }
    return payload.data;
}

function showToast(message, variant = "info") {
    const toast = document.getElementById("toast");
    if (!toast) {
        return;
    }
    toast.textContent = message;
    toast.style.borderColor = variant === "error" ? "rgba(239, 68, 68, 0.55)" : "rgba(20, 184, 166, 0.38)";
    toast.classList.add("show");
    clearTimeout(showToast.timer);
    showToast.timer = setTimeout(() => toast.classList.remove("show"), 2600);
}

function ensureSession() {
    const session = getSession();
    if (!session || !session.token) {
        location.href = "/index.html";
        throw new Error("未登录");
    }
    return session;
}

function roomStatusLabel(status) {
    const labels = {
        WAITING: "等待中",
        RUNNING: "进行中",
        FINISHED: "已结束"
    };
    return labels[status] || status;
}

function query(name) {
    return new URLSearchParams(location.search).get(name);
}

function escapeHtml(value) {
    return String(value || "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}
