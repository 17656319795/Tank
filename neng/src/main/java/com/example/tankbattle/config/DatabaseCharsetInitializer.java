package com.example.tankbattle.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class DatabaseCharsetInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCharsetInitializer.class);

    private final DataSource dataSource;

    public DatabaseCharsetInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            String catalog = connection.getCatalog();
            if (!StringUtils.hasText(catalog)) {
                return;
            }
            DatabaseMetaData metaData = connection.getMetaData();
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER DATABASE `" + catalog + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
                ensureTableUtf8mb4(metaData, statement, catalog, "player_profile");
                ensureTableUtf8mb4(metaData, statement, catalog, "game_room");
            }
            log.info("数据库字符集已校准为 utf8mb4");
        } catch (SQLException ex) {
            log.warn("数据库字符集自动校准失败: {}", ex.getMessage());
        }
    }

    private void ensureTableUtf8mb4(DatabaseMetaData metaData, Statement statement, String catalog, String tableName)
            throws SQLException {
        if (!tableExists(metaData, catalog, tableName)) {
            return;
        }
        statement.execute("ALTER TABLE `" + tableName + "` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
    }

    private boolean tableExists(DatabaseMetaData metaData, String catalog, String tableName) throws SQLException {
        try (ResultSet tables = metaData.getTables(catalog, null, tableName, new String[]{"TABLE"})) {
            return tables.next();
        }
    }
}
