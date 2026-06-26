package com.example.tankbattle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tankbattle")
public class TankBattleProperties {

    private int nettyPort = 9001;
    private int mapWidth = 960;
    private int mapHeight = 640;
    private int gameDurationSeconds = 180;
    private int targetScore = 10;
    private int respawnDelayMs = 1500;
    private int tickRateMs = 50;
    private int roomEmptyDestroySeconds = 90;
    private int reconnectGraceSeconds = 25;

    public int getNettyPort() {
        return nettyPort;
    }

    public void setNettyPort(int nettyPort) {
        this.nettyPort = nettyPort;
    }

    public int getMapWidth() {
        return mapWidth;
    }

    public void setMapWidth(int mapWidth) {
        this.mapWidth = mapWidth;
    }

    public int getMapHeight() {
        return mapHeight;
    }

    public void setMapHeight(int mapHeight) {
        this.mapHeight = mapHeight;
    }

    public int getGameDurationSeconds() {
        return gameDurationSeconds;
    }

    public void setGameDurationSeconds(int gameDurationSeconds) {
        this.gameDurationSeconds = gameDurationSeconds;
    }

    public int getTargetScore() {
        return targetScore;
    }

    public void setTargetScore(int targetScore) {
        this.targetScore = targetScore;
    }

    public int getRespawnDelayMs() {
        return respawnDelayMs;
    }

    public void setRespawnDelayMs(int respawnDelayMs) {
        this.respawnDelayMs = respawnDelayMs;
    }

    public int getTickRateMs() {
        return tickRateMs;
    }

    public void setTickRateMs(int tickRateMs) {
        this.tickRateMs = tickRateMs;
    }

    public int getRoomEmptyDestroySeconds() {
        return roomEmptyDestroySeconds;
    }

    public void setRoomEmptyDestroySeconds(int roomEmptyDestroySeconds) {
        this.roomEmptyDestroySeconds = roomEmptyDestroySeconds;
    }

    public int getReconnectGraceSeconds() {
        return reconnectGraceSeconds;
    }

    public void setReconnectGraceSeconds(int reconnectGraceSeconds) {
        this.reconnectGraceSeconds = reconnectGraceSeconds;
    }
}
