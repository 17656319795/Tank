package com.example.tankbattle.service;

import com.example.tankbattle.domain.PlayerProfile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PlayerSessionService {

    private final Map<String, SessionPlayer> sessions = new ConcurrentHashMap<String, SessionPlayer>();

    public SessionPlayer createSession(PlayerProfile profile) {
        String token = UUID.randomUUID().toString().replace("-", "");
        SessionPlayer sessionPlayer = new SessionPlayer(token, profile.getUsername(), profile.getDisplayName());
        sessions.put(token, sessionPlayer);
        return sessionPlayer;
    }

    public SessionPlayer requirePlayer(String token) {
        if (!StringUtils.hasText(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }
        SessionPlayer player = sessions.get(token);
        if (player == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }
        return player;
    }

    public void remove(String token) {
        if (StringUtils.hasText(token)) {
            sessions.remove(token);
        }
    }

    public static class SessionPlayer {
        private final String token;
        private final String username;
        private final String displayName;

        public SessionPlayer(String token, String username, String displayName) {
            this.token = token;
            this.username = username;
            this.displayName = displayName;
        }

        public String getToken() {
            return token;
        }

        public String getUsername() {
            return username;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
