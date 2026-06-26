package com.example.tankbattle.controller;

import com.example.tankbattle.dto.ApiResponse;
import com.example.tankbattle.dto.CreateRoomRequest;
import com.example.tankbattle.dto.RoomView;
import com.example.tankbattle.service.RoomService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @GetMapping
    public ApiResponse<List<RoomView>> listRooms() {
        return ApiResponse.success(roomService.listRooms());
    }

    @PostMapping
    public ApiResponse<RoomView> createRoom(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                            @Valid @RequestBody CreateRoomRequest request) {
        return ApiResponse.success("房间创建成功", roomService.createRoom(token, request));
    }

    @GetMapping("/{roomCode}")
    public ApiResponse<RoomView> getRoom(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                         @PathVariable String roomCode) {
        return ApiResponse.success(roomService.getRoom(token, roomCode));
    }

    @PostMapping("/{roomCode}/enter")
    public ApiResponse<RoomView> enterRoom(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                           @PathVariable String roomCode) {
        return ApiResponse.success("已进入房间", roomService.enterRoom(token, roomCode));
    }
}
