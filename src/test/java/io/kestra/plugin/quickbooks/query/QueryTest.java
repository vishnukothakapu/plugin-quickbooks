package io.kestra.plugin.quickbooks.query;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
class QueryTest {

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

    // Extends Query to override the validation logic so we can test against localhost
    @SuperBuilder
    @Getter
    @NoArgsConstructor
    static class TestQuery extends Query {
        @Override
        protected void validateUrl(String name, String url) throws Exception {
            var uri = java.net.URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"localhost".equals(uri.getHost()) && !"127.0.0.1".equals(uri.getHost())) {
                super.validateUrl(name, url);
            }
        }
    }

    @Test
    void testQueryFetch() throws Exception {
        wireMockServer.resetAll();
        
        // Mock token endpoint
        stubFor(post(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\": \"new_access\", \"refresh_token\": \"new_refresh\", \"expires_in\": 3600, \"x_refresh_token_expires_in\": 8726400}")));

        // Mock query endpoint
        stubFor(get(urlPathEqualTo("/v3/company/12345/query"))
            .withQueryParam("query", equalTo("select * from Invoice STARTPOSITION 1 MAXRESULTS 1000"))
            .withQueryParam("minorversion", equalTo("75"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{" +
                    "\"QueryResponse\": {" +
                    "\"Invoice\": [{\"Id\": \"1\"}, {\"Id\": \"2\"}]," +
                    "\"startPosition\": 1," +
                    "\"maxResults\": 1000" +
                    "}," +
                    "\"time\": \"2024-03-01T12:00:00.000Z\"" +
                    "}")));

        RunContext runContext = runContextFactory.of(Map.of(
            "flow", Map.of(
                "tenantId", "",
                "id", "test-flow",
                "namespace", "io.kestra.plugin.quickbooks.test",
                "revision", 1
            )
        ));

        TestQuery task = TestQuery.builder()
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .authUrl(Property.ofValue("http://localhost:" + wireMockServer.port() + "/oauth2/v1/tokens/bearer"))
            .query(Property.ofValue("select * from Invoice"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        FetchOutput run = task.run(runContext);

        assertThat(run.getSize(), is(2L));
        assertThat(run.getRows().size(), is(2));
        
        Map<String, Object> firstRow = (Map<String, Object>) run.getRows().get(0);
        assertThat(firstRow.get("Id"), is("1"));
    }

    @Test
    void testQueryStore() throws Exception {
        wireMockServer.resetAll();
        
        // Mock token endpoint
        stubFor(post(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\": \"new_access\", \"refresh_token\": \"new_refresh\", \"expires_in\": 3600, \"x_refresh_token_expires_in\": 8726400}")));

        // Mock query endpoint
        stubFor(get(urlPathEqualTo("/v3/company/12345/query"))
            .withQueryParam("query", equalTo("select * from Invoice STARTPOSITION 1 MAXRESULTS 1000"))
            .withQueryParam("minorversion", equalTo("75"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{" +
                    "\"QueryResponse\": {" +
                    "\"Invoice\": [{\"Id\": \"1\"}, {\"Id\": \"2\"}]," +
                    "\"startPosition\": 1," +
                    "\"maxResults\": 1000" +
                    "}," +
                    "\"time\": \"2024-03-01T12:00:00.000Z\"" +
                    "}")));

        RunContext runContext = runContextFactory.of(Map.of(
            "flow", Map.of(
                "tenantId", "",
                "id", "test-flow",
                "namespace", "io.kestra.plugin.quickbooks.test",
                "revision", 1
            )
        ));

        TestQuery task = TestQuery.builder()
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .authUrl(Property.ofValue("http://localhost:" + wireMockServer.port() + "/oauth2/v1/tokens/bearer"))
            .query(Property.ofValue("select * from Invoice"))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        FetchOutput run = task.run(runContext);

        assertThat(run.getSize(), is(2L));
        assertThat(run.getUri(), notNullValue());
    }
}
