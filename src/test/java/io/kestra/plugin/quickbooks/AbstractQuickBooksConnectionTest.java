package io.kestra.plugin.quickbooks;

import io.kestra.plugin.quickbooks.AbstractQuickBooksConnection.OAuthState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVValueAndMetadata;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class AbstractQuickBooksConnectionTest {

    @Inject
    private RunContextFactory runContextFactory;

    private static WireMockServer wireMockServer;

    @BeforeAll
    static void setup() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @AfterAll
    static void teardown() {
        wireMockServer.stop();
    }

    @SuperBuilder
    @Getter
    @NoArgsConstructor
    public static class QuickBooksTestTask extends AbstractQuickBooksConnection {
        @Override
        protected void validateUrl(String name, String url) throws Exception {
            var uri = java.net.URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"localhost".equals(uri.getHost()) && !"127.0.0.1".equals(uri.getHost())) {
                super.validateUrl(name, url);
            }
        }
    }

    private String getRandomRealmId() {
        return String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits())).substring(0, 9);
    }

    private String getKvKey(String realmId, String clientId) throws Exception {
        var clientIdHash = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(clientId.getBytes(StandardCharsets.UTF_8)));
        return "quickbooks_oauth_" + realmId + "_" + clientIdHash;
    }

    private QuickBooksTestTask.QuickBooksTestTaskBuilder<?, ?> getBaseBuilder(String realmId) {
        return QuickBooksTestTask.builder()
            .clientId(Property.ofValue("test_client"))
            .clientSecret(Property.ofValue("test_secret"))
            .refreshToken(Property.ofValue("initial_refresh_token"))
            .realmId(Property.ofValue(realmId))
            .authUrl(Property.ofValue(wireMockServer.baseUrl() + "/oauth2/v1/tokens/bearer"));
    }


    private RunContext getRunContext() {
        return runContextFactory.of(Map.of(
            "flow", Map.of(
                "tenantId", "",
                "id", "test-flow",
                "namespace", "io.kestra.plugin.quickbooks.test",
                "revision", 1
            )
        ));
    }

    @Test
    void testGetAccessTokenNewToken() throws Exception {
        wireMockServer.resetAll();
        stubFor(post(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\": \"new_access_token\", \"refresh_token\": \"new_refresh_token\", \"expires_in\": 3600, \"x_refresh_token_expires_in\": 8726400}")));

        var realmId = getRandomRealmId();
        var task = getBaseBuilder(realmId).build();
        var runContext = getRunContext();

        var accessToken = task.getAccessToken(runContext);
        assertThat(accessToken, is("new_access_token"));

        var kvStore = runContext.namespaceKv(runContext.flowInfo().namespace());
        var val = kvStore.getValue(getKvKey(realmId, "test_client"));
        assertThat(val.isPresent(), is(true));
        
        var decrypted = runContext.decrypt(val.get().value().toString());
        assertThat(decrypted.contains("new_access_token"), is(true));

        var cachedAccessToken = task.getAccessToken(runContext);
        assertThat(cachedAccessToken, is("new_access_token"));

        verify(1, postRequestedFor(urlEqualTo("/oauth2/v1/tokens/bearer")));
    }

    @Test
    void testMissingEncryptionKey() throws Exception {
        wireMockServer.resetAll();
        stubFor(post(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\": \"new_access\", \"refresh_token\": \"new_refresh\", \"expires_in\": 3600, \"x_refresh_token_expires_in\": 8726400}")));

        var runContext = org.mockito.Mockito.spy(getRunContext());
        
        // Simulate Kestra throwing an exception when encryption is misconfigured
        org.mockito.Mockito.doThrow(new RuntimeException("Simulated missing key")).when(runContext).encrypt(org.mockito.ArgumentMatchers.anyString());
        
        var task = getBaseBuilder(getRandomRealmId()).build();
        
        Exception e = assertThrows(IllegalStateException.class, () -> task.getAccessToken(runContext));
        assertThat(e.getMessage().contains("kestra.encryption.secret-key"), is(true));
    }

    @Test
    void testInvalidGrantRetry() throws Exception {
        wireMockServer.resetAll();
        // First request returns 400 invalid_grant
        stubFor(post(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .inScenario("Retry")
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"invalid_grant\"}"))
            .willSetStateTo("Retried"));

        // Second request succeeds
        stubFor(post(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .inScenario("Retry")
            .whenScenarioStateIs("Retried")
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\": \"retried_token\", \"refresh_token\": \"retried_refresh\", \"expires_in\": 3600, \"x_refresh_token_expires_in\": 8726400}")));

        var realmId = getRandomRealmId();
        var task = getBaseBuilder(realmId).build();
        var runContext = getRunContext();

        // Populate state with a DIFFERENT refresh token to trigger the retry condition
        var state = new OAuthState();
        state.setRefreshToken("stale_refresh_token");
        state.setAccessToken("old");
        state.setExpiresAt(Instant.now().getEpochSecond() - 3600);
        state.setSeedHash("wrong_seed");
        
        var kvStore = runContext.namespaceKv(runContext.flowInfo().namespace());
        kvStore.put(getKvKey(realmId, "test_client"), new KVValueAndMetadata(null, runContext.encrypt(JacksonMapper.ofJson().writeValueAsString(state))));

        // Because "initial_refresh_token" != "stale_refresh_token", the invalid_grant retry should kick in and use "initial_refresh_token".
        var accessToken = task.getAccessToken(runContext);
        assertThat(accessToken, is("retried_token"));
        verify(2, postRequestedFor(urlEqualTo("/oauth2/v1/tokens/bearer")));
    }

    @Test
    void testGetAccessTokenValidationErrors() {
        var runContext = getRunContext();

        // 1. Invalid realmId
        var taskRealm = getBaseBuilder("abc").build();
        assertThrows(IllegalArgumentException.class, () -> taskRealm.getAccessToken(runContext));

        // 2. Invalid baseUrl
        var taskBaseUrl = getBaseBuilder("123").baseUrl(Property.ofValue("http://evil.com")).build();
        assertThrows(IllegalArgumentException.class, () -> taskBaseUrl.getValidatedBaseUrl(runContext));

        // 3. Invalid authUrl
        var taskAuthUrl = getBaseBuilder("123").authUrl(Property.ofValue("http://evil.com")).build();
        assertThrows(IllegalArgumentException.class, () -> taskAuthUrl.getValidatedAuthUrl(runContext));

        // 4. Invalid minorVersion
        var taskMinorVersion = getBaseBuilder("123").minorVersion(Property.ofValue(-1)).build();
        assertThrows(IllegalArgumentException.class, () -> taskMinorVersion.getValidatedMinorVersion(runContext));
    }

    @Test
    void testGetAccessTokenCorruptedState() throws Exception {
        wireMockServer.resetAll();
        stubFor(post(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\": \"fresh_token\", \"refresh_token\": \"fresh_refresh\", \"expires_in\": 3600, \"x_refresh_token_expires_in\": 8726400}")));

        var realmId = getRandomRealmId();
        var task = getBaseBuilder(realmId).build();
        var runContext = getRunContext();
        var kvStore = runContext.namespaceKv(runContext.flowInfo().namespace());

        kvStore.put(getKvKey(realmId, "test_client"), new KVValueAndMetadata(
            null,
            runContext.encrypt("corrupted_json_string")
        ));

        // Corrupted state should be caught and throw an error telling user to clear KV store
        var exception = assertThrows(IllegalStateException.class, () -> task.getAccessToken(runContext));
        assertThat(exception.getMessage().contains("Corrupted OAuth state in KV store"), is(true));
    }

    @Test
    void testGetAccessTokenSeedMismatch() throws Exception {
        wireMockServer.resetAll();
        stubFor(post(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\": \"fresh_token\", \"refresh_token\": \"fresh_refresh\", \"expires_in\": 3600, \"x_refresh_token_expires_in\": 8726400}")));

        var realmId = getRandomRealmId();
        var task = getBaseBuilder(realmId).build();
        var runContext = getRunContext();
        var kvStore = runContext.namespaceKv(runContext.flowInfo().namespace());

        var mismatchedState = new OAuthState();
        mismatchedState.setAccessToken("old_token");
        mismatchedState.setRefreshToken("old_refresh");
        mismatchedState.setExpiresAt(Instant.now().getEpochSecond() - 3600);
        mismatchedState.setSeedHash("wrong_seed");
        
        kvStore.put(getKvKey(realmId, "test_client"), new KVValueAndMetadata(
            null,
            runContext.encrypt(JacksonMapper.ofJson().writeValueAsString(mismatchedState))
        ));

        // With mismatched seed, it should ignore the KV state and use the initial refresh token
        var accessToken = task.getAccessToken(runContext);
        assertThat(accessToken, is("fresh_token"));
        
        verify(1, postRequestedFor(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .withRequestBody(containing("refresh_token=initial_refresh_token")));
    }

    @SuperBuilder
    @Getter
    @NoArgsConstructor
    public static class QuickBooksConcurrencyTestTask extends AbstractQuickBooksConnection {
        private String injectedRotatedToken;
        
        @Override
        protected void validateUrl(String name, String url) throws Exception {
            var uri = java.net.URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"localhost".equals(uri.getHost()) && !"127.0.0.1".equals(uri.getHost())) {
                super.validateUrl(name, url);
            }
        }

        @Override
        protected TokenResponse performRefresh(RunContext runContext, String rAuthUrl, String rClientId, String rClientSecret, String activeRefreshToken) throws Exception {
            if ("initial_refresh_token".equals(activeRefreshToken)) {
                // Mutate KV store right before HTTP call to simulate another worker having just done it
                var kvStore = runContext.namespaceKv(runContext.flowInfo().namespace());
                var freshState = new OAuthState();
                freshState.setAccessToken("rotated_access");
                freshState.setRefreshToken(injectedRotatedToken);
                freshState.setExpiresAt(Instant.now().getEpochSecond() - 3600); // Expired so it forces refresh
                freshState.setSeedHash(Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest("initial_refresh_token".getBytes(StandardCharsets.UTF_8))));

                var clientIdHash = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(rClientId.getBytes(StandardCharsets.UTF_8)));
                var rRealmId = runContext.render(getRealmId()).as(String.class).get();
                var kvKey = "quickbooks_oauth_" + rRealmId + "_" + clientIdHash;

                kvStore.put(kvKey, new KVValueAndMetadata(
                    null,
                    runContext.encrypt(JacksonMapper.ofJson().writeValueAsString(freshState))
                ));
            }
            return super.performRefresh(runContext, rAuthUrl, rClientId, rClientSecret, activeRefreshToken);
        }
    }

    @Test
    void testGetAccessTokenSecondRefreshAfterRotation() throws Exception {
        wireMockServer.resetAll();
        stubFor(post(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"invalid_grant\"}")));

        var realmId = getRandomRealmId();
        var task = QuickBooksConcurrencyTestTask.builder()
            .clientId(Property.ofValue("test_client"))
            .clientSecret(Property.ofValue("test_secret"))
            .refreshToken(Property.ofValue("initial_refresh_token"))
            .realmId(Property.ofValue(realmId))
            .authUrl(Property.ofValue(wireMockServer.baseUrl() + "/oauth2/v1/tokens/bearer"))
            .injectedRotatedToken("new_rotated_refresh")
            .build();
            
        var runContext = getRunContext();

        // The first HTTP request inside getAccessToken will fail because wiremock always returns 400.
        // However, before making the request, performRefresh injects "new_rotated_refresh" into KV store.
        // The catch block will read KV, find "new_rotated_refresh", and trigger the second request!

        var exception = assertThrows(IllegalStateException.class, () -> task.getAccessToken(runContext));
        assertThat(exception.getMessage().contains("Intuit OAuth2 refresh failed"), is(true));

        // We expect TWO requests:
        // 1. First request with "initial_refresh_token" -> returned 400
        // 2. Second request with "new_rotated_refresh" -> returned 400
        verify(1, postRequestedFor(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .withRequestBody(containing("refresh_token=initial_refresh_token")));
        verify(1, postRequestedFor(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .withRequestBody(containing("refresh_token=new_rotated_refresh")));
    }

    @Test
    void testGetAccessTokenNon2xx() {
        wireMockServer.resetAll();
        stubFor(post(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"invalid_grant\"}")));

        var realmId = getRandomRealmId();
        var task = getBaseBuilder(realmId).build();
        var runContext = getRunContext();

        assertThrows(IllegalStateException.class, () -> task.getAccessToken(runContext));
    }

    @Test
    void testGetAccessTokenEmptyBody() {
        wireMockServer.resetAll();
        stubFor(post(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{}")));

        var realmId = getRandomRealmId();
        var task = getBaseBuilder(realmId).build();
        var runContext = getRunContext();

        assertThrows(IllegalStateException.class, () -> task.getAccessToken(runContext));
    }

    @Test
    void testGetAccessTokenExpiredTokenRefresh() throws Exception {
        wireMockServer.resetAll();
        stubFor(post(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\": \"rotated_access_token\", \"refresh_token\": \"rotated_refresh_token\", \"expires_in\": 3600, \"x_refresh_token_expires_in\": 8726400}")));

        var realmId = getRandomRealmId();
        var task = getBaseBuilder(realmId).build();
        var runContext = getRunContext();
        var kvStore = runContext.namespaceKv(runContext.flowInfo().namespace());

        var expiredState = new OAuthState();
        expiredState.setAccessToken("old_token");
        expiredState.setRefreshToken("old_refresh");
        expiredState.setExpiresAt(Instant.now().getEpochSecond() - 3600);
        var seedHash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest("initial_refresh_token".getBytes(StandardCharsets.UTF_8)));
        expiredState.setSeedHash(seedHash);
        
        kvStore.put(getKvKey(realmId, "test_client"), new KVValueAndMetadata(
            null,
            runContext.encrypt(JacksonMapper.ofJson().writeValueAsString(expiredState))
        ));

        var accessToken = task.getAccessToken(runContext);
        assertThat(accessToken, is("rotated_access_token"));
        
        verify(1, postRequestedFor(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .withRequestBody(containing("refresh_token=old_refresh")));
    }
}
