package com.example.tankbattle.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class CreateRoomRequest {

    @NotBlank(message = "房间名不能为空")
    @Size(min = 2, max = 24, message = "房间名长度需要在 2-24 之间")
    private String roomName;

    @Min(value = 2, message = "最少支持 2 人")
    @Max(value = 4, message = "最多支持 4 人")
    private Integer maxPlayers = 4;

    @Min(value = 0, message = "机器人数量不能小于 0")
    @Max(value = 3, message = "机器人数量最多 3 个")
    private Integer botCount = 0;

    public String getRoomName() {
        return roomName;
    }

    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }

    public Integer getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(Integer maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public Integer getBotCount() {
        return botCount;
    }

    public void setBotCount(Integer botCount) {
        this.botCount = botCount;
    }
}
