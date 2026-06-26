package com.example.tankbattle.dto;

import com.example.tankbattle.domain.PlayerProfile;

public class PlayerProfileView {

    private String username;
    private String displayName;
    private Integer points;
    private Integer totalMatches;
    private Integer wins;

    public static PlayerProfileView from(PlayerProfile profile) {
        PlayerProfileView view = new PlayerProfileView();
        view.setUsername(profile.getUsername());
        view.setDisplayName(profile.getDisplayName());
        view.setPoints(profile.getPoints());
        view.setTotalMatches(profile.getTotalMatches());
        view.setWins(profile.getWins());
        return view;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Integer getPoints() {
        return points;
    }

    public void setPoints(Integer points) {
        this.points = points;
    }

    public Integer getTotalMatches() {
        return totalMatches;
    }

    public void setTotalMatches(Integer totalMatches) {
        this.totalMatches = totalMatches;
    }

    public Integer getWins() {
        return wins;
    }

    public void setWins(Integer wins) {
        this.wins = wins;
    }
}
