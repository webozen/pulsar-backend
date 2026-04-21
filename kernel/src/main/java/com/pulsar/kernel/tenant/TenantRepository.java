package com.pulsar.kernel.tenant;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TenantRepository {
    private final JdbcTemplate jdbc;

    public TenantRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    private static final RowMapper<TenantRecord> MAPPER = (ResultSet rs, int i) -> new TenantRecord(
        rs.getLong("id"),
        rs.getString("slug"),
        rs.getString("name"),
        rs.getString("db_name"),
        splitModules(rs.getString("active_modules")),
        rs.getString("contact_email"),
        rs.getString("access_passcode"),
        nullableInstant(rs.getTimestamp("suspended_at")),
        rs.getTimestamp("created_at").toInstant()
    );

    private static Set<String> splitModules(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return new HashSet<>(Arrays.asList(csv.split(",")));
    }

    private static Instant nullableInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public List<TenantRecord> findAll() {
        return jdbc.query("SELECT * FROM public_tenants ORDER BY created_at DESC", MAPPER);
    }

    public Optional<TenantRecord> findBySlug(String slug) {
        return jdbc.query("SELECT * FROM public_tenants WHERE slug = ?", MAPPER, slug).stream().findFirst();
    }

    public Optional<TenantRecord> findById(long id) {
        return jdbc.query("SELECT * FROM public_tenants WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public boolean slugExists(String slug) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM public_tenants WHERE slug = ?", Integer.class, slug);
        return n != null && n > 0;
    }

    public long insert(String slug, String name, String dbName, String contactEmail, String passcode) {
        jdbc.update(
            "INSERT INTO public_tenants (slug, name, db_name, active_modules, contact_email, access_passcode, created_at) " +
            "VALUES (?, ?, ?, '', ?, ?, NOW())",
            slug, name, dbName, contactEmail, passcode
        );
        Long id = jdbc.queryForObject("SELECT id FROM public_tenants WHERE slug = ?", Long.class, slug);
        return id == null ? 0L : id;
    }

    public void updateModules(long id, Set<String> modules) {
        String csv = String.join(",", modules);
        jdbc.update("UPDATE public_tenants SET active_modules = ? WHERE id = ?", csv, id);
    }

    public void updatePasscode(long id, String passcode) {
        jdbc.update("UPDATE public_tenants SET access_passcode = ? WHERE id = ?", passcode, id);
    }

    public void setSuspended(long id, boolean suspended) {
        if (suspended) {
            jdbc.update("UPDATE public_tenants SET suspended_at = NOW() WHERE id = ?", id);
        } else {
            jdbc.update("UPDATE public_tenants SET suspended_at = NULL WHERE id = ?", id);
        }
    }
}
