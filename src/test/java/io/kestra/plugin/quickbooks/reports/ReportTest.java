package io.kestra.plugin.quickbooks.reports;

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

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
public class ReportTest {

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
    public static class TestReport extends Report {
    }

    @Test
    void testGetReport() throws Exception {
        wireMockServer.resetAll();
        
        stubFor(post(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\": \"fresh_token\", \"refresh_token\": \"fresh_refresh\", \"expires_in\": 3600, \"x_refresh_token_expires_in\": 8726400}")));

        stubFor(get(urlPathEqualTo("/v3/company/12345/reports/ProfitAndLoss"))
            .withQueryParam("minorversion", equalTo("75"))
            .withQueryParam("start_date", equalTo("2023-01-01"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{" +
                    "\"Header\": {" +
                    "\"ReportName\": \"ProfitAndLoss\"," +
                    "\"ReportBasis\": \"Accrual\"" +
                    "}," +
                    "\"Rows\": {" +
                    "\"Row\": []" +
                    "}" +
                    "}")));

        RunContext runContext = runContextFactory.of(Map.of(
            "flow", Map.of(
                "tenantId", "",
                "id", "test-flow",
                "namespace", "io.kestra.plugin.quickbooks.test",
                "revision", 1
            )
        ));

        TestReport task = TestReport.builder()
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .authUrl(Property.ofValue("http://localhost:" + wireMockServer.port() + "/oauth2/v1/tokens/bearer"))
            .reportName(Property.ofValue("ProfitAndLoss"))
            .parameters(Property.ofValue(Map.of("start_date", "2023-01-01")))
            .build();

        Report.Output run = task.run(runContext);

        assertThat(run, is(notNullValue()));
        assertThat(run.getRow().get("Header"), is(notNullValue()));
        Map<String, Object> header = (Map<String, Object>) run.getRow().get("Header");
        assertThat(header.get("ReportName"), is("ProfitAndLoss"));
    }
}
