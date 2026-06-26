package com.example.tankbattle.service;

import com.example.tankbattle.config.TankBattleProperties;
import com.example.tankbattle.domain.PlayerProfile;
import com.example.tankbattle.dto.AuthRequest;
import com.example.tankbattle.dto.AuthResponse;
import com.example.tankbattle.dto.LoginRequest;
import com.example.tankbattle.dto.PlayerProfileView;
import com.example.tankbattle.repository.PlayerProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,20}$");

    private final PlayerProfileRepository playerProfileRepository;
    private final PlayerSessionService playerSessionService;
    private final TankBattleProperties properties;

    public AuthService(PlayerProfileRepository playerProfileRepository,
                       PlayerSessionService playerSessionService,
                       TankBattleProperties properties) {
        this.playerProfileRepository = playerProfileRepository;
        this.playerSessionService = playerSessionService;
        this.properties = properties;
    }

    @Transactional
    public AuthResponse register(AuthRequest request) {
        String username = normalizeUsername(request.getUsername());
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名只能包含字母、数字、下划线");
        }
        if (playerProfileRepository.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        PlayerProfile profile = new PlayerProfile();
        profile.setUsername(username);
        profile.setDisplayName(request.getDisplayName().trim());
        profile.setPasswordHash(hash(request.getPassword()));
        playerProfileRepository.save(profile);
        return buildResponse(playerSessionService.createSession(profile), profile);
    }

    public AuthResponse login(LoginRequest request) {
        String username = normalizeUsername(request.getUsername());
        PlayerProfile profile = playerProfileRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (!profile.getPasswordHash().equals(hash(request.getPassword()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码不正确");
        }
        return buildResponse(playerSessionService.createSession(profile), profile);
    }

    public AuthResponse currentPlayer(String token) {
        PlayerSessionService.SessionPlayer sessionPlayer = playerSessionService.requirePlayer(token);
        PlayerProfile profile = playerProfileRepository.findByUsername(sessionPlayer.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        return buildResponse(sessionPlayer, profile);
    }

    public void logout(String token) {
        playerSessionService.remove(token);
    }

    private AuthResponse buildResponse(PlayerSessionService.SessionPlayer sessionPlayer, PlayerProfile profile) {
        AuthResponse response = new AuthResponse();
        response.setToken(sessionPlayer.getToken());
        response.setWsPort(properties.getNettyPort());
        response.setPlayer(PlayerProfileView.from(profile));
        return response;
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private String hash(String raw) {
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }
}
