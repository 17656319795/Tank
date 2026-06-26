package com.example.tankbattle.service;

import com.example.tankbattle.domain.GameRoom;
import com.example.tankbattle.domain.RoomStatus;
import com.example.tankbattle.dto.CreateRoomRequest;
import com.example.tankbattle.dto.RoomView;
import com.example.tankbattle.repository.GameRoomRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class RoomService {

    private static final String ROOM_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final GameRoomRepository gameRoomRepository;
    private final PlayerSessionService playerSessionService;
    private final GameRuntimeService gameRuntimeService;

    public RoomService(GameRoomRepository gameRoomRepository,
                       PlayerSessionService playerSessionService,
                       GameRuntimeService gameRuntimeService) {
        this.gameRoomRepository = gameRoomRepository;
        this.playerSessionService = playerSessionService;
        this.gameRuntimeService = gameRuntimeService;
    }

    @Transactional
    public RoomView createRoom(String token, CreateRoomRequest request) {
        PlayerSessionService.SessionPlayer player = playerSessionService.requirePlayer(token);
        GameRoom room = new GameRoom();
        room.setRoomCode(generateRoomCode());
        room.setRoomName(request.getRoomName().trim());
        room.setOwnerUsername(player.getUsername());
        room.setOwnerDisplayName(player.getDisplayName());
        room.setMaxPlayers(request.getMaxPlayers());
        int botCount = request.getBotCount() == null ? 0 : request.getBotCount();
        if (botCount >= request.getMaxPlayers()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "机器人数量必须小于房间人数上限");
        }
        room.setBotCount(botCount);
        room.setCurrentPlayers(0);
        room.setStatus(RoomStatus.WAITING);
        gameRoomRepository.save(room);
        return RoomView.from(room, 0, RoomStatus.WAITING);
    }

    public List<RoomView> listRooms() {
        List<GameRoom> rooms = gameRoomRepository.findAllByOrderByUpdatedAtDesc();
        List<RoomView> results = new ArrayList<RoomView>();
        for (GameRoom room : rooms) {
            int players = gameRuntimeService.getCurrentPlayers(room.getRoomCode());
            RoomStatus status = gameRuntimeService.getCurrentStatus(room.getRoomCode());
            results.add(RoomView.from(room, players >= 0 ? players : room.getCurrentPlayers(), status));
        }
        return results;
    }

    public RoomView getRoom(String token, String roomCode) {
        playerSessionService.requirePlayer(token);
        GameRoom room = findRoom(roomCode);
        int players = gameRuntimeService.getCurrentPlayers(room.getRoomCode());
        RoomStatus status = gameRuntimeService.getCurrentStatus(room.getRoomCode());
        return RoomView.from(room, players >= 0 ? players : room.getCurrentPlayers(), status);
    }

    public RoomView enterRoom(String token, String roomCode) {
        PlayerSessionService.SessionPlayer player = playerSessionService.requirePlayer(token);
        GameRoom room = findRoom(roomCode);
        int currentPlayers = gameRuntimeService.getCurrentPlayers(roomCode);
        if (currentPlayers < 0) {
            currentPlayers = room.getCurrentPlayers();
        }
        if (currentPlayers >= room.getMaxPlayers() && !gameRuntimeService.isPlayerInRoom(roomCode, player.getUsername())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "房间已满");
        }
        return RoomView.from(room, currentPlayers, gameRuntimeService.getCurrentStatus(roomCode));
    }

    public Optional<GameRoom> findOptional(String roomCode) {
        return gameRoomRepository.findByRoomCode(roomCode);
    }

    public GameRoom findRoom(String roomCode) {
        return findOptional(roomCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "房间不存在"));
    }

    private String generateRoomCode() {
        String code;
        do {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                int index = ThreadLocalRandom.current().nextInt(ROOM_CODE_CHARS.length());
                builder.append(ROOM_CODE_CHARS.charAt(index));
            }
            code = builder.toString();
        } while (gameRoomRepository.findByRoomCode(code).isPresent());
        return code;
    }
}
