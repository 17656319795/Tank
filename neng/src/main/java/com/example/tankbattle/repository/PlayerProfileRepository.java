package com.example.tankbattle.repository;

import com.example.tankbattle.domain.PlayerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlayerProfileRepository extends JpaRepository<PlayerProfile, Long> {

    boolean existsByUsername(String username);

    Optional<PlayerProfile> findByUsername(String username);

    java.util.List<PlayerProfile> findTop10ByOrderByPointsDescWinsDesc();
}
