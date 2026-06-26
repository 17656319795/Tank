package com.example.tankbattle.service;

import com.example.tankbattle.domain.PlayerProfile;
import com.example.tankbattle.dto.RankingView;
import com.example.tankbattle.repository.PlayerProfileRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RankingService {

    private final PlayerProfileRepository playerProfileRepository;

    public RankingService(PlayerProfileRepository playerProfileRepository) {
        this.playerProfileRepository = playerProfileRepository;
    }

    public List<RankingView> topPlayers() {
        List<PlayerProfile> profiles = playerProfileRepository.findTop10ByOrderByPointsDescWinsDesc();
        List<RankingView> results = new ArrayList<RankingView>();
        for (PlayerProfile profile : profiles) {
            results.add(RankingView.from(profile));
        }
        return results;
    }
}
