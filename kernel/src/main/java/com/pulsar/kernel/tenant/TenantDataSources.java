package com.pulsar.kernel.tenant;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TenantDataSources implements DisposableBean {
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final String baseUrl;
    private final String user;
    private final String password;

    public TenantDataSources(
        @Value("${pulsar.mysql.base-jdbc-url}") String baseUrl,
        @Value("${pulsar.mysql.user}") String user,
        @Value("${pulsar.mysql.password}") String password
    ) {
        this.baseUrl = baseUrl;
        this.user = user;
        this.password = password;
    }

    public DataSource forDb(String dbName) {
        return pools.computeIfAbsent(dbName, this::create);
    }

    private HikariDataSource create(String dbName) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(baseUrl + "/" + dbName + "?useSSL=false&allowPublicKeyRetrieval=true");
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("pulsar-tenant-" + dbName);
        return new HikariDataSource(cfg);
    }

    @Override
    public void destroy() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
