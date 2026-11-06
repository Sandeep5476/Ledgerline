package com.ledgerline.repository;

import com.ledgerline.model.User;
import com.ledgerline.model.UserRole;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<User> USER_ROW_MAPPER = (rs, rowNum) -> new User(
        rs.getLong("id"),
        rs.getString("username"),
        rs.getString("password_hash"),
        UserRole.valueOf(rs.getString("role")),
        rs.getBoolean("enabled"),
        rs.getTimestamp("created_at").toInstant()
    );

    public Optional<User> findByUsername(String username) {
        String sql = "SELECT id, username, password_hash, role, enabled, created_at FROM users WHERE username = ?";
        try {
            User user = jdbcTemplate.queryForObject(sql, USER_ROW_MAPPER, username);
            return Optional.ofNullable(user);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<User> findById(Long id) {
        String sql = "SELECT id, username, password_hash, role, enabled, created_at FROM users WHERE id = ?";
        try {
            User user = jdbcTemplate.queryForObject(sql, USER_ROW_MAPPER, id);
            return Optional.ofNullable(user);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public boolean existsByUsername(String username) {
        String sql = "SELECT COUNT(*) FROM users WHERE username = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, username);
        return count != null && count > 0;
    }

    public User create(String username, String passwordHash, UserRole role) {
        String sql = "INSERT INTO users (username, password_hash, role, enabled, created_at) " +
                     "VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP) RETURNING id, username, password_hash, role, enabled, created_at";
        return jdbcTemplate.queryForObject(sql, USER_ROW_MAPPER, username, passwordHash, role.name());
    }
}

