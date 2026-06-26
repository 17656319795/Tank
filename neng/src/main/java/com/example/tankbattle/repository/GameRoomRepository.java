package com.example.tankbattle.repository;

import com.example.tankbattle.domain.GameRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GameRoomRepository extends JpaRepository<GameRoom, Long> {

    Optional<GameRoom> findByRoomCode(String roomCode);

    List<GameRoom> findAllByOrderByUpdatedAtDesc();
}
