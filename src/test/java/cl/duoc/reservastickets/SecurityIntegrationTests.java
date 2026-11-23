package cl.duoc.reservastickets;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.datasource.url=jdbc:h2:mem:security-tests",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
class SecurityIntegrationTests {
    private static final String ISSUER = "https://gestionguiasmcabri2026.b2clogin.com/3f3062b4-4f5c-4d4f-a8de-239dc8df83f7/v2.0/";
    private static final String AUDIENCE = "0c3e4311-80ac-4edb-8a72-7c9b248cbfb1";
    private static final String POLICY = "B2C_1_eventos_signupsignin";
    private static HttpServer jwksServer;
    private static RSAKey signingKey;
    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        // Claves efimeras: estas pruebas no usan secretos ni cuentas de Azure.
        signingKey = new RSAKeyGenerator(2048).keyID("security-test").generate();
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] jwks = new JWKSet(signingKey.toPublicJWK()).toString()
            .getBytes(StandardCharsets.UTF_8);
        jwksServer.createContext("/keys", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (var output = exchange.getResponseBody()) {
                output.write(jwks);
            }
        });
        jwksServer.start();
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
            () -> "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/keys");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("app.security.audience", () -> AUDIENCE);
        registry.add("app.security.policy", () -> POLICY);
    }

    @AfterAll
    static void stopJwksServer() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
    }

    private JWTClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().subject("usuario-prueba")
            .issuer(ISSUER).audience(AUDIENCE)
            .issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(10)))
            .expirationTime(Date.from(now.plusSeconds(3600)))
            .claim("tfp", POLICY).claim("scp", "eventos.access");
    }

    private String sign(JWTClaimsSet.Builder claims, RSAKey key) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID("security-test").build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private HttpResponse<String> request(String method, String path, String token,
                                         String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int status(String token) throws Exception {
        return request("GET", "/api/eventos", token, null).statusCode();
    }

    @Test
    void allEndpointsRejectMissingToken() throws Exception {
        Map<String, String> endpoints = Map.ofEntries(
            Map.entry("/api/eventos", "GET"), Map.entry("/api/eventos?crear", "POST"),
            Map.entry("/api/reservas", "POST"), Map.entry("/api/eventos/1/estadisticas", "GET"),
            Map.entry("/api/tickets/generar/1", "POST"), Map.entry("/api/tickets/historial", "GET"),
            Map.entry("/api/tickets/prueba", "PUT"), Map.entry("/api/tickets/prueba/descargar", "GET"),
            Map.entry("/api/tickets/descargar?usuario=prueba", "GET"),
            Map.entry("/api/tickets/prueba?borrar", "DELETE"),
            Map.entry("/api/tickets/prueba/subir", "POST"));
        for (var endpoint : endpoints.entrySet()) {
            assertEquals(401, request(endpoint.getValue(), endpoint.getKey(), null, null)
                .statusCode(), endpoint.toString());
        }
    }

    @Test
    void signedAccessTokenAllowsReadingEventsWithoutCreatingSession() throws Exception {
        var response = request("GET", "/api/eventos", sign(claims(), signingKey), null);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
    }

    @Test
    void signedAccessTokenAllowsCreatingEvent() throws Exception {
        var response = request("POST", "/api/eventos", sign(claims(), signingKey),
            "{\"nombre\":\"Prueba seguridad\",\"fecha\":\"2026-12-01\",\"precio\":10000}");
        assertEquals(201, response.statusCode());
    }

    @Test
    void malformedTokenIsRejected() throws Exception {
        assertEquals(401, status("no-es-un-jwt"));
    }

    @Test
    void signatureFromUnknownKeyIsRejected() throws Exception {
        RSAKey attackerKey = new RSAKeyGenerator(2048).generate();
        assertEquals(401, status(sign(claims(), attackerKey)));
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        assertEquals(401, status(sign(claims().issuer("https://otro-emisor.example"), signingKey)));
    }

    @Test
    void tokenForClientInsteadOfApiIsRejected() throws Exception {
        assertEquals(401, status(sign(claims()
            .audience("effd7ef2-ad06-4e4f-bb39-1b690fef64e8"), signingKey)));
    }

    @Test
    void missingAudienceIsRejected() throws Exception {
        assertEquals(401, status(sign(claims().audience((String) null), signingKey)));
    }

    @Test
    void recoveryPolicyIsRejected() throws Exception {
        assertEquals(401, status(sign(claims()
            .claim("tfp", "B2C_1_eventos_passwordreset"), signingKey)));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        assertEquals(401, status(sign(claims()
            .expirationTime(Date.from(Instant.now().minusSeconds(300)))
            .notBeforeTime(Date.from(Instant.now().minusSeconds(600))), signingKey)));
    }

    @Test
    void tokenNotYetValidIsRejected() throws Exception {
        assertEquals(401, status(sign(claims()
            .notBeforeTime(Date.from(Instant.now().plusSeconds(300))), signingKey)));
    }

    @Test
    void validTokenWithoutRequiredScopeIsForbidden() throws Exception {
        assertEquals(403, status(sign(claims().claim("scp", "otro.access"), signingKey)));
    }

    @Test
    void authenticatedBusinessErrorsKeepTheirStatusCodes() throws Exception {
        String token = sign(claims(), signingKey);
        assertEquals(404, request("GET", "/api/eventos/999999/estadisticas", token, null)
            .statusCode());
        assertEquals(400, request("POST", "/api/eventos", token,
            "{\"nombre\":\"Invalido\",\"fecha\":\"2026-12-01\",\"precio\":-1}")
            .statusCode());
    }
}
