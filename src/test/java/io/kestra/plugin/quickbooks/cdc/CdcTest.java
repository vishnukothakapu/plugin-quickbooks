package io.kestra.plugin.quickbooks.cdc;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
public class CdcTest {

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

    @lombok.experimental.SuperBuilder
    @lombok.NoArgsConstructor
    public static class TestCdc extends Cdc {
    }

    @Test
    void testGetCdc() throws Exception {
        wireMockServer.resetAll();
        
        stubFor(post(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\": \"fresh_token\", \"refresh_token\": \"fresh_refresh\", \"expires_in\": 3600, \"x_refresh_token_expires_in\": 8726400}")));

        stubFor(get(urlPathEqualTo("/v3/company/12345/cdc"))
            .withQueryParam("minorversion", equalTo("75"))
            .withQueryParam("entities", equalTo("Invoice,Customer"))
            .withQueryParam("changedSince", equalTo("2023-09-01T12:00:00Z"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{" +
                    "\"CDCResponse\": [{" +
                    "\"QueryResponse\": [{" +
                    "\"Invoice\": [{" +
                    "\"Id\": \"130\"," +
                    "\"status\": \"Updated\"" +
                    "}]" +
                    "}]" +
                    "}]," +
                    "\"time\": \"2023-09-02T12:00:00Z\"" +
                    "}")));

        RunContext runContext = runContextFactory.of(Map.of(
            "flow", Map.of(
                "tenantId", "",
                "id", "test-flow",
                "namespace", "io.kestra.plugin.quickbooks.test",
                "revision", 1
            )
        ));

        TestCdc task = TestCdc.builder()
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .authUrl(Property.ofValue("http://localhost:" + wireMockServer.port() + "/oauth2/v1/tokens/bearer"))
            .entities(Property.ofValue(List.of("Invoice", "Customer")))
            .changedSince(Property.ofValue("2023-09-01T12:00:00Z"))
            .build();

        Cdc.Output run = task.run(runContext);

        assertThat(run, is(notNullValue()));
        assertThat(run.getCdcResponse().get("CDCResponse"), is(notNullValue()));
        List<Map<String, Object>> cdcResponses = (List<Map<String, Object>>) run.getCdcResponse().get("CDCResponse");
        assertThat(cdcResponses.size(), is(1));
    }
}
