(() => {
    const session = ensureSession();
    const roomCode = String(query("room") || "").toUpperCase();
    const battleCanvas = document.getElementById("battleCanvas");
    const battleTitle = document.getElementById("battleTitle");
    const statusBadge = document.getElementById("statusBadge");
    const timerBadge = document.getElementById("timerBadge");
    const startMatchButton = document.getElementById("startMatchButton");
    const leaveBattleButton = document.getElementById("leaveBattleButton");
    const scoreboard = document.getElementById("scoreboard");
    const battleLogs = document.getElementById("battleLogs");
    const socketState = document.getElementById("socketState");
    const terrainBadge = document.getElementById("terrainBadge");
    const ctx = battleCanvas.getContext("2d");

    if (!roomCode) {
        showToast("缺少房间号", "error");
        setTimeout(() => location.href = "/lobby.html", 600);
        return;
    }

    const TANK_SIZE = 34;
    const BULLET_SIZE = 8;
    const PLAYER_SPEED = 4.5;
    const BULLET_SPEED = 12;
    const FOREST_TERRAIN = "FOREST";
    const TERRAIN_META = {
        GROUND: { label: "合金地表", tint: "#9ec5ff" },
        FOREST: { label: "森林带", tint: "#8bffb5" },
        WATER: { label: "深水区", tint: "#6ed6ff" },
        RUINS: { label: "遗迹壁垒", tint: "#f7d299" },
        MOUNTAIN: { label: "山体阻隔", tint: "#e2e8f0" }
    };

    const state = {
        room: null,
        players: new Map(),
        bullets: [],
        obstacles: [],
        terrain: [],
        logs: [],
        self: session.player.username,
        canStart: false,
        tickRateMs: 50,
        targetScore: 10,
        pendingFrames: [],
        frameId: 0,
        particles: [],
        trails: [],
        pulses: [],
        debris: [],
        trackMarks: [],
        wakeRipples: [],
        scorePulse: {},
        camera: {
            shake: 0,
            x: 0,
            y: 0
        },
        audioEnabled: false,
        reconnectAttempts: 0,
        reconnectGraceSeconds: 25
    };

    const keys = { up: false, down: false, left: false, right: false };
    const audioEngine = createAudioEngine();
    let lastInputSignature = "";
    let animationFrameId = 0;
    let lastRenderTime = performance.now();
    let lastEffectTime = performance.now();

    battleTitle.textContent = `战场房间 ${roomCode}`;
    leaveBattleButton.addEventListener("click", () => location.href = `/room.html?room=${encodeURIComponent(roomCode)}`);
    startMatchButton.addEventListener("click", () => {
        unlockFeedback();
        send({ type: "start" });
    });
    battleCanvas.addEventListener("pointerdown", unlockFeedback, { once: false });

    const socketProtocol = location.protocol === "https:" ? "wss" : "ws";
    let socket = null;
    let reconnectTimer = 0;
    connectSocket();

    window.addEventListener("keydown", event => {
        const changed = updateKey(event.code, true);
        if (event.code === "Space") {
            event.preventDefault();
            unlockFeedback();
            send({ type: "fire" });
        }
        if (changed) {
            flushInput();
        }
    });

    window.addEventListener("keyup", event => {
        if (updateKey(event.code, false)) {
            flushInput();
        }
    });

    setInterval(() => {
        if (socket && socket.readyState === WebSocket.OPEN) {
            send({ type: "ping" });
        }
    }, 10000);

    function send(payload) {
        if (socket && socket.readyState === WebSocket.OPEN) {
            socket.send(JSON.stringify(payload));
        }
    }

    function unlockFeedback() {
        state.audioEnabled = audioEngine.unlock();
        if (state.audioEnabled) {
            audioEngine.startBgm();
        }
    }

    function vibrate(pattern) {
        if (navigator.vibrate) {
            navigator.vibrate(pattern);
        }
    }

    function updateKey(code, pressed) {
        let changed = false;
        if (code === "ArrowUp" || code === "KeyW") {
            changed = keys.up !== pressed;
            keys.up = pressed;
        } else if (code === "ArrowDown" || code === "KeyS") {
            changed = keys.down !== pressed;
            keys.down = pressed;
        } else if (code === "ArrowLeft" || code === "KeyA") {
            changed = keys.left !== pressed;
            keys.left = pressed;
        } else if (code === "ArrowRight" || code === "KeyD") {
            changed = keys.right !== pressed;
            keys.right = pressed;
        }
        return changed;
    }

    function flushInput() {
        const signature = JSON.stringify(keys);
        if (signature === lastInputSignature) {
            return;
        }
        lastInputSignature = signature;
        send(Object.assign({ type: "input" }, keys));
    }

    function connectSocket() {
        if (socket) {
            try {
                socket.close();
            } catch (error) {
                console.warn(error);
            }
        }
        socket = new WebSocket(`${socketProtocol}://${location.hostname}:9001/ws`);
        socket.addEventListener("open", () => {
            state.reconnectAttempts = 0;
            socketState.textContent = "实时连接已建立";
            send({
                type: "join",
                token: session.token,
                roomCode
            });
            flushInput();
        });
        socket.addEventListener("close", () => {
            socketState.textContent = `连接已断开，${Math.max(1, state.reconnectGraceSeconds)}s 内自动重连中`;
            scheduleReconnect();
        });
        socket.addEventListener("message", handleSocketMessage);
    }

    function scheduleReconnect() {
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
        }
        const delay = Math.min(4000, 900 + (state.reconnectAttempts * 450));
        state.reconnectAttempts += 1;
        reconnectTimer = setTimeout(() => {
            connectSocket();
        }, delay);
    }

    function handleSocketMessage(event) {
        const payload = JSON.parse(event.data);
        if (payload.type === "welcome") {
            state.self = payload.self;
            state.canStart = Boolean(payload.canStart);
            state.tickRateMs = payload.tickRateMs || state.tickRateMs;
            state.targetScore = payload.targetScore || state.targetScore;
            state.reconnectGraceSeconds = payload.reconnectGraceSeconds || state.reconnectGraceSeconds;
            battleCanvas.width = payload.mapWidth;
            battleCanvas.height = payload.mapHeight;
            startMatchButton.classList.toggle("hidden", !state.canStart);
            battleTitle.textContent = `${payload.roomName} · ${payload.roomCode}`;
        } else if (payload.type === "roomMeta") {
            applyRoomMeta(payload);
        } else if (payload.type === "snapshot") {
            applySnapshot(payload);
        } else if (payload.type === "frame") {
            state.pendingFrames.push(payload);
        } else if (payload.type === "system") {
            pushLog(payload.message);
        } else if (payload.type === "gameOver") {
            pushLog(`本局结束: ${payload.reason}，胜者 ${payload.winner}`);
            spawnCenterPulse("#fb7185", 170);
            shakeCamera(16);
            playExplosionSound(0.9);
            vibrate([80, 40, 120]);
            showToast(`胜者: ${payload.winner}`);
        } else if (payload.type === "error") {
            if (String(payload.message || "").indexOf("登录状态已失效") >= 0) {
                clearSession();
                showToast("登录状态已失效，请重新登录", "error");
                setTimeout(() => location.href = "/index.html", 400);
                return;
            }
            showToast(payload.message, "error");
        }
    }

    function applyRoomMeta(payload) {
        if (payload.room) {
            state.room = payload.room;
            state.tickRateMs = payload.room.tickRateMs || state.tickRateMs;
        }
        const permissions = payload.permissions || [];
        const selfPermission = permissions.find(item => item.username === state.self);
        state.canStart = Boolean(selfPermission && selfPermission.canStart);
        renderHud();
    }

    function applySnapshot(payload) {
        state.frameId = payload.frameId || 0;
        state.room = payload.room || state.room;
        state.terrain = (payload.terrain || []).map(item => Object.assign({}, item));
        state.obstacles = payload.obstacles || state.terrain.filter(item => item.blocksTank);
        state.logs = payload.logs || [];
        state.bullets = (payload.bullets || []).map(item => Object.assign({}, item));

        const previousPlayers = new Map(state.players);
        state.players.clear();
        (payload.players || []).forEach(player => {
            const previous = previousPlayers.get(player.username);
            const entry = {
                username: player.username,
                displayName: player.displayName,
                color: player.color,
                x: player.x,
                y: player.y,
                direction: player.direction,
                alive: player.alive,
                score: player.score,
                deaths: player.deaths,
                connected: player.connected,
                bot: Boolean(player.bot),
                respawnAt: player.respawnAt,
                reconnectUntil: player.reconnectUntil || 0,
                seatIndex: player.seatIndex || 0,
                terrainType: player.terrainType || "GROUND",
                speedFactor: player.speedFactor || 1,
                visibilityFactor: player.visibilityFactor || 1
            };
            if (previous && previous.alive && !player.alive) {
                spawnExplosion(player.x + 17, player.y + 17, player.color, 1.1);
                spawnDebrisField(player.x + 17, player.y + 17, player.color);
                spawnCenterPulse(player.color, 42, player.x + 17, player.y + 17);
                shakeCamera(10);
                playExplosionSound(0.72);
                vibrate(50);
            }
            if (previous && previous.score !== player.score) {
                pulseScore(entry.username);
            }
            state.players.set(player.username, entry);
        });
        state.pendingFrames = state.pendingFrames.filter(frame => frame.frameId > state.frameId);
        renderHud();
        drawScene(performance.now());
    }

    function processFrames() {
        state.pendingFrames.sort((a, b) => a.frameId - b.frameId);
        while (state.pendingFrames.length && state.pendingFrames[0].frameId === state.frameId + 1) {
            const frame = state.pendingFrames.shift();
            applyFrame(frame);
        }
    }

    function applyFrame(frame) {
        state.frameId = frame.frameId;
        const inputs = frame.inputs || [];
        const inputMap = new Map(inputs.map(item => [item.username, item]));

        Array.from(state.players.values()).forEach(player => {
            if (!player.connected) {
                return;
            }
            const input = inputMap.get(player.username) || { up: false, down: false, left: false, right: false, fire: false };
            if (!player.alive) {
                if (player.respawnAt && frame.serverTime >= player.respawnAt) {
                    player.alive = true;
                    spawnRespawnPulse(player);
                    shakeCamera(4);
                }
            } else {
                const beforeX = player.x;
                const beforeY = player.y;
                movePlayer(player, input);
                player.terrainType = dominantTerrainType(player.x, player.y, TANK_SIZE, TANK_SIZE);
                player.speedFactor = movementSpeedMultiplier(player.x, player.y, TANK_SIZE, TANK_SIZE);
                player.visibilityFactor = visibilityMultiplier(player.x, player.y, TANK_SIZE, TANK_SIZE);
                if (beforeX !== player.x || beforeY !== player.y) {
                    spawnTrackMarks(player, beforeX, beforeY);
                    spawnTrackDust(player);
                    if (player.terrainType === "WATER") {
                        spawnWakeRipple(player.x + 17, player.y + 17, player.color);
                    }
                }
                if (input.fire) {
                    spawnVisualBullet(player);
                    spawnMuzzleFlash(player);
                    playFireSound(player.color);
                    if (player.username === state.self) {
                        shakeCamera(3);
                        vibrate(20);
                    }
                }
            }
        });

        updateBullets();
        renderHud();
    }

    function movePlayer(player, input) {
        const movementSpeed = PLAYER_SPEED * movementSpeedMultiplier(player.x, player.y, TANK_SIZE, TANK_SIZE);
        let nextX = player.x;
        let nextY = player.y;
        if (input.up && !input.down) {
            nextY -= movementSpeed;
            player.direction = "UP";
        }
        if (input.down && !input.up) {
            nextY += movementSpeed;
            player.direction = "DOWN";
        }
        if (input.left && !input.right) {
            nextX -= movementSpeed;
            player.direction = "LEFT";
        }
        if (input.right && !input.left) {
            nextX += movementSpeed;
            player.direction = "RIGHT";
        }
        nextX = clamp(nextX, 0, battleCanvas.width - TANK_SIZE);
        nextY = clamp(nextY, 0, battleCanvas.height - TANK_SIZE);
        if (!hitsBlockingTerrain(nextX, nextY, TANK_SIZE, TANK_SIZE, true)) {
            player.x = nextX;
            player.y = nextY;
        }
    }

    function spawnVisualBullet(player) {
        state.bullets.push({
            id: `local-${player.username}-${state.frameId}-${state.bullets.length}`,
            ownerUsername: player.username,
            x: player.x + (TANK_SIZE / 2) - (BULLET_SIZE / 2),
            y: player.y + (TANK_SIZE / 2) - (BULLET_SIZE / 2),
            direction: player.direction,
            color: player.color
        });
    }

    function updateBullets() {
        state.bullets = state.bullets.filter(bullet => {
            const prevX = bullet.x;
            const prevY = bullet.y;
            if (bullet.direction === "UP") {
                bullet.y -= BULLET_SPEED;
            } else if (bullet.direction === "DOWN") {
                bullet.y += BULLET_SPEED;
            } else if (bullet.direction === "LEFT") {
                bullet.x -= BULLET_SPEED;
            } else if (bullet.direction === "RIGHT") {
                bullet.x += BULLET_SPEED;
            }

            state.trails.push({
                x1: prevX + 4,
                y1: prevY + 4,
                x2: bullet.x + 4,
                y2: bullet.y + 4,
                life: 0.24,
                color: bullet.color || "#f8fafc"
            });

            if (
                bullet.x < 0 ||
                bullet.y < 0 ||
                bullet.x > battleCanvas.width ||
                bullet.y > battleCanvas.height ||
                hitsBlockingTerrain(bullet.x, bullet.y, BULLET_SIZE, BULLET_SIZE, false)
            ) {
                spawnSparkBurst(bullet.x + 4, bullet.y + 4, bullet.color || "#f8fafc");
                spawnDebrisField(bullet.x + 4, bullet.y + 4, bullet.color || "#ffffff", 4, 0.22);
                playImpactSound();
                return false;
            }
            return true;
        });
    }

    function hitsBlockingTerrain(x, y, width, height, blocksTank) {
        return state.terrain.some(zone => {
            const shouldBlock = blocksTank ? zone.blocksTank : zone.blocksBullet;
            return shouldBlock && intersects(x, y, width, height, zone.x, zone.y, zone.width, zone.height);
        });
    }

    function movementSpeedMultiplier(x, y, width, height) {
        let multiplier = 1;
        terrainAt(x, y, width, height).forEach(zone => {
            if (typeof zone.speedMultiplier === "number") {
                multiplier = Math.min(multiplier, zone.speedMultiplier);
            }
        });
        return multiplier;
    }

    function visibilityMultiplier(x, y, width, height) {
        let multiplier = 1;
        terrainAt(x, y, width, height).forEach(zone => {
            if (typeof zone.visibilityMultiplier === "number") {
                multiplier = Math.min(multiplier, zone.visibilityMultiplier);
            }
        });
        return multiplier;
    }

    function dominantTerrainType(x, y, width, height) {
        const matches = terrainAt(x, y, width, height);
        if (!matches.length) {
            return "GROUND";
        }
        const priority = { GROUND: 0, FOREST: 1, WATER: 2, RUINS: 3, MOUNTAIN: 4 };
        matches.sort((a, b) => (priority[b.type] || 0) - (priority[a.type] || 0));
        return matches[0].type || "GROUND";
    }

    function terrainAt(x, y, width, height) {
        return state.terrain.filter(zone => intersects(x, y, width, height, zone.x, zone.y, zone.width, zone.height));
    }

    function intersects(ax, ay, aw, ah, bx, by, bw, bh) {
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }

    function clamp(value, min, max) {
        return Math.max(min, Math.min(max, value));
    }

    function renderHud() {
        if (!state.room) {
            return;
        }
        const self = state.players.get(state.self);
        const terrainType = self ? self.terrainType : "GROUND";
        const terrainLabel = (TERRAIN_META[terrainType] || TERRAIN_META.GROUND).label;
        statusBadge.textContent = roomStatusLabel(state.room.status);
        timerBadge.textContent = `${state.room.remainingSeconds}s`;
        if (terrainBadge) {
            terrainBadge.textContent = `地形 ${terrainLabel}`;
            terrainBadge.style.color = (TERRAIN_META[terrainType] || TERRAIN_META.GROUND).tint;
        }
        startMatchButton.classList.toggle("hidden", !(state.canStart && state.room.status !== "RUNNING"));
        scoreboard.innerHTML = Array.from(state.players.values())
            .sort((a, b) => b.score - a.score || a.seatIndex - b.seatIndex)
            .map(player => {
                const pulse = state.scorePulse[player.username] ? " score-row-pulse" : "";
                const terrainLabelShort = (TERRAIN_META[player.terrainType] || TERRAIN_META.GROUND).label;
                const roleLabel = player.bot ? "· 机器人" : (player.username === state.self ? "· 你" : "");
                const reconnectLabel = !player.bot && !player.connected && player.reconnectUntil
                    ? ` · 重连中 ${Math.max(0, Math.ceil((player.reconnectUntil - Date.now()) / 1000))}s`
                    : (player.connected ? "" : " · 离线");
                return `
                    <div class="score-row ${player.username === state.self ? "score-row-self" : ""}${pulse}">
                        <strong style="color:${player.color}">${escapeHtml(player.displayName)}</strong>
                        <span>${player.score} 分 · ${player.deaths} 次阵亡 ${roleLabel}${reconnectLabel} · ${terrainLabelShort}</span>
                    </div>
                `;
            })
            .join("");
        battleLogs.innerHTML = state.logs.map(log => `<div>${escapeHtml(log)}</div>`).join("");
        socketState.textContent = `房间 ${state.room.roomCode} · ${state.room.currentPlayers}/${state.room.maxPlayers} 人 · 机器人 ${state.room.botCount || 0} · 帧 ${state.frameId}`;
    }

    function pulseScore(username) {
        state.scorePulse[username] = performance.now() + 520;
    }

    function pushLog(message) {
        state.logs = [message].concat(state.logs || []).slice(0, 8);
        battleLogs.innerHTML = state.logs.map(log => `<div>${escapeHtml(log)}</div>`).join("");
    }

    function spawnTrackDust(player) {
        const cx = player.x + 17;
        const cy = player.y + 17;
        const dustColor = player.terrainType === FOREST_TERRAIN ? "rgba(125,215,148,0.44)" : "rgba(255,210,160,0.55)";
        for (let i = 0; i < 2; i++) {
            state.particles.push({
                x: cx + (Math.random() - 0.5) * 18,
                y: cy + (Math.random() - 0.5) * 18,
                vx: (Math.random() - 0.5) * 1.4,
                vy: (Math.random() - 0.5) * 1.4,
                radius: 2 + Math.random() * 2.5,
                life: 0.5 + Math.random() * 0.3,
                color: dustColor
            });
        }
    }

    function spawnTrackMarks(player, beforeX, beforeY) {
        const dx = player.x - beforeX;
        const dy = player.y - beforeY;
        if (Math.abs(dx) + Math.abs(dy) < 0.1) {
            return;
        }
        const angle = Math.atan2(dy, dx || 0.001);
        const cx = player.x + 17;
        const cy = player.y + 17;
        const offset = 10;
        const normalX = Math.cos(angle + Math.PI / 2) * offset;
        const normalY = Math.sin(angle + Math.PI / 2) * offset;
        state.trackMarks.push({
            x: cx + normalX,
            y: cy + normalY,
            angle,
            width: 18,
            height: 6,
            life: 2.8,
            color: player.terrainType === "WATER" ? "rgba(198,244,255,0.18)" : "rgba(14,22,34,0.22)"
        });
        state.trackMarks.push({
            x: cx - normalX,
            y: cy - normalY,
            angle,
            width: 18,
            height: 6,
            life: 2.8,
            color: player.terrainType === "WATER" ? "rgba(198,244,255,0.18)" : "rgba(14,22,34,0.22)"
        });
        if (state.trackMarks.length > 180) {
            state.trackMarks.splice(0, state.trackMarks.length - 180);
        }
    }

    function spawnWakeRipple(x, y, color) {
        state.wakeRipples.push({
            x,
            y,
            radius: 4,
            maxRadius: 22 + Math.random() * 10,
            life: 0.5,
            color: toAlphaColor(color, 0.5)
        });
    }

    function spawnMuzzleFlash(player) {
        const origin = getBarrelTip(player);
        spawnSparkBurst(origin.x, origin.y, player.color);
        state.pulses.push({
            x: origin.x,
            y: origin.y,
            radius: 4,
            maxRadius: 24,
            life: 0.18,
            color: player.color
        });
    }

    function spawnExplosion(x, y, color, scale = 1) {
        for (let i = 0; i < 26; i++) {
            const angle = Math.random() * Math.PI * 2;
            const speed = (1.2 + Math.random() * 3.4) * scale;
            state.particles.push({
                x,
                y,
                vx: Math.cos(angle) * speed,
                vy: Math.sin(angle) * speed,
                radius: 2 + Math.random() * 5,
                life: 0.65 + Math.random() * 0.55,
                color: i % 3 === 0 ? color : (i % 2 === 0 ? "#ffd166" : "#fff1c1")
            });
        }
    }

    function spawnDebrisField(x, y, color, count = 10, scale = 1) {
        for (let i = 0; i < count; i++) {
            const angle = Math.random() * Math.PI * 2;
            const speed = (0.8 + Math.random() * 3.1) * scale;
            state.debris.push({
                x,
                y,
                vx: Math.cos(angle) * speed,
                vy: Math.sin(angle) * speed,
                rotation: Math.random() * Math.PI * 2,
                spin: (Math.random() - 0.5) * 0.2,
                size: 4 + Math.random() * 7,
                life: 1.2 + Math.random() * 1.6,
                color: i % 2 === 0 ? darkenColor(color, 0.2) : "#d9e5f2"
            });
        }
    }

    function spawnSparkBurst(x, y, color) {
        for (let i = 0; i < 8; i++) {
            const angle = Math.random() * Math.PI * 2;
            const speed = 0.6 + Math.random() * 1.8;
            state.particles.push({
                x,
                y,
                vx: Math.cos(angle) * speed,
                vy: Math.sin(angle) * speed,
                radius: 1.5 + Math.random() * 2.5,
                life: 0.28 + Math.random() * 0.18,
                color
            });
        }
    }

    function spawnRespawnPulse(player) {
        state.pulses.push({
            x: player.x + 17,
            y: player.y + 17,
            radius: 10,
            maxRadius: 42,
            life: 0.65,
            color: "#8bffcc"
        });
    }

    function spawnCenterPulse(color, maxRadius, x = battleCanvas.width / 2, y = battleCanvas.height / 2) {
        state.pulses.push({
            x,
            y,
            radius: 10,
            maxRadius,
            life: 0.65,
            color
        });
    }

    function shakeCamera(power) {
        state.camera.shake = Math.max(state.camera.shake, power);
    }

    function getBarrelTip(player) {
        if (player.direction === "UP") {
            return { x: player.x + 17, y: player.y - 2 };
        }
        if (player.direction === "DOWN") {
            return { x: player.x + 17, y: player.y + 36 };
        }
        if (player.direction === "LEFT") {
            return { x: player.x - 2, y: player.y + 17 };
        }
        return { x: player.x + 36, y: player.y + 17 };
    }

    function updateEffects(deltaSeconds, now) {
        state.particles = state.particles.filter(item => {
            item.x += item.vx * 60 * deltaSeconds;
            item.y += item.vy * 60 * deltaSeconds;
            item.life -= deltaSeconds;
            item.radius *= 0.992;
            return item.life > 0;
        });
        state.trails = state.trails.filter(item => {
            item.life -= deltaSeconds;
            return item.life > 0;
        });
        state.pulses = state.pulses.filter(item => {
            item.life -= deltaSeconds;
            item.radius += (item.maxRadius - item.radius) * 0.18;
            return item.life > 0;
        });
        state.trackMarks = state.trackMarks.filter(item => {
            item.life -= deltaSeconds;
            return item.life > 0;
        });
        state.debris = state.debris.filter(item => {
            item.x += item.vx * 60 * deltaSeconds;
            item.y += item.vy * 60 * deltaSeconds;
            item.vx *= 0.985;
            item.vy *= 0.985;
            item.rotation += item.spin;
            item.life -= deltaSeconds;
            return item.life > 0;
        });
        state.wakeRipples = state.wakeRipples.filter(item => {
            item.life -= deltaSeconds;
            item.radius += (item.maxRadius - item.radius) * 0.16;
            return item.life > 0;
        });
        Object.keys(state.scorePulse).forEach(username => {
            if (state.scorePulse[username] < now) {
                delete state.scorePulse[username];
            }
        });
        state.camera.shake = Math.max(0, state.camera.shake - deltaSeconds * 28);
        state.camera.x = (Math.random() - 0.5) * state.camera.shake * 1.2;
        state.camera.y = (Math.random() - 0.5) * state.camera.shake * 1.2;
    }

    function drawScene(now) {
        ctx.clearRect(0, 0, battleCanvas.width, battleCanvas.height);
        ctx.save();
        ctx.translate(state.camera.x, state.camera.y);
        drawBattlefieldBackdrop(now);
        drawTerrainBase(now);
        drawGrid(now);
        drawEnergyVeins(now);
        drawTrackMarks();
        drawTerrainFeatures(now);
        drawWakeRipples();
        drawTrails();
        drawBullets();
        drawPlayers(now);
        drawDebris();
        drawEffects();
        drawForestFog(now);
        drawVignette();
        ctx.restore();
    }

    function drawBattlefieldBackdrop(now) {
        const pulse = 0.5 + Math.sin(now * 0.0012) * 0.08;
        const gradient = ctx.createLinearGradient(0, 0, battleCanvas.width, battleCanvas.height);
        gradient.addColorStop(0, "#07111f");
        gradient.addColorStop(0.5, "#0d1d2e");
        gradient.addColorStop(1, "#0a1423");
        ctx.fillStyle = gradient;
        ctx.fillRect(0, 0, battleCanvas.width, battleCanvas.height);

        const bloom = ctx.createRadialGradient(
            battleCanvas.width * 0.56,
            battleCanvas.height * 0.44,
            30,
            battleCanvas.width * 0.56,
            battleCanvas.height * 0.44,
            battleCanvas.width * 0.65
        );
        bloom.addColorStop(0, `rgba(56,189,248,${0.06 + pulse * 0.04})`);
        bloom.addColorStop(0.5, "rgba(34,211,238,0.035)");
        bloom.addColorStop(1, "rgba(7,17,31,0)");
        ctx.fillStyle = bloom;
        ctx.fillRect(0, 0, battleCanvas.width, battleCanvas.height);
    }

    function drawTerrainBase(now) {
        const base = ctx.createLinearGradient(0, 0, battleCanvas.width, battleCanvas.height);
        base.addColorStop(0, "#16273b");
        base.addColorStop(0.55, "#20344b");
        base.addColorStop(1, "#111c2f");
        ctx.fillStyle = base;
        ctx.fillRect(0, 0, battleCanvas.width, battleCanvas.height);

        ctx.save();
        ctx.globalAlpha = 0.1;
        for (let i = 0; i < battleCanvas.width; i += 56) {
            ctx.fillStyle = i % 112 === 0 ? "rgba(255,255,255,0.08)" : "rgba(255,255,255,0.03)";
            ctx.fillRect(i, 0, 26, battleCanvas.height);
        }
        for (let y = 0; y < battleCanvas.height; y += 44) {
            ctx.fillStyle = `rgba(255,255,255,${0.018 + ((y / 44) % 2) * 0.01})`;
            ctx.fillRect(0, y, battleCanvas.width, 10);
        }
        ctx.restore();

        ctx.save();
        ctx.strokeStyle = "rgba(245, 158, 11, 0.05)";
        ctx.lineWidth = 2;
        for (let x = -60; x < battleCanvas.width + 60; x += 120) {
            ctx.beginPath();
            ctx.moveTo(x + (now * 0.015) % 40, 0);
            ctx.lineTo(x + 80 + (now * 0.015) % 40, battleCanvas.height);
            ctx.stroke();
        }
        ctx.restore();
    }

    function drawGrid(now) {
        const offset = (now * 0.018) % 32;
        ctx.strokeStyle = "rgba(147, 197, 253, 0.055)";
        ctx.lineWidth = 1;
        for (let x = -32; x <= battleCanvas.width + 32; x += 32) {
            ctx.beginPath();
            ctx.moveTo(x + offset, 0);
            ctx.lineTo(x + offset, battleCanvas.height);
            ctx.stroke();
        }
        for (let y = -32; y <= battleCanvas.height + 32; y += 32) {
            ctx.beginPath();
            ctx.moveTo(0, y + offset * 0.35);
            ctx.lineTo(battleCanvas.width, y + offset * 0.35);
            ctx.stroke();
        }
    }

    function drawEnergyVeins(now) {
        ctx.save();
        ctx.globalCompositeOperation = "screen";
        ctx.lineWidth = 2;
        ctx.strokeStyle = "rgba(94, 234, 212, 0.06)";
        for (let i = 0; i < 3; i++) {
            ctx.beginPath();
            for (let x = 0; x <= battleCanvas.width; x += 32) {
                const y = battleCanvas.height * (0.2 + i * 0.25) + Math.sin((x * 0.011) + now * 0.0015 + i) * 14;
                if (x === 0) {
                    ctx.moveTo(x, y);
                } else {
                    ctx.lineTo(x, y);
                }
            }
            ctx.stroke();
        }
        ctx.restore();
    }

    function drawTrackMarks() {
        ctx.save();
        state.trackMarks.forEach(item => {
            ctx.save();
            ctx.translate(item.x, item.y);
            ctx.rotate(item.angle);
            ctx.fillStyle = toAlphaColor(item.color, item.life * 0.12);
            roundRect(-item.width / 2, -item.height / 2, item.width, item.height, 3, true, false);
            ctx.restore();
        });
        ctx.restore();
    }

    function drawTerrainFeatures(now) {
        state.terrain.forEach((zone, index) => {
            if (zone.type === "FOREST") {
                drawForestZone(zone, index, now);
            } else if (zone.type === "WATER") {
                drawWaterZone(zone, index, now);
            } else if (zone.type === "MOUNTAIN") {
                drawMountainZone(zone, index, now);
            } else if (zone.type === "RUINS") {
                drawRuinsZone(zone, index, now);
            }
        });
    }

    function drawForestZone(zone, index, now) {
        const gradient = ctx.createLinearGradient(zone.x, zone.y, zone.x + zone.width, zone.y + zone.height);
        gradient.addColorStop(0, "rgba(24, 56, 37, 0.72)");
        gradient.addColorStop(1, "rgba(38, 87, 53, 0.92)");
        ctx.fillStyle = gradient;
        roundRect(zone.x, zone.y, zone.width, zone.height, 22, true, false);

        ctx.save();
        ctx.beginPath();
        roundRect(zone.x, zone.y, zone.width, zone.height, 22, false, false);
        ctx.clip();
        for (let i = 0; i < 18; i++) {
            const px = zone.x + 18 + ((i * 29) % Math.max(36, zone.width - 20));
            const py = zone.y + 18 + ((i * 47) % Math.max(36, zone.height - 20));
            const sway = Math.sin(now * 0.002 + i) * 4;
            drawPine(px + sway, py, 16 + (i % 3) * 3, i % 2 === 0 ? "#2d6a4f" : "#3a7d54");
        }
        ctx.fillStyle = "rgba(184, 255, 200, 0.08)";
        for (let i = 0; i < 4; i++) {
            ctx.fillRect(zone.x + 10, zone.y + i * 24 + 10, zone.width - 20, 1.5);
        }
        ctx.restore();
    }

    function drawWaterZone(zone, index, now) {
        const water = ctx.createLinearGradient(zone.x, zone.y, zone.x + zone.width, zone.y + zone.height);
        water.addColorStop(0, "#163d60");
        water.addColorStop(0.55, "#1d5a86");
        water.addColorStop(1, "#0f2f49");
        ctx.fillStyle = water;
        roundRect(zone.x, zone.y, zone.width, zone.height, 20, true, false);
        ctx.strokeStyle = "rgba(170, 234, 255, 0.22)";
        ctx.lineWidth = 1.2;
        roundRect(zone.x + 0.5, zone.y + 0.5, zone.width - 1, zone.height - 1, 20, false, true);

        ctx.save();
        ctx.beginPath();
        roundRect(zone.x, zone.y, zone.width, zone.height, 20, false, false);
        ctx.clip();
        for (let row = 0; row < 5; row++) {
            ctx.beginPath();
            ctx.strokeStyle = `rgba(208, 244, 255, ${0.09 + row * 0.015})`;
            for (let x = zone.x - 10; x <= zone.x + zone.width + 10; x += 12) {
                const y = zone.y + 16 + row * 18 + Math.sin((x * 0.035) + now * 0.004 + row) * 4;
                if (x === zone.x - 10) {
                    ctx.moveTo(x, y);
                } else {
                    ctx.lineTo(x, y);
                }
            }
            ctx.stroke();
        }
        ctx.restore();
    }

    function drawMountainZone(zone, index, now) {
        const gradient = ctx.createLinearGradient(zone.x, zone.y, zone.x + zone.width, zone.y + zone.height);
        gradient.addColorStop(0, "#68798b");
        gradient.addColorStop(0.55, "#2a3948");
        gradient.addColorStop(1, "#16212c");
        ctx.fillStyle = gradient;
        roundRect(zone.x, zone.y, zone.width, zone.height, 18, true, false);
        ctx.strokeStyle = "rgba(230, 238, 248, 0.15)";
        ctx.lineWidth = 1.2;
        roundRect(zone.x + 0.5, zone.y + 0.5, zone.width - 1, zone.height - 1, 18, false, true);

        ctx.save();
        ctx.beginPath();
        roundRect(zone.x, zone.y, zone.width, zone.height, 18, false, false);
        ctx.clip();
        for (let i = 0; i < 7; i++) {
            const px = zone.x + (i * zone.width / 6) - 8;
            ctx.fillStyle = i % 2 === 0 ? "rgba(255,255,255,0.08)" : "rgba(0,0,0,0.16)";
            ctx.beginPath();
            ctx.moveTo(px, zone.y + zone.height);
            ctx.lineTo(px + zone.width / 7, zone.y + 18 + (i % 3) * 14);
            ctx.lineTo(px + zone.width / 3.8, zone.y + zone.height);
            ctx.closePath();
            ctx.fill();
        }
        ctx.restore();
    }

    function drawRuinsZone(zone, index, now) {
        const base = ctx.createLinearGradient(zone.x, zone.y, zone.x + zone.width, zone.y + zone.height);
        base.addColorStop(0, "#314a67");
        base.addColorStop(0.55, "#162536");
        base.addColorStop(1, "#0f1a27");
        ctx.fillStyle = base;
        roundRect(zone.x, zone.y, zone.width, zone.height, 12, true, false);
        ctx.strokeStyle = "rgba(170, 220, 255, 0.22)";
        ctx.lineWidth = 1.5;
        roundRect(zone.x + 0.5, zone.y + 0.5, zone.width - 1, zone.height - 1, 12, false, true);
        ctx.fillStyle = "rgba(255,255,255,0.05)";
        for (let y = zone.y + 12; y < zone.y + zone.height - 8; y += 14) {
            ctx.fillRect(zone.x + 10, y, zone.width - 20, 2);
        }
        const stripGlow = 0.18 + Math.sin(now * 0.002 + index) * 0.06;
        ctx.fillStyle = `rgba(94,234,212,${stripGlow})`;
        roundRect(zone.x + 8, zone.y + 8, Math.max(20, zone.width - 16), 6, 4, true, false);
        ctx.fillStyle = "rgba(255,255,255,0.08)";
        roundRect(zone.x + 14, zone.y + 16, Math.max(18, zone.width * 0.35), Math.max(16, zone.height * 0.16), 6, true, false);
    }

    function drawPine(x, y, size, color) {
        ctx.save();
        ctx.fillStyle = "rgba(36, 25, 18, 0.55)";
        ctx.fillRect(x - 2, y + size * 0.5, 4, size * 0.6);
        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.moveTo(x, y - size * 0.7);
        ctx.lineTo(x - size * 0.8, y + size * 0.2);
        ctx.lineTo(x + size * 0.8, y + size * 0.2);
        ctx.closePath();
        ctx.fill();
        ctx.beginPath();
        ctx.moveTo(x, y - size * 0.3);
        ctx.lineTo(x - size, y + size * 0.6);
        ctx.lineTo(x + size, y + size * 0.6);
        ctx.closePath();
        ctx.fill();
        ctx.restore();
    }

    function drawWakeRipples() {
        ctx.save();
        state.wakeRipples.forEach(item => {
            ctx.strokeStyle = toAlphaColor(item.color, item.life * 0.8);
            ctx.lineWidth = 1.6;
            ctx.beginPath();
            ctx.arc(item.x, item.y, item.radius, 0, Math.PI * 2);
            ctx.stroke();
        });
        ctx.restore();
    }

    function drawTrails() {
        ctx.save();
        ctx.globalCompositeOperation = "lighter";
        state.trails.forEach(item => {
            ctx.strokeStyle = toAlphaColor(item.color, item.life * 0.65);
            ctx.lineWidth = 2.2;
            ctx.beginPath();
            ctx.moveTo(item.x1, item.y1);
            ctx.lineTo(item.x2, item.y2);
            ctx.stroke();
        });
        ctx.restore();
    }

    function drawBullets() {
        state.bullets.forEach(item => {
            const glow = ctx.createRadialGradient(item.x + 4, item.y + 4, 1, item.x + 4, item.y + 4, 12);
            glow.addColorStop(0, "rgba(255,255,255,0.95)");
            glow.addColorStop(0.4, toAlphaColor(item.color || "#ffffff", 0.9));
            glow.addColorStop(1, "rgba(255,255,255,0)");
            ctx.fillStyle = glow;
            ctx.beginPath();
            ctx.arc(item.x + 4, item.y + 4, 12, 0, Math.PI * 2);
            ctx.fill();

            ctx.fillStyle = "#f8fafc";
            ctx.beginPath();
            ctx.arc(item.x + 4, item.y + 4, 3.8, 0, Math.PI * 2);
            ctx.fill();
        });
    }

    function drawPlayers(now) {
        const self = state.players.get(state.self);
        Array.from(state.players.values())
            .sort((a, b) => a.y - b.y)
            .forEach(player => {
                if (!player.alive) {
                    drawDestroyedTank(player, now);
                    return;
                }
                const visibleAlpha = visibilityForViewer(player, self);
                ctx.save();
                ctx.globalAlpha = visibleAlpha;
                drawTankShadow(player);
                drawTankHalo(player, now);
                drawTankBody(player, now);
                drawNameplate(player, visibleAlpha);
                if (player.username === state.self) {
                    drawSelfMarker(player, now);
                }
                ctx.restore();
            });
    }

    function visibilityForViewer(player, self) {
        if (!self || player.username === self.username) {
            return 1;
        }
        if (player.terrainType !== FOREST_TERRAIN) {
            return 1;
        }
        const dx = (player.x + 17) - (self.x + 17);
        const dy = (player.y + 17) - (self.y + 17);
        const distance = Math.sqrt((dx * dx) + (dy * dy));
        if (distance < 110) {
            return 0.76;
        }
        return 0.42;
    }

    function drawTankShadow(player) {
        ctx.save();
        ctx.globalAlpha = 0.42;
        ctx.fillStyle = "rgba(0,0,0,0.5)";
        ctx.beginPath();
        ctx.ellipse(player.x + 17, player.y + 28, 20, 10, 0, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();
    }

    function drawTankHalo(player, now) {
        const pulse = 0.35 + Math.sin(now * 0.004 + player.seatIndex) * 0.08;
        const glow = ctx.createRadialGradient(player.x + 17, player.y + 17, 6, player.x + 17, player.y + 17, 28);
        glow.addColorStop(0, toAlphaColor(player.color, pulse));
        glow.addColorStop(1, "rgba(0,0,0,0)");
        ctx.fillStyle = glow;
        ctx.beginPath();
        ctx.arc(player.x + 17, player.y + 17, 28, 0, Math.PI * 2);
        ctx.fill();
    }

    function drawTankBody(player, now) {
        const bevel = ctx.createLinearGradient(-17, -16, 17, 16);
        bevel.addColorStop(0, lightenColor(player.color, 0.3));
        bevel.addColorStop(0.48, player.color);
        bevel.addColorStop(1, darkenColor(player.color, 0.28));

        ctx.save();
        ctx.translate(player.x + 17, player.y + 17);
        ctx.rotate(directionAngle(player.direction));

        ctx.fillStyle = "rgba(255,255,255,0.08)";
        roundRect(-15, -11, 30, 22, 8, true, false, ctx);

        ctx.fillStyle = bevel;
        roundRect(-17, -14, 34, 28, 9, true, false, ctx);

        ctx.fillStyle = "rgba(5, 11, 20, 0.42)";
        roundRect(-15, -12, 30, 7, 5, true, false, ctx);

        ctx.fillStyle = "rgba(255,255,255,0.16)";
        roundRect(-12, -10, 24, 4, 3, true, false, ctx);

        ctx.fillStyle = "#c9f9ff";
        roundRect(-7, -8, 14, 14, 4, true, false, ctx);

        ctx.fillStyle = "rgba(255,255,255,0.22)";
        roundRect(-4, -5, 8, 4, 2, true, false, ctx);

        const barrel = ctx.createLinearGradient(0, -2, 20, 4);
        barrel.addColorStop(0, "#edf6ff");
        barrel.addColorStop(0.25, "#b9d8f6");
        barrel.addColorStop(1, "#42566e");
        ctx.fillStyle = barrel;
        roundRect(0, -3.5, 24, 7, 3.5, true, false, ctx);

        ctx.fillStyle = "rgba(255,255,255,0.18)";
        roundRect(6, -2, 10, 2, 1, true, false, ctx);

        ctx.fillStyle = "rgba(255,255,255,0.12)";
        roundRect(-18, -15, 10, 30, 5, true, false, ctx);
        roundRect(8, -15, 10, 30, 5, true, false, ctx);

        const energy = 0.4 + Math.sin(now * 0.006 + player.seatIndex) * 0.18;
        ctx.fillStyle = `rgba(255,255,255,${0.22 + energy * 0.3})`;
        roundRect(-13, 2, 26, 3, 2, true, false, ctx);

        if (player.terrainType === FOREST_TERRAIN) {
            ctx.fillStyle = "rgba(120,255,160,0.18)";
            roundRect(-16, -13, 32, 26, 8, true, false, ctx);
        }
        ctx.restore();
    }

    function drawDestroyedTank(player, now) {
        const pulse = 0.18 + Math.sin(now * 0.008 + player.seatIndex) * 0.05;
        ctx.fillStyle = "rgba(255,90,90,0.12)";
        ctx.beginPath();
        ctx.arc(player.x + 17, player.y + 17, 22 + pulse * 20, 0, Math.PI * 2);
        ctx.fill();

        ctx.fillStyle = "rgba(91, 104, 122, 0.6)";
        roundRect(player.x, player.y, 34, 34, 10, true, false);
        ctx.strokeStyle = "rgba(255,180,180,0.35)";
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(player.x + 6, player.y + 6);
        ctx.lineTo(player.x + 28, player.y + 28);
        ctx.moveTo(player.x + 28, player.y + 6);
        ctx.lineTo(player.x + 6, player.y + 28);
        ctx.stroke();

        ctx.fillStyle = "#ffd2d2";
        ctx.font = "12px Segoe UI";
        ctx.fillText("RESPAWN", player.x - 8, player.y - 10);
    }

    function drawNameplate(player, alpha) {
        const label = `${player.displayName} (${player.score})`;
        ctx.font = "12px Segoe UI";
        const width = ctx.measureText(label).width + 18;
        const x = player.x + 17 - width / 2;
        const y = player.y - 24;

        ctx.fillStyle = `rgba(8, 15, 26, ${0.52 + alpha * 0.2})`;
        roundRect(x, y, width, 18, 9, true, false);
        ctx.strokeStyle = toAlphaColor(player.color, 0.45 * alpha);
        ctx.lineWidth = 1;
        roundRect(x + 0.5, y + 0.5, width - 1, 17, 8.5, false, true);

        ctx.fillStyle = "#f8fafc";
        ctx.fillText(label, x + 9, y + 12.5);
    }

    function drawSelfMarker(player, now) {
        ctx.save();
        const alpha = 0.28 + Math.sin(now * 0.007) * 0.08;
        ctx.strokeStyle = `rgba(255,255,255,${alpha})`;
        ctx.lineWidth = 2;
        roundRect(player.x - 5, player.y - 5, 44, 44, 14, false, true);
        ctx.restore();
    }

    function drawDebris() {
        state.debris.forEach(item => {
            ctx.save();
            ctx.translate(item.x, item.y);
            ctx.rotate(item.rotation);
            ctx.fillStyle = toAlphaColor(item.color, Math.min(0.9, item.life));
            roundRect(-item.size / 2, -item.size / 3, item.size, item.size * 0.66, 2, true, false);
            ctx.restore();
        });
    }

    function drawEffects() {
        state.pulses.forEach(item => {
            ctx.strokeStyle = toAlphaColor(item.color, item.life * 0.9);
            ctx.lineWidth = 3;
            ctx.beginPath();
            ctx.arc(item.x, item.y, item.radius, 0, Math.PI * 2);
            ctx.stroke();
        });

        state.particles.forEach(item => {
            ctx.fillStyle = toAlphaColor(item.color, Math.max(0, item.life));
            ctx.beginPath();
            ctx.arc(item.x, item.y, Math.max(0.2, item.radius), 0, Math.PI * 2);
            ctx.fill();
        });
    }

    function drawForestFog(now) {
        const self = state.players.get(state.self);
        if (!self) {
            return;
        }
        ctx.save();
        state.players.forEach(player => {
            if (player.username === self.username || player.terrainType !== FOREST_TERRAIN || !player.alive) {
                return;
            }
            const alpha = visibilityForViewer(player, self);
            if (alpha >= 0.98) {
                return;
            }
            const shimmer = Math.sin(now * 0.003 + player.seatIndex) * 4;
            ctx.fillStyle = `rgba(6, 20, 10, ${0.18 + ((1 - alpha) * 0.25)})`;
            ctx.beginPath();
            ctx.ellipse(player.x + 17, player.y + 17, 40 + shimmer, 28 + shimmer * 0.4, 0, 0, Math.PI * 2);
            ctx.fill();
        });
        ctx.restore();
    }

    function drawVignette() {
        const gradient = ctx.createRadialGradient(
            battleCanvas.width / 2,
            battleCanvas.height / 2,
            battleCanvas.width * 0.22,
            battleCanvas.width / 2,
            battleCanvas.height / 2,
            battleCanvas.width * 0.72
        );
        gradient.addColorStop(0, "rgba(0,0,0,0)");
        gradient.addColorStop(1, "rgba(0,0,0,0.28)");
        ctx.fillStyle = gradient;
        ctx.fillRect(0, 0, battleCanvas.width, battleCanvas.height);
    }

    function roundRect(x, y, width, height, radius, fill, stroke, context = ctx) {
        context.beginPath();
        context.moveTo(x + radius, y);
        context.arcTo(x + width, y, x + width, y + height, radius);
        context.arcTo(x + width, y + height, x, y + height, radius);
        context.arcTo(x, y + height, x, y, radius);
        context.arcTo(x, y, x + width, y, radius);
        context.closePath();
        if (fill) {
            context.fill();
        }
        if (stroke) {
            context.stroke();
        }
    }

    function directionAngle(direction) {
        if (direction === "UP") {
            return -Math.PI / 2;
        }
        if (direction === "DOWN") {
            return Math.PI / 2;
        }
        if (direction === "LEFT") {
            return Math.PI;
        }
        return 0;
    }

    function playFireSound(color) {
        if (!state.audioEnabled) {
            return;
        }
        audioEngine.fire(color);
    }

    function playExplosionSound(power) {
        if (!state.audioEnabled) {
            return;
        }
        audioEngine.explosion(power);
    }

    function playImpactSound() {
        if (!state.audioEnabled) {
            return;
        }
        audioEngine.impact();
    }

    function createAudioEngine() {
        const AudioContextCtor = window.AudioContext || window.webkitAudioContext;
        let context = null;

        function ensureContext() {
            if (!AudioContextCtor) {
                return null;
            }
            if (!context) {
                context = new AudioContextCtor();
            }
            return context;
        }

        function unlock() {
            const ctxInstance = ensureContext();
            if (!ctxInstance) {
                return false;
            }
            if (ctxInstance.state === "suspended" && ctxInstance.resume) {
                ctxInstance.resume();
            }
            return true;
        }

        let bgmNodes = [];
        let bgmTimer = 0;

        function fire(color) {
            const ctxInstance = ensureContext();
            if (!ctxInstance) {
                return;
            }
            const now = ctxInstance.currentTime;
            const osc = ctxInstance.createOscillator();
            const gain = ctxInstance.createGain();
            const filter = ctxInstance.createBiquadFilter();
            osc.type = "sawtooth";
            osc.frequency.setValueAtTime(colorToFrequency(color), now);
            osc.frequency.exponentialRampToValueAtTime(90, now + 0.12);
            filter.type = "lowpass";
            filter.frequency.setValueAtTime(920, now);
            gain.gain.setValueAtTime(0.0001, now);
            gain.gain.exponentialRampToValueAtTime(0.08, now + 0.01);
            gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.14);
            osc.connect(filter);
            filter.connect(gain);
            gain.connect(ctxInstance.destination);
            osc.start(now);
            osc.stop(now + 0.16);
        }

        function explosion(power) {
            const ctxInstance = ensureContext();
            if (!ctxInstance) {
                return;
            }
            const now = ctxInstance.currentTime;
            const buffer = ctxInstance.createBuffer(1, ctxInstance.sampleRate * 0.4, ctxInstance.sampleRate);
            const channel = buffer.getChannelData(0);
            for (let i = 0; i < channel.length; i++) {
                channel[i] = (Math.random() * 2 - 1) * Math.pow(1 - (i / channel.length), 2);
            }
            const source = ctxInstance.createBufferSource();
            const filter = ctxInstance.createBiquadFilter();
            const gain = ctxInstance.createGain();
            source.buffer = buffer;
            filter.type = "lowpass";
            filter.frequency.setValueAtTime(220 + power * 300, now);
            gain.gain.setValueAtTime(0.0001, now);
            gain.gain.exponentialRampToValueAtTime(0.18 * power, now + 0.01);
            gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.38);
            source.connect(filter);
            filter.connect(gain);
            gain.connect(ctxInstance.destination);
            source.start(now);
        }

        function impact() {
            const ctxInstance = ensureContext();
            if (!ctxInstance) {
                return;
            }
            const now = ctxInstance.currentTime;
            const osc = ctxInstance.createOscillator();
            const gain = ctxInstance.createGain();
            osc.type = "triangle";
            osc.frequency.setValueAtTime(420, now);
            osc.frequency.exponentialRampToValueAtTime(120, now + 0.08);
            gain.gain.setValueAtTime(0.0001, now);
            gain.gain.exponentialRampToValueAtTime(0.05, now + 0.01);
            gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.09);
            osc.connect(gain);
            gain.connect(ctxInstance.destination);
            osc.start(now);
            osc.stop(now + 0.1);
        }

        function startBgm() {
            const ctxInstance = ensureContext();
            if (!ctxInstance || bgmTimer) {
                return;
            }
            playBgmBar();
        }

        function playBgmBar() {
            const ctxInstance = ensureContext();
            if (!ctxInstance) {
                return;
            }
            stopBgm();
            const now = ctxInstance.currentTime + 0.02;
            const master = ctxInstance.createGain();
            master.gain.setValueAtTime(0.038, now);
            master.connect(ctxInstance.destination);

            const drone = ctxInstance.createOscillator();
            const droneGain = ctxInstance.createGain();
            drone.type = "triangle";
            drone.frequency.setValueAtTime(73.42, now);
            droneGain.gain.setValueAtTime(0.0001, now);
            droneGain.gain.linearRampToValueAtTime(0.026, now + 0.6);
            droneGain.gain.linearRampToValueAtTime(0.02, now + 4.8);
            drone.connect(droneGain);
            droneGain.connect(master);
            drone.start(now);
            drone.stop(now + 5.2);

            const brass = ctxInstance.createOscillator();
            const brassGain = ctxInstance.createGain();
            const brassFilter = ctxInstance.createBiquadFilter();
            brass.type = "sawtooth";
            brass.frequency.setValueAtTime(146.84, now + 0.2);
            brass.frequency.setValueAtTime(164.81, now + 2.1);
            brass.frequency.setValueAtTime(130.81, now + 3.6);
            brassGain.gain.setValueAtTime(0.0001, now);
            brassGain.gain.linearRampToValueAtTime(0.018, now + 0.45);
            brassGain.gain.linearRampToValueAtTime(0.012, now + 4.7);
            brassFilter.type = "lowpass";
            brassFilter.frequency.setValueAtTime(640, now);
            brass.connect(brassFilter);
            brassFilter.connect(brassGain);
            brassGain.connect(master);
            brass.start(now + 0.18);
            brass.stop(now + 5.0);

            for (let i = 0; i < 4; i++) {
                const hit = ctxInstance.createOscillator();
                const hitGain = ctxInstance.createGain();
                hit.type = "sine";
                hit.frequency.setValueAtTime(58 + (i % 2) * 10, now + i * 1.2);
                hitGain.gain.setValueAtTime(0.0001, now + i * 1.2);
                hitGain.gain.exponentialRampToValueAtTime(0.09, now + i * 1.2 + 0.03);
                hitGain.gain.exponentialRampToValueAtTime(0.0001, now + i * 1.2 + 0.28);
                hit.connect(hitGain);
                hitGain.connect(master);
                hit.start(now + i * 1.2);
                hit.stop(now + i * 1.2 + 0.3);
                bgmNodes.push(hit, hitGain);
            }

            bgmNodes.push(master, drone, droneGain, brass, brassGain, brassFilter);
            bgmTimer = setTimeout(playBgmBar, 4600);
        }

        function stopBgm() {
            if (bgmTimer) {
                clearTimeout(bgmTimer);
                bgmTimer = 0;
            }
            bgmNodes.forEach(node => {
                if (node && typeof node.stop === "function") {
                    try {
                        node.stop();
                    } catch (error) {
                        console.debug(error);
                    }
                }
                if (node && typeof node.disconnect === "function") {
                    try {
                        node.disconnect();
                    } catch (error) {
                        console.debug(error);
                    }
                }
            });
            bgmNodes = [];
        }

        function colorToFrequency(color) {
            const hex = normalizeHex(color || "#ffffff");
            const r = parseInt(hex.slice(1, 3), 16);
            const g = parseInt(hex.slice(3, 5), 16);
            const b = parseInt(hex.slice(5, 7), 16);
            return 160 + ((r + g + b) / 765) * 160;
        }

        return { unlock, fire, explosion, impact, startBgm, stopBgm };
    }

    function lightenColor(hex, amount) {
        return shiftColor(hex, Math.round(255 * amount));
    }

    function darkenColor(hex, amount) {
        return shiftColor(hex, -Math.round(255 * amount));
    }

    function shiftColor(hex, delta) {
        const color = normalizeHex(hex);
        const r = clampColor(parseInt(color.slice(1, 3), 16) + delta);
        const g = clampColor(parseInt(color.slice(3, 5), 16) + delta);
        const b = clampColor(parseInt(color.slice(5, 7), 16) + delta);
        return `rgb(${r}, ${g}, ${b})`;
    }

    function toAlphaColor(color, alpha) {
        if (!color) {
            return `rgba(255,255,255,${alpha})`;
        }
        if (color.startsWith("rgba")) {
            const parts = color.slice(5, -1).split(",").map(item => item.trim());
            return `rgba(${parts[0]}, ${parts[1]}, ${parts[2]}, ${alpha})`;
        }
        if (color.startsWith("rgb")) {
            return color.replace("rgb(", "rgba(").replace(")", `, ${alpha})`);
        }
        const hex = normalizeHex(color);
        const r = parseInt(hex.slice(1, 3), 16);
        const g = parseInt(hex.slice(3, 5), 16);
        const b = parseInt(hex.slice(5, 7), 16);
        return `rgba(${r}, ${g}, ${b}, ${alpha})`;
    }

    function normalizeHex(color) {
        if (!color || !color.startsWith("#")) {
            return "#ffffff";
        }
        if (color.length === 4) {
            return `#${color[1]}${color[1]}${color[2]}${color[2]}${color[3]}${color[3]}`;
        }
        return color;
    }

    function clampColor(value) {
        return Math.max(0, Math.min(255, value));
    }

    function animationLoop(now) {
        const elapsed = now - lastRenderTime;
        const deltaSeconds = Math.min(0.05, (now - lastEffectTime) / 1000);
        updateEffects(deltaSeconds, now);
        if (elapsed >= Math.max(16, state.tickRateMs / 2)) {
            processFrames();
            drawScene(now);
            lastRenderTime = now;
        } else {
            drawScene(now);
        }
        lastEffectTime = now;
        animationFrameId = requestAnimationFrame(animationLoop);
    }

    animationFrameId = requestAnimationFrame(animationLoop);

    window.addEventListener("beforeunload", () => {
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
        }
        audioEngine.stopBgm();
        if (animationFrameId) {
            cancelAnimationFrame(animationFrameId);
        }
    });
})();
