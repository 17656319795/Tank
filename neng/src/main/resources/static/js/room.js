(() => {
    ensureSession();
    const roomCode = String(query("room") || "").toUpperCase();
    const roomTitle = document.getElementById("roomTitle");
    const roomSummary = document.getElementById("roomSummary");
    const enterBattleButton = document.getElementById("enterBattleButton");
    const backLobbyButton = document.getElementById("backLobbyButton");

    if (!roomCode) {
        showToast("缺少房间号", "error");
        setTimeout(() => location.href = "/lobby.html", 600);
        return;
    }

    backLobbyButton.addEventListener("click", () => location.href = "/lobby.html");
    enterBattleButton.addEventListener("click", async () => {
        try {
            await apiFetch(`/api/rooms/${encodeURIComponent(roomCode)}/enter`, { method: "POST" });
            location.href = `/game.html?room=${encodeURIComponent(roomCode)}`;
        } catch (error) {
            showToast(error.message, "error");
        }
    });

    async function loadRoom() {
        try {
            const room = await apiFetch(`/api/rooms/${encodeURIComponent(roomCode)}`);
            roomTitle.textContent = `${room.roomName} · ${room.roomCode}`;
            roomSummary.innerHTML = `
                <div><span>房间状态</span><span>${roomStatusLabel(room.status)}</span></div>
                <div><span>房主</span><span>${escapeHtml(room.ownerDisplayName)} (@${escapeHtml(room.ownerUsername)})</span></div>
                <div><span>当前人数</span><span>${room.currentPlayers} / ${room.maxPlayers}</span></div>
                <div><span>机器人数量</span><span>${room.botCount || 0} 个</span></div>
                <div><span>空房销毁</span><span>${room.pendingDestroySeconds > 0 ? `${room.pendingDestroySeconds}s 后销毁` : `空房 ${room.emptyDestroySeconds}s 自动销毁`}</span></div>
                <div><span>上局胜者</span><span>${escapeHtml(room.lastWinner || "暂无")}</span></div>
            `;
        } catch (error) {
            showToast(error.message, "error");
            setTimeout(() => location.href = "/lobby.html", 900);
        }
    }

    loadRoom();
    setInterval(loadRoom, 3000);
})();
