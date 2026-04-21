package com.pulsar.kernel.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Contract test against the shared fixture at docs/jwt-contract/fixtures.json.
 *
 * <p>This exists to catch drift between the two HMAC JWT libraries we rely on: jjwt (here) and
 * jsonwebtoken (in the automation service). Both services parse the same tokens produced once at
 * fixture-generation time; if either lib stops accepting them, both suites fail loudly.
 *
 * <p>Fixture location resolution order:
 *
 * <ol>
 *   <li>System property {@code jwt.contract.fixture} (absolute path)
 *   <li>Env var {@code JWT_CONTRACT_FIXTURE} (absolute path)
 *   <li>Walk up from the working directory looking for {@code docs/jwt-contract/fixtures.json}
 * </ol>
 */
class JwtContractTest {

    private static final String FIXTURE_RELATIVE = "docs/jwt-contract/fixtures.json";

    @TestFactory
    Iterable<DynamicTest> verifyEveryFixtureCase() throws Exception {
        File fixture = resolveFixture();
        assertNotNull(fixture, "Could not locate " + FIXTURE_RELATIVE);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(fixture);
        JsonNode cases = root.get("cases");
        assertNotNull(cases, "fixture missing 'cases' array");

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases) {
            String name = c.get("name").asText();
            String secret = c.get("secret").asText();
            String algorithm = c.get("algorithm").asText();
            String token = c.get("token").asText();
            JsonNode expected = c.get("expected");

            tests.add(
                DynamicTest.dynamicTest(
                    name,
                    () -> verifyCase(name, secret, algorithm, token, expected)));
        }
        return tests;
    }

    private static void verifyCase(
        String name, String secret, String algorithm, String token, JsonNode expected) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Jws<Claims> parsed;
        try {
            parsed = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        } catch (RuntimeException e) {
            fail("[" + name + "] jjwt failed to verify fixture token: " + e.getMessage(), e);
            return;
        }

        String headerAlg = parsed.getHeader().getAlgorithm();
        assertEquals(
            algorithm, headerAlg, "[" + name + "] header.alg mismatch (fixture vs parsed)");

        Claims claims = parsed.getPayload();
        Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String claimName = field.getKey();
            String wanted = field.getValue().asText();
            Object actualObj;
            if ("sub".equals(claimName)) {
                actualObj = claims.getSubject();
            } else {
                actualObj = claims.get(claimName);
            }
            String actual = actualObj == null ? null : actualObj.toString();
            assertEquals(
                wanted,
                actual,
                "[" + name + "] claim '" + claimName + "' mismatch (expected=" + wanted
                    + ", actual=" + actual + ")");
        }
    }

    private static File resolveFixture() {
        String prop = System.getProperty("jwt.contract.fixture");
        if (prop != null && !prop.isBlank()) {
            File f = new File(prop);
            if (f.isFile()) return f;
        }
        String env = System.getenv("JWT_CONTRACT_FIXTURE");
        if (env != null && !env.isBlank()) {
            File f = new File(env);
            if (f.isFile()) return f;
        }
        // Walk up from cwd; kernel tests usually run from pulsar-backend/kernel.
        File dir = new File(".").getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParentFile()) {
            File candidate = new File(dir, FIXTURE_RELATIVE);
            if (candidate.isFile()) return candidate;
        }
        return null;
    }
}
