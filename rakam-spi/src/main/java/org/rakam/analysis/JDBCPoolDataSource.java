package org.rakam.analysis;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.rakam.config.JDBCConfig;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;

public class JDBCPoolDataSource implements DataSource {
    private static final Map<JDBCConfig, JDBCPoolDataSource> pools = new ConcurrentHashMap<>();
    private final HikariDataSource dataSource;
    private final JDBCConfig config;

    private JDBCPoolDataSource(JDBCConfig config, Optional<String> initialString) {
        this.config = config;
        checkArgument(config.getUrl() != null, "JDBC url is required");

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());
        hikariConfig.setJdbcUrl(config.getUrl());

        if (config.getConnectionIdleTimeout() != null) {
            hikariConfig.setIdleTimeout(config.getConnectionIdleTimeout());
        } else {
            hikariConfig.setIdleTimeout(1000);
        }

        if (config.getMaxConnection() != null) {
            hikariConfig.setMaximumPoolSize(config.getMaxConnection());
        } else {
            hikariConfig.setMaximumPoolSize(30);
        }

        hikariConfig.setMaxLifetime(60000);

        hikariConfig.setConnectionTimeout(15000);
        hikariConfig.setLeakDetectionThreshold(2000);

        hikariConfig.setAutoCommit(true);
        hikariConfig.setPoolName("generic-jdbc-query-executor");
        if(initialString.isPresent()) {
            hikariConfig.setConnectionInitSql(initialString.get());
        }
        dataSource = new HikariDataSource(hikariConfig);
    }

    public static JDBCPoolDataSource getOrCreateDataSource(JDBCConfig config, String initialQuery) {
        return pools.computeIfAbsent(config,
                key -> new JDBCPoolDataSource(config, Optional.of(initialQuery)));
    }

    public static JDBCPoolDataSource getOrCreateDataSource(JDBCConfig config) {
        return pools.computeIfAbsent(config,
                key -> new JDBCPoolDataSource(config, Optional.empty()));
    }

    @Override
    public Connection getConnection() throws SQLException {
//        return DriverManager.getConnection(config.getUrl(), config.getUsername(), config.getPassword());
        return dataSource.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return dataSource.getConnection(username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return dataSource.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        dataSource.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        dataSource.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return dataSource.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return dataSource.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return dataSource.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return dataSource.isWrapperFor(iface);
    }
}
