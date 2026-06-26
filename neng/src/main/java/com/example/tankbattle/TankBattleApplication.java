package com.example.tankbattle;

import com.example.tankbattle.config.TankBattleProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(TankBattleProperties.class)
public class TankBattleApplication {

    public static void main(String[] args) {
        SpringApplication.run(TankBattleApplication.class, args);
    }
}
