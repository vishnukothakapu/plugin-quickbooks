package io.kestra.plugin.quickbooks.trigger;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickbooks.cdc.Cdc;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Trigger a flow when QuickBooks entities change",
            full = true,
            code = """
                id: quickbooks_cdc_trigger
                namespace: company.team
                triggers:
                  - id: watch_invoices
                    type: io.kestra.plugin.quickbooks.trigger.CdcTrigger
                    clientId: "{`{ secret('QBO_CLIENT_ID') }`}"
                    clientSecret: "{{ secret('QUICKBOOKS_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QUICKBOOKS_REFRESH_TOKEN') }}"
                    realmId: "{`{ secret('QBO_REALM_ID') }`}"
                    entities:
                      - Invoice
                      - Customer
                    interval: "PT1H"
                tasks:
                  - id: process_changes
                    type: io.kestra.plugin.core.log.Log
                    message: "CDC Output: {{ trigger.cdcResponse }}"
                """
        )
    }
)
@Schema(
    title = "Trigger based on QuickBooks Change Data Capture (CDC)",
    description = "Periodically polls QuickBooks to fetch changed entities since the last successful poll."
)
public class CdcTrigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Cdc.Output> {

    @Schema(
        title = "QuickBooks Client ID"
    )
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    @ToString.Exclude
    protected Property<String> clientId;

    @Schema(
        title = "QuickBooks Client Secret"
    )
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    @ToString.Exclude
    protected Property<String> clientSecret;

    @Schema(
        title = "QuickBooks Refresh Token"
    )
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    @ToString.Exclude
    protected Property<String> refreshToken;

    @Schema(
        title = "QuickBooks Realm ID"
    )
    @PluginProperty(group = "connection")
    @NotNull
    protected Property<String> realmId;

    @Schema(
        title = "Base URL"
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    protected Property<String> baseUrl = Property.ofValue("https://quickbooks.api.intuit.com");

    @Schema(
        title = "Entities",
        description = "A list of QuickBooks entities to check for changes (e.g., 'Invoice', 'Customer')."
    )
    @PluginProperty(dynamic = true, group = "main")
    @NotNull
    private Property<List<String>> entities;

    @Schema(
        title = "Polling interval",
        description = "How often the trigger should poll for changes."
    )
    @Builder.Default
    private final Duration interval = Duration.ofMinutes(15);

    @Override
    public Duration getInterval() {
        return this.interval;
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        
        var kvStore = runContext.namespaceKv(context.getNamespace());
        var kvKey = "quickbooks_cdc_" + context.getFlowId() + "_" + context.getTriggerId();
        
        var kvValue = kvStore.getValue(kvKey).map(val -> val.value().toString());
        ZonedDateTime changedSince;
        if (kvValue.isPresent()) {
            changedSince = ZonedDateTime.parse(kvValue.get(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } else if (context.getDate() != null) {
            changedSince = context.getDate();
        } else {
            changedSince = ZonedDateTime.now().minusHours(24);
        }

        ZonedDateTime pollStartTime = ZonedDateTime.now();

        // 2. Build and run the Cdc task
        Cdc cdcTask = Cdc.builder()
            .id(this.id)
            .type(Cdc.class.getName())
            .clientId(this.clientId)
            .clientSecret(this.clientSecret)
            .refreshToken(this.refreshToken)
            .realmId(this.realmId)
            .baseUrl(this.baseUrl)
            .entities(this.entities)
            .changedSince(Property.ofValue(changedSince.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
            .build();

        Cdc.Output output = cdcTask.run(runContext);

        // 3. Check if there are actual changes
        boolean hasChanges = false;
        Map<String, Object> cdcResponse = output.getCdcResponse();
        if (cdcResponse != null) {
            try {
                com.fasterxml.jackson.databind.JsonNode rootNode = io.kestra.core.serializers.JacksonMapper.ofJson().valueToTree(cdcResponse);
                com.fasterxml.jackson.databind.JsonNode responses = rootNode.get("CDCResponse");
                if (responses != null && responses.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode resp : responses) {
                        com.fasterxml.jackson.databind.JsonNode queryResponses = resp.get("QueryResponse");
                        if (queryResponses != null && queryResponses.isArray()) {
                            for (com.fasterxml.jackson.databind.JsonNode qResp : queryResponses) {
                                if (!qResp.isEmpty()) {
                                    hasChanges = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                runContext.logger().warn("Failed to parse CDCResponse for changes check", e);
            }
        }

        // Update watermark
        kvStore.put(kvKey, new io.kestra.core.storages.kv.KVValueAndMetadata(null, pollStartTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));

        if (hasChanges) {
            Execution execution = TriggerService.generateExecution(this, conditionContext, context, output);
            return Optional.of(execution);
        }

        return Optional.empty();
    }
}
