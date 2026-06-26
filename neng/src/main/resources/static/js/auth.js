(() => {
    const loginForm = document.getElementById("loginForm");
    const registerForm = document.getElementById("registerForm");
    const tabButtons = document.querySelectorAll(".tab-button");

    if (!loginForm || !registerForm) {
        return;
    }

    const session = getSession();
    if (session && session.token) {
        apiFetch("/api/auth/me")
            .then(data => {
                saveSession(data);
                location.href = "/lobby.html";
            })
            .catch(() => clearSession());
    }

    tabButtons.forEach(button => {
        button.addEventListener("click", () => {
            const tab = button.dataset.tab;
            tabButtons.forEach(item => item.classList.toggle("is-active", item === button));
            loginForm.classList.toggle("hidden", tab !== "login");
            registerForm.classList.toggle("hidden", tab !== "register");
        });
    });

    loginForm.addEventListener("submit", async event => {
        event.preventDefault();
        const form = new FormData(loginForm);
        try {
            const data = await apiFetch("/api/auth/login", {
                method: "POST",
                body: JSON.stringify({
                    username: form.get("username"),
                    password: form.get("password")
                })
            });
            saveSession(data);
            showToast("登录成功，正在进入大厅");
            setTimeout(() => location.href = "/lobby.html", 400);
        } catch (error) {
            showToast(error.message, "error");
        }
    });

    registerForm.addEventListener("submit", async event => {
        event.preventDefault();
        const form = new FormData(registerForm);
        try {
            const data = await apiFetch("/api/auth/register", {
                method: "POST",
                body: JSON.stringify({
                    displayName: form.get("displayName"),
                    username: form.get("username"),
                    password: form.get("password")
                })
            });
            saveSession(data);
            showToast("注册成功，正在进入大厅");
            setTimeout(() => location.href = "/lobby.html", 400);
        } catch (error) {
            showToast(error.message, "error");
        }
    });
})();
