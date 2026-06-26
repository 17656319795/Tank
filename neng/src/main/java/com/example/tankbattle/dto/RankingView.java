package com.example.tankbattle.dto;

import com.example.tankbattle.domain.PlayerProfile;

public class RankingView {

    private String username;
    private String displayName;
    private Integer points;
    private Integer totalMatches;
    private Integer wins;
    private double winRate;

    public static RankingView from(PlayerProfile profile) {
        RankingView view = new RankingView();
        view.setUsername(profile.getUsername());
        view.setDisplayName(profile.getDisplayName());
        view.setPoints(profile.getPoints());
        view.setTotalMatches(profile.getTotalMatches());
        view.setWins(profile.getWins());
        if (profile.getTotalMatches() == null || profile.getTotalMatches() == 0) {
            view.setWinRate(0D);
        } else {
            view.setWinRate((profile.getWins() * 100D) / profile.getTotalMatches());
        }
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

    public double getWinRate() {
        return winRate;
    }

    public void setWinRate(double winRate) {
        this.winRate = winRate;
    }
}
