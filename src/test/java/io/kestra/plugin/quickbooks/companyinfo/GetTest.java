package io.kestra.plugin.quickbooks.companyinfo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
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
public class GetTest {

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

    // Extends Get to override the validation logic so we can test against localhost
    @lombok.experimental.SuperBuilder
    @lombok.NoArgsConstructor
    public static class TestGet extends Get {
    }

    @Test
    void testGetCompanyInfo() throws Exception {
        wireMockServer.resetAll();
        
        // Mock token endpoint
        stubFor(post(urlEqualTo("/oauth2/v1/tokens/bearer"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\": \"new_access\", \"refresh_token\": \"new_refresh\", \"expires_in\": 3600, \"x_refresh_token_expires_in\": 8726400}")));

        // Mock companyinfo endpoint
        stubFor(get(urlPathEqualTo("/v3/company/12345/companyinfo/12345"))
            .withQueryParam("minorversion", equalTo("75"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{" +
                    "\"CompanyInfo\": {" +
                    "\"CompanyName\": \"Kestra Test Company\"," +
                    "\"LegalName\": \"Kestra Test Company LLC\"," +
                    "\"CompanyAddr\": {" +
                    "\"Line1\": \"123 Main St\"," +
                    "\"City\": \"San Francisco\"," +
                    "\"CountrySubDivisionCode\": \"CA\"," +
                    "\"PostalCode\": \"94105\"" +
                    "}" +
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

        TestGet task = TestGet.builder()
            .clientId(Property.ofValue("client_id"))
            .clientSecret(Property.ofValue("client_secret"))
            .refreshToken(Property.ofValue("refresh_token"))
            .realmId(Property.ofValue("12345"))
            .baseUrl(Property.ofValue("http://localhost:" + wireMockServer.port()))
            .authUrl(Property.ofValue("http://localhost:" + wireMockServer.port() + "/oauth2/v1/tokens/bearer"))
            .build();

        Get.Output run = task.run(runContext);

        assertThat(run.getRow(), notNullValue());
        assertThat(run.getRow().get("CompanyName"), is("Kestra Test Company"));
    }
}
