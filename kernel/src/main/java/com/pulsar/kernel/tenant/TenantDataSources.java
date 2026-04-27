package com.pulsar.kernel.tenant;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One Hikari pool per tenant database, bounded by an LRU cap.
 *
 * <p>Without eviction the pool map grows monotonically — at the default
 * 5 connections/pool, ~30 active tenants exhausts MySQL's stock {@code
 * max_connections=151}. The LRU cap (default 50) keeps the connection
 * floor predictable: 50 × 5 = 250 connections at saturation, sized
 * against a {@code max_connections} the operator picks at deploy time.
 *
 * <p>Eviction closes the evicted pool. Hikari's {@code close()} drains
 * in-flight connections (default ~30s grace), so a query already in
 * flight on the evicted tenant will complete; the next request for that
 * tenant lazily rebuilds the pool. The race window is bounded and benign.
 */
@Component
public class TenantDataSources implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(TenantDataSources.class);

    private final Map<String, HikariDataSource> pools;
    private final String baseUrl;
    private final String user;
    private final String password;
    private final int maxPools;

    public TenantDataSources(
        @Value("${pulsar.mysql.base-jdbc-url}") String baseUrl,
        @Value("${pulsar.mysql.user}") String user,
        @Value("${pulsar.mysql.password}") String password,
        @Value("${pulsar.tenant.max-pools:50}") int maxPools
    ) {
        this.baseUrl = baseUrl;
        this.user = user;
        this.password = password;
        this.maxPools = maxPools;
        // access-order LinkedHashMap — every get() promotes the entry to MRU.
        this.pools = new LinkedHashMap<>(maxPools, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, HikariDataSource> eldest) {
                if (size() > TenantDataSources.this.maxPools) {
                    HikariDataSource evicted = eldest.getValue();
                    log.info("tenant pool evicted (LRU cap {}): {}", TenantDataSources.this.maxPools, eldest.getKey());
                    try { evicted.close(); } catch (Exception ex) {
                        log.warn("error closing evicted pool {}: {}", eldest.getKey(), ex.toString());
                    }
                    return true;
                }
                return false;
            }
        };
    }

    public DataSource forDb(String dbName) {
        // Synchronized because LinkedHashMap with access-order isn't thread-safe
        // even for reads, and removeEldestEntry mutates on insert. Contention
        // is low — typical read path is a hash lookup, and pool creation is a
        // once-per-tenant-per-process event.
        synchronized (pools) {
            return pools.computeIfAbsent(dbName, this::create);
        }
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
        synchronized (pools) {
            pools.values().forEach(ds -> {
                try { ds.close(); } catch (Exception ex) {
                    log.warn("error closing pool on shutdown: {}", ex.toString());
                }
            });
            pools.clear();
        }
    }
}
