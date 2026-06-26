package com.example.tankbattle.controller;

import com.example.tankbattle.dto.ApiResponse;
import com.example.tankbattle.dto.RankingView;
import com.example.tankbattle.service.RankingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rankings")
public class RankingController {

    private final RankingService rankingService;

    public RankingController(RankingService rankingService) {
        this.rankingService = rankingService;
    }

    @GetMapping
    public ApiResponse<List<RankingView>> rankings() {
        return ApiResponse.success(rankingService.topPlayers());
    }
}
