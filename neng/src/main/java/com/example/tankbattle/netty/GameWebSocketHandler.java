package com.example.tankbattle.netty;

import com.example.tankbattle.service.GameRuntimeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import org.springframework.stereotype.Component;

@Component
@ChannelHandler.Sharable
public class GameWebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    public static final AttributeKey<String> ROOM_CODE = AttributeKey.valueOf("roomCode");
    public static final AttributeKey<String> USERNAME = AttributeKey.valueOf("username");

    private final GameRuntimeService gameRuntimeService;
    private final ObjectMapper objectMapper;

    public GameWebSocketHandler(GameRuntimeService gameRuntimeService, ObjectMapper objectMapper) {
        this.gameRuntimeService = gameRuntimeService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, TextWebSocketFrame frame) throws Exception {
        ClientMessage message = objectMapper.readValue(frame.text(), ClientMessage.class);
        String type = message.getType() == null ? "" : message.getType().trim().toLowerCase();
        Channel channel = context.channel();

        if ("join".equals(type)) {
            gameRuntimeService.join(message.getToken(), message.getRoomCode(), channel);
            return;
        }

        String roomCode = channel.attr(ROOM_CODE).get();
        String username = channel.attr(USERNAME).get();
        if (roomCode == null || username == null) {
            gameRuntimeService.sendToChannel(channel, "error", "请先完成房间加入");
            return;
        }

        if ("input".equals(type)) {
            gameRuntimeService.updateInput(
                    roomCode,
                    username,
                    Boolean.TRUE.equals(message.getUp()),
                    Boolean.TRUE.equals(message.getDown()),
                    Boolean.TRUE.equals(message.getLeft()),
                    Boolean.TRUE.equals(message.getRight())
            );
        } else if ("fire".equals(type)) {
            gameRuntimeService.fire(roomCode, username);
        } else if ("start".equals(type)) {
            gameRuntimeService.startMatch(roomCode, username);
        } else if ("ping".equals(type)) {
            gameRuntimeService.sendToChannel(channel, "pong", "ok");
        } else {
            gameRuntimeService.sendToChannel(channel, "error", "不支持的消息类型: " + type);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        gameRuntimeService.removeChannel(context.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        gameRuntimeService.sendToChannel(context.channel(), "error", "连接异常: " + cause.getMessage());
        context.close();
    }
}
