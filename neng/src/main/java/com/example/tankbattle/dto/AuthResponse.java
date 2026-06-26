package com.example.tankbattle.dto;

public class AuthResponse {

    private String token;
    private int wsPort;
    private PlayerProfileView player;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public int getWsPort() {
        return wsPort;
    }

    public void setWsPort(int wsPort) {
        this.wsPort = wsPort;
    }

    public PlayerProfileView getPlayer() {
        return player;
    }

    public void setPlayer(PlayerProfileView player) {
        this.player = player;
    }
}
