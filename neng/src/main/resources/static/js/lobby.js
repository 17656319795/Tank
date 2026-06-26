(() => {
    const session = ensureSession();
    const playerCard = document.getElementById("playerCard");
    const logoutButton = document.getElementById("logoutButton");
    const createRoomForm = document.getElementById("createRoomForm");
    const quickJoinForm = document.getElementById("quickJoinForm");
    const roomList = document.getElementById("roomList");
    const rankingList = document.getElementById("rankingList");
    const refreshRoomsButton = document.getElementById("refreshRoomsButton");

    playerCard.innerHTML = `
        <strong>${escapeHtml(session.player.displayName)}</strong>
        <span>${escapeHtml(session.player.username)} · ${session.player.points || 0} 积分</span>
    `;

    logoutButton.addEventListener("click", async () => {
        try {
            await apiFetch("/api/auth/logout", { method: "POST" });
        } catch (error) {
            console.warn(error);
        }
        clearSession();
        location.href = "/index.html";
    });

    createRoomForm.addEventListener("submit", async event => {
        event.preventDefault();
        const form = new FormData(createRoomForm);
        try {
            const room = await apiFetch("/api/rooms", {
                method: "POST",
                body: JSON.stringify({
                    roomName: form.get("roomName"),
                    maxPlayers: Number(form.get("maxPlayers")),
                    botCount: Number(form.get("botCount"))
                })
            });
            showToast("房间创建成功");
            location.href = `/room.html?room=${encodeURIComponent(room.roomCode)}`;
        } catch (error) {
            showToast(error.message, "error");
        }
    });

    quickJoinForm.addEventListener("submit", async event => {
        event.preventDefault();
        const form = new FormData(quickJoinForm);
        const roomCode = String(form.get("roomCode") || "").trim().toUpperCase();
        if (!roomCode) {
            showToast("请输入房间号", "error");
            return;
        }
        await joinRoom(roomCode);
    });

    refreshRoomsButton.addEventListener("click", loadRooms);

    async function joinRoom(roomCode) {
        try {
            await apiFetch(`/api/rooms/${encodeURIComponent(roomCode)}/enter`, { method: "POST" });
            location.href = `/room.html?room=${encodeURIComponent(roomCode)}`;
        } catch (error) {
            showToast(error.message, "error");
        }
    }

    async function loadRooms() {
        try {
            const rooms = await apiFetch("/api/rooms");
            if (!rooms.length) {
                roomList.innerHTML = `<div class="empty-state">还没有房间，创建一个做第一位房主吧。</div>`;
                return;
            }
            roomList.innerHTML = rooms.map(room => `
                <article class="room-item">
                    <div class="room-item-header">
                        <strong>${escapeHtml(room.roomName)}</strong>
                        <span class="status-chip">${roomStatusLabel(room.status)}</span>
                    </div>
                    <div class="room-meta">房间号: ${escapeHtml(room.roomCode)}</div>
                    <div class="room-meta">房主: ${escapeHtml(room.ownerDisplayName)}</div>
                    <div class="room-meta">人数: ${room.currentPlayers}/${room.maxPlayers}</div>
                    <div class="room-meta">机器人: ${room.botCount || 0} 个</div>
                    <div class="room-meta">上次胜者: ${escapeHtml(room.lastWinner || "暂无")}</div>
                    <button class="secondary-button join-room-button" data-room="${escapeHtml(room.roomCode)}">进入房间</button>
                </article>
            `).join("");
            roomList.querySelectorAll(".join-room-button").forEach(button => {
                button.addEventListener("click", () => joinRoom(button.dataset.room));
            });
        } catch (error) {
            roomList.innerHTML = `<div class="empty-state">加载失败: ${escapeHtml(error.message)}</div>`;
        }
    }

    async function loadRankings() {
        try {
            const rankings = await apiFetch("/api/rankings");
            if (!rankings.length) {
                rankingList.innerHTML = `<div class="empty-state">暂时还没有排行榜数据。</div>`;
                return;
            }
            rankingList.innerHTML = rankings.map((item, index) => `
                <article class="ranking-item">
                    <strong>#${index + 1} ${escapeHtml(item.displayName)}</strong>
                    <span>@${escapeHtml(item.username)}</span>
                    <span>${item.points} 积分 · ${item.wins}/${item.totalMatches} 胜场 · 胜率 ${item.winRate.toFixed(1)}%</span>
                </article>
            `).join("");
        } catch (error) {
            rankingList.innerHTML = `<div class="empty-state">加载失败: ${escapeHtml(error.message)}</div>`;
        }
    }

    loadRooms();
    loadRankings();
    setInterval(loadRooms, 5000);
})();
