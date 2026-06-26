# 铁甲对决 Tank Battle

一个基于 `Spring Boot + MySQL + Netty + 原生前端页面` 的多人坦克大战示例项目。

## 功能概览

- 注册 / 登录，使用内存会话令牌维护前端登录态
- 大厅页面，支持创建房间、按房间号加入、查看房间列表
- 排行榜页面，按积分和胜场展示玩家
- 房间预备页，方便战前确认房间状态
- Netty WebSocket 实时对战页，支持多人移动、开火、击毁、复活、倒计时和结算
- 输入帧同步 + 服务端周期校准，兼顾流畅度和状态一致性
- 多地形战场，包含山体、遗迹、水域、森林
- 森林减速与视野压制、山体/遗迹/水域阻挡
- 高级 Canvas 战场表现，包含音效、震屏、爆炸残骸、履带压痕、水波尾迹和 HUD 动效
- 支持房间机器人数量配置，可与 AI 坦克混战
- 空房间按配置时间自动销毁，玩家断线后支持重连宽限期
- 服务端重启后内存登录态失效，前端会要求重新登录
- MySQL 持久化玩家数据、房间记录和上局胜者

## 技术栈

- Java 8
- Spring Boot 2.7
- Spring Web / Spring Data JPA
- MySQL 8
- Netty WebSocket
- HTML + CSS + Vanilla JavaScript + Canvas

## 目录说明

- `src/main/java/com/example/tankbattle/controller`: REST 接口
- `src/main/java/com/example/tankbattle/service`: 登录、房间、排行榜、实时对战引擎
- `src/main/java/com/example/tankbattle/netty`: Netty WebSocket 服务
- `src/main/resources/static`: 登录页、大厅页、房间页、游戏页
- `sql/init.sql`: 数据库初始化脚本

## 运行前准备

1. 确保 MySQL 服务已经启动
2. 修改 `src/main/resources/application.yml` 中的数据库账号密码
3. 首次启动时，程序会在账号具备权限的前提下自动创建 `tank_battle` 数据库

## 数据库初始化说明

- 当前 JDBC URL 已开启 `createDatabaseIfNotExist=true`
- 如果 `tank_battle` 不存在，Spring Boot 首次连接 MySQL 时会自动创建该库
- `spring.jpa.hibernate.ddl-auto=update` 会继续自动创建或更新业务表
- 前提是当前数据库账号具备 `CREATE DATABASE` 权限
- `sql/init.sql` 仍然保留，适合你想手动初始化时使用

## 启动方式

### 在 IDEA 中启动

1. 以 Maven 项目导入
2. 等待依赖下载完成
3. 运行 `com.example.tankbattle.TankBattleApplication`

### 命令行启动

```powershell
mvn spring-boot:run
```

## 页面入口

- 登录页: `http://localhost:8080/index.html`
- 大厅页: `http://localhost:8080/lobby.html`
- 房间页: `http://localhost:8080/room.html?room=房间号`
- 战场页: `http://localhost:8080/game.html?room=房间号`

## WebSocket 协议

连接地址:

```text
ws://localhost:9001/ws
```

客户端消息:

```json
{"type":"join","token":"登录后返回的 token","roomCode":"ABC123"}
{"type":"input","up":true,"down":false,"left":false,"right":true}
{"type":"fire"}
{"type":"start"}
```

服务端消息:

- `welcome`: 初始化房间和地图信息
- `frame`: 服务端广播某一帧的所有玩家输入
- `snapshot`: 周期性状态快照，用于校准客户端状态
- `roomMeta`: 房间状态和开局权限刷新
- `system`: 系统提示
- `gameOver`: 结算消息
- `error`: 错误消息

## 当前实现说明

- 这是一个可继续扩展的 MVP，当前是自由混战模式
- 地图已升级为统一地形区模型，支持 `MOUNTAIN / RUINS / WATER / FOREST`
- 森林区域会降低坦克移动速度，并让敌方更难锁定林中的你
- 山体、遗迹、水域会阻挡坦克和子弹
- 实时层当前采用“输入帧同步 + 服务端权威校准”模型
- 前端登录态已改为 `sessionStorage`，同浏览器多开账号不会再互相覆盖
- 房间开局权限会在玩家离开、重连、重新入场时自动刷新
- 战斗页已加入程序化音效、二战氛围 BGM、震动反馈、爆炸残骸、履带压痕、水波尾迹和 UI 动效
- 建房时可配置机器人数量；空房会在配置倒计时结束后自动销毁
- WebSocket 战场连接断开后会在重连宽限期内自动尝试恢复
- 若要扩展聊天、匹配、观战、AI 坦克、道具系统，可以继续在现有结构上叠加
