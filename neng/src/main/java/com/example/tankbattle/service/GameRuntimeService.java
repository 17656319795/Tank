package com.example.tankbattle.service;

import com.example.tankbattle.config.TankBattleProperties;
import com.example.tankbattle.domain.Direction;
import com.example.tankbattle.domain.GameRoom;
import com.example.tankbattle.domain.PlayerProfile;
import com.example.tankbattle.domain.RoomStatus;
import com.example.tankbattle.netty.GameWebSocketHandler;
import com.example.tankbattle.repository.GameRoomRepository;
import com.example.tankbattle.repository.PlayerProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class GameRuntimeService {

    private static final double TANK_SIZE = 34D;
    private static final double BULLET_SIZE = 8D;
    private static final double PLAYER_SPEED = 4.5D;
    private static final double BULLET_SPEED = 12D;
    private static final int SNAPSHOT_INTERVAL_FRAMES = 6;
    private static final double FOREST_SPEED_MULTIPLIER = 0.68D;
    private static final double FOREST_VISIBILITY_MULTIPLIER = 0.58D;
    private static final List<String> TANK_COLORS = Arrays.asList("#f25f5c", "#247ba0", "#70c1b3", "#ffe066", "#f59e0b", "#a78bfa");
    private static final List<String> BOT_NAMES = Arrays.asList("铁狼AI", "灰鹰AI", "北境AI", "钢牙AI", "霜火AI", "猎隼AI");

    private final TankBattleProperties properties;
    private final PlayerSessionService playerSessionService;
    private final GameRoomRepository gameRoomRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService gameLoop;
    private final Map<String, RuntimeRoom> runtimeRooms = new ConcurrentHashMap<String, RuntimeRoom>();

    public GameRuntimeService(TankBattleProperties properties,
                              PlayerSessionService playerSessionService,
                              GameRoomRepository gameRoomRepository,
                              PlayerProfileRepository playerProfileRepository,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.playerSessionService = playerSessionService;
        this.gameRoomRepository = gameRoomRepository;
        this.playerProfileRepository = playerProfileRepository;
        this.objectMapper = objectMapper;
        this.gameLoop = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "tankbattle-frame-loop");
                thread.setDaemon(true);
                return thread;
            }
        });
        this.gameLoop.scheduleAtFixedRate(this::tickAllRooms, properties.getTickRateMs(), properties.getTickRateMs(), TimeUnit.MILLISECONDS);
    }

    public void join(String token, String roomCode, Channel channel) {
        if (roomCode == null || roomCode.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "房间号不能为空");
        }
        PlayerSessionService.SessionPlayer sessionPlayer = playerSessionService.requirePlayer(token);
        GameRoom persistedRoom = gameRoomRepository.findByRoomCode(roomCode.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "房间不存在"));
        RuntimeRoom room = runtimeRooms.computeIfAbsent(roomCode.trim(), key -> createRoom(persistedRoom));
        synchronized (room.monitor) {
            room.pendingDestroyAt = 0L;
            ensureBots(room);
            PlayerRuntime player = room.players.get(sessionPlayer.getUsername());
            if (player == null && countHumanPlayers(room) >= room.humanCapacity) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "房间已满");
            }
            if (player == null) {
                player = createHumanPlayer(room, sessionPlayer.getUsername(), sessionPlayer.getDisplayName(), humanSeatCount(room));
                room.players.put(player.username, player);
                room.seatOrder.add(player.username);
                room.logs.add(0, sessionPlayer.getDisplayName() + " 已进入房间");
            } else {
                if (player.channel != null && player.channel != channel && player.channel.isActive()) {
                    sendToChannel(player.channel, "system", "该账号已在新的页面登录，本页面控制权已转移");
                    player.channel.close();
                }
                player.displayName = sessionPlayer.getDisplayName();
                player.connected = true;
                player.channel = channel;
                player.awaitReconnectUntil = 0L;
                room.logs.add(0, sessionPlayer.getDisplayName() + " 已重新连接");
            }
            player.channel = channel;
            player.connected = true;
            player.lastSeenAt = System.currentTimeMillis();
            channel.attr(GameWebSocketHandler.ROOM_CODE).set(room.roomCode);
            channel.attr(GameWebSocketHandler.USERNAME).set(player.username);
            ensureRoomStartability(room);
            syncRoomRecord(room, room.status, room.lastWinner);
            sendWelcome(channel, room, player.username);
            broadcastRoomMeta(room);
            broadcastSnapshot(room, true);
            broadcastSystem(room, player.displayName + " 已加入战场");
        }
    }

    public void removeChannel(Channel channel) {
        if (channel == null) {
            return;
        }
        String roomCode = channel.attr(GameWebSocketHandler.ROOM_CODE).get();
        String username = channel.attr(GameWebSocketHandler.USERNAME).get();
        if (roomCode == null || username == null) {
            return;
        }
        RuntimeRoom room = runtimeRooms.get(roomCode);
        if (room == null) {
            return;
        }
        synchronized (room.monitor) {
            PlayerRuntime player = room.players.get(username);
            if (player == null || player.channel != channel) {
                return;
            }
            player.channel = null;
            player.connected = false;
            player.lastSeenAt = System.currentTimeMillis();
            player.awaitReconnectUntil = player.lastSeenAt + (properties.getReconnectGraceSeconds() * 1000L);
            player.pendingInput = PlayerInput.idle();
            room.logs.add(0, player.displayName + " 已离线，等待重连");
            if (countConnectedHumanPlayers(room) == 0) {
                scheduleRoomDestroy(room);
            }
            ensureRoomStartability(room);
            syncRoomRecord(room, room.status, room.lastWinner);
            broadcastRoomMeta(room);
            broadcastSnapshot(room, true);
            broadcastSystem(room, player.displayName + " 已离线，可在宽限期内重连");
        }
    }

    public void updateInput(String roomCode, String username, boolean up, boolean down, boolean left, boolean right) {
        RuntimeRoom room = runtimeRooms.get(roomCode);
        if (room == null) {
            return;
        }
        synchronized (room.monitor) {
            PlayerRuntime player = room.players.get(username);
            if (player == null || player.bot) {
                return;
            }
            player.lastSeenAt = System.currentTimeMillis();
            player.pendingInput = new PlayerInput(up, down, left, right, player.pendingInput.fireRequested);
        }
    }

    public void fire(String roomCode, String username) {
        RuntimeRoom room = runtimeRooms.get(roomCode);
        if (room == null) {
            return;
        }
        synchronized (room.monitor) {
            PlayerRuntime player = room.players.get(username);
            if (player == null || player.bot) {
                return;
            }
            player.lastSeenAt = System.currentTimeMillis();
            player.pendingInput = new PlayerInput(
                    player.pendingInput.up,
                    player.pendingInput.down,
                    player.pendingInput.left,
                    player.pendingInput.right,
                    true
            );
        }
    }

    public void startMatch(String roomCode, String username) {
        RuntimeRoom room = runtimeRooms.get(roomCode);
        if (room == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "房间不存在");
        }
        synchronized (room.monitor) {
            ensureBots(room);
            ensureRoomStartability(room);
            if (!canStart(room, username)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有房主在线时由房主开局，房主离线后由任一在场玩家开局");
            }
            if (countConnectedParticipants(room) < 2) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少需要 2 个作战单位才能开始");
            }
            room.status = RoomStatus.RUNNING;
            room.pendingDestroyAt = 0L;
            room.startedAt = System.currentTimeMillis();
            room.endsAt = room.startedAt + (properties.getGameDurationSeconds() * 1000L);
            room.frameId = 0L;
            room.bullets.clear();
            room.lastWinner = null;
            resetPlayersForNewMatch(room);
            syncRoomRecord(room, RoomStatus.RUNNING, null);
            broadcastSystem(room, "对局开始，当前已切换为帧同步输入模式");
            broadcastRoomMeta(room);
            broadcastSnapshot(room, true);
        }
    }

    public int getCurrentPlayers(String roomCode) {
        RuntimeRoom room = runtimeRooms.get(roomCode);
        return room == null ? -1 : countConnectedHumanPlayers(room);
    }

    public RoomStatus getCurrentStatus(String roomCode) {
        RuntimeRoom room = runtimeRooms.get(roomCode);
        return room == null ? null : room.status;
    }

    public boolean isPlayerInRoom(String roomCode, String username) {
        RuntimeRoom room = runtimeRooms.get(roomCode);
        return room != null && room.players.containsKey(username);
    }

    public void sendToChannel(Channel channel, String type, String message) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", type);
        payload.put("message", message);
        write(channel, payload);
    }

    @PreDestroy
    public void shutdown() {
        gameLoop.shutdownNow();
    }

    private void tickAllRooms() {
        Collection<RuntimeRoom> rooms = runtimeRooms.values();
        for (RuntimeRoom room : rooms) {
            synchronized (room.monitor) {
                purgeInactiveConnections(room);
                purgeExpiredRoomIfNeeded(room);
                if (!runtimeRooms.containsKey(room.roomCode)) {
                    continue;
                }
                if (room.status != RoomStatus.RUNNING) {
                    continue;
                }
                room.frameId++;
                long now = System.currentTimeMillis();
                prepareBotInputs(room, now);
                FrameBundle frameBundle = captureFrameInputs(room);
                applyFrame(room, frameBundle, now);
                broadcastFrame(room, frameBundle, now);
                if (room.frameId % SNAPSHOT_INTERVAL_FRAMES == 0L) {
                    broadcastSnapshot(room, false);
                }
                if (room.status == RoomStatus.RUNNING && now >= room.endsAt) {
                    finishMatch(room, chooseWinnerByScore(room), "时间结束");
                }
            }
        }
    }

    private void purgeInactiveConnections(RuntimeRoom room) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (PlayerRuntime player : room.players.values()) {
            if (player.bot) {
                continue;
            }
            if (player.connected && player.channel != null && !player.channel.isActive()) {
                player.channel = null;
                player.connected = false;
                player.pendingInput = PlayerInput.idle();
                player.awaitReconnectUntil = now + (properties.getReconnectGraceSeconds() * 1000L);
                player.lastSeenAt = now;
                changed = true;
            }
            if (!player.connected && player.awaitReconnectUntil > 0L && now >= player.awaitReconnectUntil) {
                player.awaitReconnectUntil = 0L;
                if (room.status == RoomStatus.RUNNING && countConnectedParticipants(room) <= 1) {
                    PlayerRuntime winner = findFirstLivingParticipant(room);
                    finishMatch(room, winner, "对手重连超时");
                }
                changed = true;
            }
        }
        if (countConnectedHumanPlayers(room) == 0) {
            scheduleRoomDestroy(room);
        } else {
            room.pendingDestroyAt = 0L;
        }
        if (changed) {
            ensureRoomStartability(room);
            syncRoomRecord(room, room.status, room.lastWinner);
            broadcastRoomMeta(room);
            broadcastSnapshot(room, true);
        }
    }

    private void purgeExpiredRoomIfNeeded(RuntimeRoom room) {
        if (room.pendingDestroyAt <= 0L) {
            return;
        }
        if (System.currentTimeMillis() < room.pendingDestroyAt) {
            return;
        }
        runtimeRooms.remove(room.roomCode);
        gameRoomRepository.findByRoomCode(room.roomCode).ifPresent(gameRoomRepository::delete);
    }

    private void scheduleRoomDestroy(RuntimeRoom room) {
        if (room.pendingDestroyAt <= 0L) {
            room.pendingDestroyAt = System.currentTimeMillis() + (properties.getRoomEmptyDestroySeconds() * 1000L);
        }
        room.status = RoomStatus.WAITING;
        room.bullets.clear();
    }

    private void prepareBotInputs(RuntimeRoom room, long now) {
        for (PlayerRuntime player : room.players.values()) {
            if (!player.bot || !player.connected) {
                continue;
            }
            if (!player.alive) {
                player.pendingInput = PlayerInput.idle();
                continue;
            }
            PlayerRuntime target = chooseBotTarget(room, player);
            if (target == null) {
                player.pendingInput = PlayerInput.idle();
                continue;
            }
            if (now >= player.nextBotDecisionAt) {
                player.pendingInput = buildBotInput(room, player, target);
                player.nextBotDecisionAt = now + 160L + (player.botIndex * 35L);
            }
        }
    }

    private PlayerRuntime chooseBotTarget(RuntimeRoom room, PlayerRuntime bot) {
        PlayerRuntime selected = null;
        double minDistance = Double.MAX_VALUE;
        for (PlayerRuntime candidate : room.players.values()) {
            if (!candidate.connected || !candidate.alive || Objects.equals(candidate.username, bot.username)) {
                continue;
            }
            double dx = candidate.x - bot.x;
            double dy = candidate.y - bot.y;
            double distance = (dx * dx) + (dy * dy);
            if (distance < minDistance) {
                minDistance = distance;
                selected = candidate;
            }
        }
        return selected;
    }

    private PlayerInput buildBotInput(RuntimeRoom room, PlayerRuntime bot, PlayerRuntime target) {
        double botCenterX = bot.x + (TANK_SIZE / 2D);
        double botCenterY = bot.y + (TANK_SIZE / 2D);
        double targetCenterX = target.x + (TANK_SIZE / 2D);
        double targetCenterY = target.y + (TANK_SIZE / 2D);
        boolean up = false;
        boolean down = false;
        boolean left = false;
        boolean right = false;
        boolean fire = false;

        if (Math.abs(targetCenterX - botCenterX) > 22D) {
            right = targetCenterX > botCenterX;
            left = targetCenterX < botCenterX;
        }
        if (Math.abs(targetCenterY - botCenterY) > 22D) {
            down = targetCenterY > botCenterY;
            up = targetCenterY < botCenterY;
        }
        if (Math.abs(targetCenterX - botCenterX) > Math.abs(targetCenterY - botCenterY)) {
            up = false;
            down = false;
            bot.direction = targetCenterX > botCenterX ? Direction.RIGHT : Direction.LEFT;
        } else {
            left = false;
            right = false;
            bot.direction = targetCenterY > botCenterY ? Direction.DOWN : Direction.UP;
        }

        if (hitsBlockingTerrain(room.terrainZones,
                bot.x + bot.direction.getDx() * PLAYER_SPEED,
                bot.y + bot.direction.getDy() * PLAYER_SPEED,
                TANK_SIZE,
                TANK_SIZE,
                true)) {
            up = bot.direction == Direction.LEFT;
            down = bot.direction == Direction.RIGHT;
            left = bot.direction == Direction.DOWN;
            right = bot.direction == Direction.UP;
        }

        double distance = Math.sqrt(Math.pow(targetCenterX - botCenterX, 2) + Math.pow(targetCenterY - botCenterY, 2));
        if (distance < 280D && clearLineForBot(room, bot, target)) {
            fire = true;
        }
        return new PlayerInput(up, down, left, right, fire);
    }

    private boolean clearLineForBot(RuntimeRoom room, PlayerRuntime bot, PlayerRuntime target) {
        double botCenterX = bot.x + (TANK_SIZE / 2D);
        double botCenterY = bot.y + (TANK_SIZE / 2D);
        double targetCenterX = target.x + (TANK_SIZE / 2D);
        double targetCenterY = target.y + (TANK_SIZE / 2D);
        if (Math.abs(targetCenterX - botCenterX) > 24D && Math.abs(targetCenterY - botCenterY) > 24D) {
            return false;
        }
        int samples = 8;
        for (int i = 1; i <= samples; i++) {
            double t = i / (double) samples;
            double sampleX = botCenterX + ((targetCenterX - botCenterX) * t);
            double sampleY = botCenterY + ((targetCenterY - botCenterY) * t);
            if (hitsBlockingTerrain(room.terrainZones, sampleX, sampleY, BULLET_SIZE, BULLET_SIZE, false)) {
                return false;
            }
        }
        return true;
    }

    private FrameBundle captureFrameInputs(RuntimeRoom room) {
        FrameBundle bundle = new FrameBundle();
        bundle.frameId = room.frameId;
        bundle.inputs = new ArrayList<Map<String, Object>>();
        for (String username : room.seatOrder) {
            PlayerRuntime player = room.players.get(username);
            if (player == null || !player.connected) {
                continue;
            }
            PlayerInput input = player.pendingInput;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("username", player.username);
            item.put("up", input.up);
            item.put("down", input.down);
            item.put("left", input.left);
            item.put("right", input.right);
            item.put("fire", input.fireRequested);
            bundle.inputs.add(item);
        }
        return bundle;
    }

    private void applyFrame(RuntimeRoom room, FrameBundle bundle, long now) {
        Map<String, PlayerInput> frameInputs = new HashMap<String, PlayerInput>();
        for (Map<String, Object> item : bundle.inputs) {
            frameInputs.put(String.valueOf(item.get("username")), new PlayerInput(
                    Boolean.TRUE.equals(item.get("up")),
                    Boolean.TRUE.equals(item.get("down")),
                    Boolean.TRUE.equals(item.get("left")),
                    Boolean.TRUE.equals(item.get("right")),
                    Boolean.TRUE.equals(item.get("fire"))
            ));
        }
        for (PlayerRuntime player : room.players.values()) {
            if (!player.connected) {
                continue;
            }
            PlayerInput input = frameInputs.get(player.username);
            if (input == null) {
                input = PlayerInput.idle();
            }
            player.lastAppliedInput = input;
            if (!player.alive) {
                if (player.respawnAt > 0 && now >= player.respawnAt) {
                    respawnPlayer(room, player);
                }
            } else {
                movePlayer(room, player, input);
                if (input.fireRequested) {
                    spawnBulletIfPossible(room, player, now);
                }
            }
            player.pendingInput = new PlayerInput(input.up, input.down, input.left, input.right, false);
        }
        updateBullets(room, now);
    }

    private void movePlayer(RuntimeRoom room, PlayerRuntime player, PlayerInput input) {
        double movementSpeed = PLAYER_SPEED * movementSpeedMultiplier(room.terrainZones, player.x, player.y, TANK_SIZE, TANK_SIZE);
        double nextX = player.x;
        double nextY = player.y;
        if (input.up && !input.down) {
            nextY -= movementSpeed;
            player.direction = Direction.UP;
        }
        if (input.down && !input.up) {
            nextY += movementSpeed;
            player.direction = Direction.DOWN;
        }
        if (input.left && !input.right) {
            nextX -= movementSpeed;
            player.direction = Direction.LEFT;
        }
        if (input.right && !input.left) {
            nextX += movementSpeed;
            player.direction = Direction.RIGHT;
        }
        nextX = clamp(nextX, 0D, properties.getMapWidth() - TANK_SIZE);
        nextY = clamp(nextY, 0D, properties.getMapHeight() - TANK_SIZE);
        if (!hitsBlockingTerrain(room.terrainZones, nextX, nextY, TANK_SIZE, TANK_SIZE, true)) {
            player.x = nextX;
            player.y = nextY;
        }
    }

    private void spawnBulletIfPossible(RuntimeRoom room, PlayerRuntime player, long now) {
        if (!player.alive) {
            return;
        }
        if (now - player.lastFireAt < 350L) {
            return;
        }
        player.lastFireAt = now;
        BulletRuntime bullet = new BulletRuntime();
        bullet.id = room.bulletSequence.incrementAndGet();
        bullet.ownerUsername = player.username;
        bullet.x = player.x + (TANK_SIZE / 2D) - (BULLET_SIZE / 2D);
        bullet.y = player.y + (TANK_SIZE / 2D) - (BULLET_SIZE / 2D);
        bullet.direction = player.direction;
        room.bullets.add(bullet);
    }

    private void updateBullets(RuntimeRoom room, long now) {
        Iterator<BulletRuntime> iterator = room.bullets.iterator();
        while (iterator.hasNext()) {
            BulletRuntime bullet = iterator.next();
            bullet.x += bullet.direction.getDx() * BULLET_SPEED;
            bullet.y += bullet.direction.getDy() * BULLET_SPEED;
            if (bullet.x < 0 || bullet.y < 0
                    || bullet.x > properties.getMapWidth()
                    || bullet.y > properties.getMapHeight()
                    || hitsBlockingTerrain(room.terrainZones, bullet.x, bullet.y, BULLET_SIZE, BULLET_SIZE, false)) {
                iterator.remove();
                continue;
            }
            PlayerRuntime owner = room.players.get(bullet.ownerUsername);
            for (PlayerRuntime target : room.players.values()) {
                if (!target.connected || !target.alive || Objects.equals(target.username, bullet.ownerUsername)) {
                    continue;
                }
                if (intersects(bullet.x, bullet.y, BULLET_SIZE, BULLET_SIZE, target.x, target.y, TANK_SIZE, TANK_SIZE)) {
                    iterator.remove();
                    target.alive = false;
                    target.respawnAt = now + properties.getRespawnDelayMs();
                    target.deaths++;
                    target.pendingInput = PlayerInput.idle();
                    if (owner != null) {
                        owner.score++;
                    }
                    room.logs.add(0, (owner == null ? "未知玩家" : owner.displayName) + " 击毁了 " + target.displayName);
                    if (owner != null && owner.score >= properties.getTargetScore()) {
                        finishMatch(room, owner, "达到目标分数");
                    }
                    break;
                }
            }
        }
    }

    private void broadcastFrame(RuntimeRoom room, FrameBundle bundle, long now) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "frame");
        payload.put("frameId", bundle.frameId);
        payload.put("inputs", bundle.inputs);
        payload.put("serverTime", now);
        broadcast(room, payload);
    }

    private void finishMatch(RuntimeRoom room, PlayerRuntime winner, String reason) {
        if (room.status != RoomStatus.RUNNING) {
            return;
        }
        room.status = RoomStatus.FINISHED;
        room.lastWinner = winner == null ? "平局" : winner.displayName;
        room.bullets.clear();
        for (PlayerRuntime player : room.players.values()) {
            player.pendingInput = PlayerInput.idle();
            player.lastAppliedInput = PlayerInput.idle();
            player.alive = true;
            player.respawnAt = 0L;
        }
        syncRoomRecord(room, RoomStatus.FINISHED, room.lastWinner);
        recordPlayerResults(room, winner);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "gameOver");
        payload.put("reason", reason);
        payload.put("winner", room.lastWinner);
        payload.put("serverTime", Instant.now().toEpochMilli());
        broadcast(room, payload);
        broadcastRoomMeta(room);
        broadcastSnapshot(room, true);
        broadcastSystem(room, "本局结束: " + reason + "，胜者: " + room.lastWinner);
    }

    private void recordPlayerResults(RuntimeRoom room, PlayerRuntime winner) {
        for (PlayerRuntime player : room.players.values()) {
            if (player.bot) {
                continue;
            }
            if (!player.connected && player.score == 0 && player.deaths == 0) {
                continue;
            }
            playerProfileRepository.findByUsername(player.username)
                    .ifPresent(profile -> updateProfile(winner, player, profile));
        }
    }

    private void updateProfile(PlayerRuntime winner, PlayerRuntime player, PlayerProfile profile) {
        int totalMatches = profile.getTotalMatches() == null ? 0 : profile.getTotalMatches();
        int wins = profile.getWins() == null ? 0 : profile.getWins();
        int points = profile.getPoints() == null ? 0 : profile.getPoints();
        profile.setTotalMatches(totalMatches + 1);
        if (winner != null && Objects.equals(winner.username, player.username)) {
            profile.setWins(wins + 1);
            profile.setPoints(points + 12 + (player.score * 2));
        } else {
            profile.setWins(wins);
            profile.setPoints(points + 3 + player.score);
        }
        profile.setDisplayName(player.displayName);
        playerProfileRepository.save(profile);
    }

    private PlayerRuntime chooseWinnerByScore(RuntimeRoom room) {
        List<PlayerRuntime> sorted = new ArrayList<PlayerRuntime>();
        for (PlayerRuntime player : room.players.values()) {
            if (player.connected) {
                sorted.add(player);
            }
        }
        Collections.sort(sorted, Comparator.comparingInt(PlayerRuntime::getScore)
                .thenComparingInt(PlayerRuntime::getDeaths)
                .reversed());
        return sorted.isEmpty() ? null : sorted.get(0);
    }

    private void resetPlayersForNewMatch(RuntimeRoom room) {
        int index = 0;
        for (String username : room.seatOrder) {
            PlayerRuntime player = room.players.get(username);
            if (player == null || !player.connected) {
                continue;
            }
            player.score = 0;
            player.deaths = 0;
            player.lastFireAt = 0L;
            player.respawnAt = 0L;
            player.alive = true;
            player.pendingInput = PlayerInput.idle();
            player.lastAppliedInput = PlayerInput.idle();
            placeAtSpawn(room, player, index++);
        }
    }

    private RuntimeRoom createRoom(GameRoom persistedRoom) {
        RuntimeRoom room = new RuntimeRoom();
        room.roomCode = persistedRoom.getRoomCode();
        room.roomName = persistedRoom.getRoomName();
        room.ownerUsername = persistedRoom.getOwnerUsername();
        room.ownerDisplayName = persistedRoom.getOwnerDisplayName();
        room.maxPlayers = persistedRoom.getMaxPlayers();
        room.botCount = persistedRoom.getBotCount() == null ? 0 : persistedRoom.getBotCount();
        room.humanCapacity = Math.max(1, room.maxPlayers - room.botCount);
        room.status = persistedRoom.getStatus() == null ? RoomStatus.WAITING : persistedRoom.getStatus();
        room.terrainZones = defaultTerrainZones();
        room.lastWinner = persistedRoom.getLastWinner();
        ensureBots(room);
        return room;
    }

    private void ensureBots(RuntimeRoom room) {
        for (int i = 0; i < room.botCount; i++) {
            String username = "bot_" + room.roomCode + "_" + i;
            if (room.players.containsKey(username)) {
                continue;
            }
            PlayerRuntime bot = createBotPlayer(room, username, i);
            room.players.put(username, bot);
            room.seatOrder.add(username);
        }
    }

    private PlayerRuntime createHumanPlayer(RuntimeRoom room, String username, String displayName, int spawnIndex) {
        PlayerRuntime player = createBasePlayer(username, displayName, spawnIndex);
        player.bot = false;
        player.connected = true;
        player.alive = true;
        return player;
    }

    private PlayerRuntime createBotPlayer(RuntimeRoom room, String username, int botIndex) {
        String displayName = BOT_NAMES.get(botIndex % BOT_NAMES.size()) + " " + (botIndex + 1);
        PlayerRuntime player = createBasePlayer(username, displayName, humanSeatCount(room) + botIndex);
        player.bot = true;
        player.botIndex = botIndex;
        player.connected = true;
        player.alive = true;
        player.nextBotDecisionAt = System.currentTimeMillis();
        return player;
    }

    private PlayerRuntime createBasePlayer(String username, String displayName, int spawnIndex) {
        PlayerRuntime player = new PlayerRuntime();
        player.username = username;
        player.displayName = displayName;
        player.color = TANK_COLORS.get(Math.max(0, spawnIndex) % TANK_COLORS.size());
        player.direction = Direction.UP;
        player.pendingInput = PlayerInput.idle();
        player.lastAppliedInput = PlayerInput.idle();
        return player;
    }

    private void placeAtSpawn(RuntimeRoom room, PlayerRuntime player, int spawnIndex) {
        double[][] spawns = new double[][]{
                {64D, 64D},
                {properties.getMapWidth() - 98D, 64D},
                {64D, properties.getMapHeight() - 98D},
                {properties.getMapWidth() - 98D, properties.getMapHeight() - 98D}
        };
        double[] point = spawns[Math.max(0, spawnIndex) % spawns.length];
        player.x = point[0];
        player.y = point[1];
    }

    private void respawnPlayer(RuntimeRoom room, PlayerRuntime player) {
        player.alive = true;
        player.respawnAt = 0L;
        placeAtSpawn(room, player, seatIndexOf(room, player.username));
    }

    private void ensureRoomStartability(RuntimeRoom room) {
        if (room.status == RoomStatus.RUNNING) {
            return;
        }
        room.status = RoomStatus.WAITING;
    }

    private boolean canStart(RuntimeRoom room, String username) {
        if (countConnectedParticipants(room) < 2) {
            return false;
        }
        PlayerRuntime owner = room.players.get(room.ownerUsername);
        if (owner != null && owner.connected) {
            return Objects.equals(room.ownerUsername, username);
        }
        PlayerRuntime player = room.players.get(username);
        return player != null && player.connected;
    }

    private int countConnectedHumanPlayers(RuntimeRoom room) {
        int count = 0;
        for (PlayerRuntime player : room.players.values()) {
            if (!player.bot && player.connected) {
                count++;
            }
        }
        return count;
    }

    private int countConnectedParticipants(RuntimeRoom room) {
        int count = 0;
        for (PlayerRuntime player : room.players.values()) {
            if (player.connected) {
                count++;
            }
        }
        return count;
    }

    private int countHumanPlayers(RuntimeRoom room) {
        int count = 0;
        for (PlayerRuntime player : room.players.values()) {
            if (!player.bot) {
                count++;
            }
        }
        return count;
    }

    private int humanSeatCount(RuntimeRoom room) {
        int count = 0;
        for (String username : room.seatOrder) {
            PlayerRuntime player = room.players.get(username);
            if (player != null && !player.bot) {
                count++;
            }
        }
        return count;
    }

    private PlayerRuntime findFirstLivingParticipant(RuntimeRoom room) {
        for (String username : room.seatOrder) {
            PlayerRuntime player = room.players.get(username);
            if (player != null && player.connected && player.alive) {
                return player;
            }
        }
        return null;
    }

    private List<TerrainZone> defaultTerrainZones() {
        List<TerrainZone> terrain = new ArrayList<TerrainZone>();
        terrain.add(blockingTerrain(TerrainType.MOUNTAIN, 204D, 96D, 124D, 236D));
        terrain.add(blockingTerrain(TerrainType.MOUNTAIN, 632D, 108D, 128D, 222D));
        terrain.add(blockingTerrain(TerrainType.RUINS, 394D, 42D, 172D, 72D));
        terrain.add(blockingTerrain(TerrainType.WATER, 408D, 248D, 146D, 114D));
        terrain.add(blockingTerrain(TerrainType.RUINS, 396D, 494D, 168D, 74D));
        terrain.add(softTerrain(TerrainType.FOREST, 92D, 352D, 178D, 126D, FOREST_SPEED_MULTIPLIER, FOREST_VISIBILITY_MULTIPLIER));
        terrain.add(softTerrain(TerrainType.FOREST, 684D, 374D, 180D, 132D, FOREST_SPEED_MULTIPLIER, FOREST_VISIBILITY_MULTIPLIER));
        terrain.add(softTerrain(TerrainType.FOREST, 376D, 134D, 108D, 88D, 0.76D, 0.7D));
        terrain.add(blockingTerrain(TerrainType.WATER, 170D, 468D, 122D, 82D));
        terrain.add(softTerrain(TerrainType.FOREST, 584D, 452D, 86D, 96D, 0.8D, 0.76D));
        return terrain;
    }

    private TerrainZone blockingTerrain(TerrainType type, double x, double y, double width, double height) {
        return new TerrainZone(type, x, y, width, height, true, true, 1D, 1D);
    }

    private TerrainZone softTerrain(TerrainType type, double x, double y, double width, double height,
                                     double speedMultiplier, double visibilityMultiplier) {
        return new TerrainZone(type, x, y, width, height, false, false, speedMultiplier, visibilityMultiplier);
    }

    private void syncRoomRecord(RuntimeRoom room, RoomStatus status, String winnerName) {
        gameRoomRepository.findByRoomCode(room.roomCode).ifPresent(record -> {
            record.setCurrentPlayers(countConnectedHumanPlayers(room));
            record.setStatus(status);
            record.setLastWinner(winnerName);
            record.setBotCount(room.botCount);
            gameRoomRepository.save(record);
        });
    }

    private void sendWelcome(Channel channel, RuntimeRoom room, String username) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "welcome");
        payload.put("roomCode", room.roomCode);
        payload.put("roomName", room.roomName);
        payload.put("ownerUsername", room.ownerUsername);
        payload.put("ownerDisplayName", room.ownerDisplayName);
        payload.put("self", username);
        payload.put("mapWidth", properties.getMapWidth());
        payload.put("mapHeight", properties.getMapHeight());
        payload.put("targetScore", properties.getTargetScore());
        payload.put("durationSeconds", properties.getGameDurationSeconds());
        payload.put("tickRateMs", properties.getTickRateMs());
        payload.put("reconnectGraceSeconds", properties.getReconnectGraceSeconds());
        payload.put("canStart", canStart(room, username));
        payload.put("seatIndex", seatIndexOf(room, username));
        write(channel, payload);
    }

    private void broadcastRoomMeta(RuntimeRoom room) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "roomMeta");
        payload.put("room", roomPayload(room));
        List<Map<String, Object>> permissions = new ArrayList<Map<String, Object>>();
        for (PlayerRuntime player : room.players.values()) {
            if (!player.connected || player.bot) {
                continue;
            }
            Map<String, Object> item = new HashMap<String, Object>();
            item.put("username", player.username);
            item.put("canStart", canStart(room, player.username));
            permissions.add(item);
        }
        payload.put("permissions", permissions);
        broadcast(room, payload);
    }

    private void broadcastSnapshot(RuntimeRoom room, boolean force) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "snapshot");
        payload.put("frameId", room.frameId);
        payload.put("room", roomPayload(room));
        payload.put("players", playerPayload(room));
        payload.put("bullets", bulletPayload(room));
        payload.put("terrain", terrainPayload(room));
        payload.put("obstacles", obstaclePayload(room));
        payload.put("logs", recentLogs(room));
        payload.put("serverTime", Instant.now().toEpochMilli());
        payload.put("full", force);
        broadcast(room, payload);
    }

    private void broadcastSystem(RuntimeRoom room, String message) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "system");
        payload.put("message", message);
        payload.put("serverTime", Instant.now().toEpochMilli());
        broadcast(room, payload);
    }

    private Map<String, Object> roomPayload(RuntimeRoom room) {
        Map<String, Object> roomData = new LinkedHashMap<String, Object>();
        roomData.put("roomCode", room.roomCode);
        roomData.put("roomName", room.roomName);
        roomData.put("ownerUsername", room.ownerUsername);
        roomData.put("ownerDisplayName", room.ownerDisplayName);
        roomData.put("status", room.status);
        roomData.put("currentPlayers", countConnectedHumanPlayers(room));
        roomData.put("maxPlayers", room.maxPlayers);
        roomData.put("botCount", room.botCount);
        roomData.put("lastWinner", room.lastWinner);
        roomData.put("emptyDestroySeconds", properties.getRoomEmptyDestroySeconds());
        roomData.put("pendingDestroySeconds", room.pendingDestroyAt > 0L
                ? Math.max(0L, (room.pendingDestroyAt - System.currentTimeMillis()) / 1000L)
                : 0L);
        roomData.put("remainingSeconds", room.status == RoomStatus.RUNNING
                ? Math.max(0, (room.endsAt - System.currentTimeMillis()) / 1000)
                : properties.getGameDurationSeconds());
        roomData.put("mapWidth", properties.getMapWidth());
        roomData.put("mapHeight", properties.getMapHeight());
        roomData.put("targetScore", properties.getTargetScore());
        roomData.put("frameId", room.frameId);
        roomData.put("tickRateMs", properties.getTickRateMs());
        roomData.put("reconnectGraceSeconds", properties.getReconnectGraceSeconds());
        return roomData;
    }

    private List<Map<String, Object>> playerPayload(RuntimeRoom room) {
        List<Map<String, Object>> players = new ArrayList<Map<String, Object>>();
        for (String username : room.seatOrder) {
            PlayerRuntime player = room.players.get(username);
            if (player == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("username", player.username);
            item.put("displayName", player.displayName);
            item.put("color", player.color);
            item.put("x", player.x);
            item.put("y", player.y);
            item.put("direction", player.direction);
            item.put("alive", player.alive);
            item.put("score", player.score);
            item.put("deaths", player.deaths);
            item.put("connected", player.connected);
            item.put("bot", player.bot);
            item.put("respawnAt", player.respawnAt);
            item.put("reconnectUntil", player.awaitReconnectUntil);
            item.put("seatIndex", seatIndexOf(room, player.username));
            item.put("terrainType", dominantTerrainType(room.terrainZones, player.x, player.y, TANK_SIZE, TANK_SIZE));
            item.put("speedFactor", movementSpeedMultiplier(room.terrainZones, player.x, player.y, TANK_SIZE, TANK_SIZE));
            item.put("visibilityFactor", visibilityMultiplier(room.terrainZones, player.x, player.y, TANK_SIZE, TANK_SIZE));
            players.add(item);
        }
        return players;
    }

    private int seatIndexOf(RuntimeRoom room, String username) {
        return room.seatOrder.indexOf(username);
    }

    private List<Map<String, Object>> bulletPayload(RuntimeRoom room) {
        List<Map<String, Object>> bullets = new ArrayList<Map<String, Object>>();
        for (BulletRuntime bullet : room.bullets) {
            Map<String, Object> item = new HashMap<String, Object>();
            item.put("id", bullet.id);
            item.put("ownerUsername", bullet.ownerUsername);
            item.put("x", bullet.x);
            item.put("y", bullet.y);
            item.put("direction", bullet.direction);
            bullets.add(item);
        }
        return bullets;
    }

    private List<Map<String, Object>> terrainPayload(RuntimeRoom room) {
        List<Map<String, Object>> terrain = new ArrayList<Map<String, Object>>();
        for (TerrainZone zone : room.terrainZones) {
            Map<String, Object> item = new HashMap<String, Object>();
            item.put("type", zone.type.name());
            item.put("x", zone.x);
            item.put("y", zone.y);
            item.put("width", zone.width);
            item.put("height", zone.height);
            item.put("blocksTank", zone.blocksTank);
            item.put("blocksBullet", zone.blocksBullet);
            item.put("speedMultiplier", zone.speedMultiplier);
            item.put("visibilityMultiplier", zone.visibilityMultiplier);
            terrain.add(item);
        }
        return terrain;
    }

    private List<Map<String, Object>> obstaclePayload(RuntimeRoom room) {
        List<Map<String, Object>> obstacles = new ArrayList<Map<String, Object>>();
        for (TerrainZone zone : room.terrainZones) {
            if (!zone.blocksTank) {
                continue;
            }
            Map<String, Object> item = new HashMap<String, Object>();
            item.put("x", zone.x);
            item.put("y", zone.y);
            item.put("width", zone.width);
            item.put("height", zone.height);
            item.put("type", zone.type.name());
            obstacles.add(item);
        }
        return obstacles;
    }

    private List<String> recentLogs(RuntimeRoom room) {
        List<String> logs = new ArrayList<String>();
        int max = Math.min(8, room.logs.size());
        for (int i = 0; i < max; i++) {
            logs.add(room.logs.get(i));
        }
        return logs;
    }

    private void broadcast(RuntimeRoom room, Map<String, Object> payload) {
        for (PlayerRuntime player : room.players.values()) {
            if (player.connected && !player.bot && player.channel != null && player.channel.isActive()) {
                write(player.channel, payload);
            }
        }
    }

    private void write(Channel channel, Map<String, Object> payload) {
        try {
            channel.writeAndFlush(new TextWebSocketFrame(objectMapper.writeValueAsString(payload)));
        } catch (IOException ignored) {
        }
    }

    private boolean hitsBlockingTerrain(List<TerrainZone> terrainZones, double x, double y, double width, double height, boolean blocksTank) {
        for (TerrainZone zone : terrainZones) {
            boolean shouldBlock = blocksTank ? zone.blocksTank : zone.blocksBullet;
            if (shouldBlock && intersects(x, y, width, height, zone.x, zone.y, zone.width, zone.height)) {
                return true;
            }
        }
        return false;
    }

    private double movementSpeedMultiplier(List<TerrainZone> terrainZones, double x, double y, double width, double height) {
        double multiplier = 1D;
        for (TerrainZone zone : terrainZones) {
            if (zone.speedMultiplier >= 1D) {
                continue;
            }
            if (intersects(x, y, width, height, zone.x, zone.y, zone.width, zone.height)) {
                multiplier = Math.min(multiplier, zone.speedMultiplier);
            }
        }
        return multiplier;
    }

    private double visibilityMultiplier(List<TerrainZone> terrainZones, double x, double y, double width, double height) {
        double multiplier = 1D;
        for (TerrainZone zone : terrainZones) {
            if (zone.visibilityMultiplier >= 1D) {
                continue;
            }
            if (intersects(x, y, width, height, zone.x, zone.y, zone.width, zone.height)) {
                multiplier = Math.min(multiplier, zone.visibilityMultiplier);
            }
        }
        return multiplier;
    }

    private String dominantTerrainType(List<TerrainZone> terrainZones, double x, double y, double width, double height) {
        TerrainZone selected = null;
        for (TerrainZone zone : terrainZones) {
            if (!intersects(x, y, width, height, zone.x, zone.y, zone.width, zone.height)) {
                continue;
            }
            if (selected == null || zone.type.priority > selected.type.priority) {
                selected = zone;
            }
        }
        return selected == null ? TerrainType.GROUND.name() : selected.type.name();
    }

    private boolean intersects(double ax, double ay, double aw, double ah,
                               double bx, double by, double bw, double bh) {
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class RuntimeRoom {
        private final Object monitor = new Object();
        private final Map<String, PlayerRuntime> players = new LinkedHashMap<String, PlayerRuntime>();
        private final List<String> seatOrder = new ArrayList<String>();
        private final List<BulletRuntime> bullets = new ArrayList<BulletRuntime>();
        private final List<String> logs = new ArrayList<String>();
        private final AtomicLong bulletSequence = new AtomicLong(0L);
        private String roomCode;
        private String roomName;
        private String ownerUsername;
        private String ownerDisplayName;
        private int maxPlayers;
        private int botCount;
        private int humanCapacity;
        private RoomStatus status = RoomStatus.WAITING;
        private long startedAt;
        private long endsAt;
        private long frameId;
        private long pendingDestroyAt;
        private String lastWinner;
        private List<TerrainZone> terrainZones = new ArrayList<TerrainZone>();
    }

    private static class PlayerRuntime {
        private String username;
        private String displayName;
        private String color;
        private Direction direction;
        private double x;
        private double y;
        private boolean alive;
        private boolean connected;
        private boolean bot;
        private int botIndex;
        private int score;
        private int deaths;
        private long lastFireAt;
        private long respawnAt;
        private long lastSeenAt;
        private long awaitReconnectUntil;
        private long nextBotDecisionAt;
        private Channel channel;
        private PlayerInput pendingInput = PlayerInput.idle();
        private PlayerInput lastAppliedInput = PlayerInput.idle();

        private int getScore() {
            return score;
        }

        private int getDeaths() {
            return deaths;
        }
    }

    private static class BulletRuntime {
        private long id;
        private String ownerUsername;
        private Direction direction;
        private double x;
        private double y;
    }

    private enum TerrainType {
        GROUND(0),
        FOREST(1),
        WATER(2),
        RUINS(3),
        MOUNTAIN(4);

        private final int priority;

        TerrainType(int priority) {
            this.priority = priority;
        }
    }

    private static class TerrainZone {
        private final TerrainType type;
        private final double x;
        private final double y;
        private final double width;
        private final double height;
        private final boolean blocksTank;
        private final boolean blocksBullet;
        private final double speedMultiplier;
        private final double visibilityMultiplier;

        private TerrainZone(TerrainType type, double x, double y, double width, double height,
                            boolean blocksTank, boolean blocksBullet,
                            double speedMultiplier, double visibilityMultiplier) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.blocksTank = blocksTank;
            this.blocksBullet = blocksBullet;
            this.speedMultiplier = speedMultiplier;
            this.visibilityMultiplier = visibilityMultiplier;
        }
    }

    private static class PlayerInput {
        private final boolean up;
        private final boolean down;
        private final boolean left;
        private final boolean right;
        private final boolean fireRequested;

        private PlayerInput(boolean up, boolean down, boolean left, boolean right, boolean fireRequested) {
            this.up = up;
            this.down = down;
            this.left = left;
            this.right = right;
            this.fireRequested = fireRequested;
        }

        private static PlayerInput idle() {
            return new PlayerInput(false, false, false, false, false);
        }
    }

    private static class FrameBundle {
        private long frameId;
        private List<Map<String, Object>> inputs;
    }
}
