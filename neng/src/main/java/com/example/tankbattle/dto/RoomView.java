package com.example.tankbattle.dto;

import com.example.tankbattle.domain.GameRoom;
import com.example.tankbattle.domain.RoomStatus;

public class RoomView {

    private String roomCode;
    private String roomName;
    private String ownerUsername;
    private String ownerDisplayName;
    private RoomStatus status;
    private Integer currentPlayers;
    private Integer maxPlayers;
    private Integer botCount;
    private String lastWinner;

    public static RoomView from(GameRoom room, int currentPlayers, RoomStatus statusOverride) {
        RoomView view = new RoomView();
        view.setRoomCode(room.getRoomCode());
        view.setRoomName(room.getRoomName());
        view.setOwnerUsername(room.getOwnerUsername());
        view.setOwnerDisplayName(room.getOwnerDisplayName());
        view.setStatus(statusOverride == null ? room.getStatus() : statusOverride);
        view.setCurrentPlayers(currentPlayers);
        view.setMaxPlayers(room.getMaxPlayers());
        view.setBotCount(room.getBotCount());
        view.setLastWinner(room.getLastWinner());
        return view;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void setRoomCode(String roomCode) {
        this.roomCode = roomCode;
    }

    public String getRoomName() {
        return roomName;
    }

    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }

    public String getOwnerUsername() {
        return ownerUsername;
    }

    public void setOwnerUsername(String ownerUsername) {
        this.ownerUsername = ownerUsername;
    }

    public String getOwnerDisplayName() {
        return ownerDisplayName;
    }

    public void setOwnerDisplayName(String ownerDisplayName) {
        this.ownerDisplayName = ownerDisplayName;
    }

    public RoomStatus getStatus() {
        return status;
    }

    public void setStatus(RoomStatus status) {
        this.status = status;
    }

    public Integer getCurrentPlayers() {
        return currentPlayers;
    }

    public void setCurrentPlayers(Integer currentPlayers) {
        this.currentPlayers = currentPlayers;
    }

    public Integer getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(Integer maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public Integer getBotCount() {
        return botCount;
    }

    public void setBotCount(Integer botCount) {
        this.botCount = botCount;
    }

    public String getLastWinner() {
        return lastWinner;
    }

    public void setLastWinner(String lastWinner) {
        this.lastWinner = lastWinner;
    }
}
