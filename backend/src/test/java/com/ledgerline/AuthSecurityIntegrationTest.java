package com.ledgerline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.dto.LoginRequest;
import com.ledgerline.dto.LoginResponse;
import com.ledgerline.dto.UserDto;
import com.ledgerline.model.UserRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AuthSecurityIntegrationTest extends BasePostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @org.junit.jupiter.api.BeforeEach
    void setUpRequestFactory() {
        restTemplate.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Login succeeds with valid seeded credentials and returns valid JWT")
    void testLoginSuccess() {
        LoginRequest request = new LoginRequest("admin", "AdminPassword_2026!");
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity("/api/auth/login", request, LoginResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().token()).isNotBlank();
        assertThat(response.getBody().user().username()).isEqualTo("admin");
        assertThat(response.getBody().user().role()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    @DisplayName("Login fails with generic error for incorrect password")
    void testLoginFailureWrongPassword() {
        LoginRequest request = new LoginRequest("admin", "WrongPassword_12345");
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/auth/login", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).isEqualTo("Invalid credentials");
    }

    @Test
    @DisplayName("Login fails with generic error for unknown user (constant-time path)")
    void testLoginFailureUnknownUser() {
        LoginRequest request = new LoginRequest("non_existent_user_999", "SomePassword_12345");
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/auth/login", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).isEqualTo("Invalid credentials");
    }

    @Test
    @DisplayName("Protected route rejects request without JWT token")
    void testProtectedRouteRequiresToken() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/me", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Protected route rejects tampered JWT token")
    void testProtectedRouteRejectsTamperedToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwidXNlcm5hbWUiOiJhZG1pbiJ9.invalid_signature_tampered_bits");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange("/api/me", HttpMethod.GET, entity, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Protected route rejects expired JWT token")
    void testProtectedRouteRejectsExpiredToken() {
        byte[] keyBytes = "a_very_secret_and_long_jwt_signing_key_32_bytes_min!".getBytes(StandardCharsets.UTF_8);
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);

        String expiredToken = Jwts.builder()
            .subject("1")
            .claim("username", "admin")
            .claim("role", "ADMIN")
            .issuedAt(Date.from(past.minus(1, ChronoUnit.HOURS)))
            .expiration(Date.from(past))
            .signWith(Keys.hmacShaKeyFor(keyBytes), Jwts.SIG.HS256)
            .compact();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(expiredToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange("/api/me", HttpMethod.GET, entity, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("GET /api/me returns caller ID, username, and role (never password hash)")
    void testGetMeReturnsCallerDetailsWithoutHash() {
        LoginResponse login = restTemplate.postForEntity(
            "/api/auth/login",
            new LoginRequest("holder", "HolderPassword_2026!"),
            LoginResponse.class
        ).getBody();

        assertThat(login).isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.token());
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange("/api/me", HttpMethod.GET, entity, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("password");
        assertThat(response.getBody()).doesNotContain("hash");
        assertThat(response.getBody()).contains("\"username\":\"holder\"");
        assertThat(response.getBody()).contains("\"role\":\"HOLDER\"");
    }

    @Test
    @DisplayName("HOLDER role cannot access ADMIN-only endpoint (403 Forbidden)")
    void testHolderCannotAccessAdminEndpoint() {
        LoginResponse holderLogin = restTemplate.postForEntity(
            "/api/auth/login",
            new LoginRequest("holder", "HolderPassword_2026!"),
            LoginResponse.class
        ).getBody();

        assertThat(holderLogin).isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(holderLogin.token());
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange("/api/admin/ping", HttpMethod.GET, entity, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).isEqualTo("Access denied: insufficient privileges");
    }

    @Test
    @DisplayName("ADMIN role can access ADMIN-only endpoint (200 OK)")
    void testAdminCanAccessAdminEndpoint() {
        LoginResponse adminLogin = restTemplate.postForEntity(
            "/api/auth/login",
            new LoginRequest("admin", "AdminPassword_2026!"),
            LoginResponse.class
        ).getBody();

        assertThat(adminLogin).isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminLogin.token());
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange("/api/admin/ping", HttpMethod.GET, entity, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("ok");
    }
}
