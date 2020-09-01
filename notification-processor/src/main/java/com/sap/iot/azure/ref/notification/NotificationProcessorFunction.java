package com.sap.iot.azure.ref.notification;

import java.io.IOException;
import java.util.logging.Level;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.Cardinality;
import com.microsoft.azure.functions.annotation.EventHubTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.sap.iot.azure.ref.integration.commons.constants.CommonConstants;
import com.sap.iot.azure.ref.integration.commons.context.InvocationContext;
import com.sap.iot.azure.ref.notification.processing.NotificationHandler;
import com.sap.iot.azure.ref.notification.util.Constants;

import java.util.List;
import java.util.Map;

import static com.sap.iot.azure.ref.integration.commons.constants.CommonConstants.TRIGGER_SYSTEM_PROPERTIES_ARRAY_NAME;

/**
 * Azure function for handling of model change notification payloads generated by model onboarding and
 * mapping applications. These can be Structure, Assignment or Mapping changes.
 */

public class NotificationProcessorFunction {

    private final NotificationHandler notificationHandler;

    public NotificationProcessorFunction() {
        this( new NotificationHandler() );
    }

    @VisibleForTesting
    NotificationProcessorFunction( NotificationHandler notificationHandler ) {
        this.notificationHandler = notificationHandler;
    }

    /**
     * Azure function which invoked by an by an EventHub trigger.
     * The Trigger is connected to a Notification Processor EventHub Topic.
     * This function receives the notification payload, checks the entity type and accordingly calls
     * the relevant processor
     */
    @FunctionName("NotificationProcessor")
    public void run(
            @EventHubTrigger(
                    name = Constants.TRIGGER_NAME,
                    eventHubName = Constants.TRIGGER_EVENT_HUB_NAME,
                    connection = Constants.TRIGGER_EVENT_HUB_CONNECTION_STRING_PROP,
                    consumerGroup = Constants.TRIGGER_EVENT_HUB_CONSUMER_GROUP,
                    cardinality = Cardinality.MANY
            )List<String> messages,
            @BindingName(value = Constants.TRIGGER_SYSTEM_PROPERTIES_NAME) Map<String, Object>[] systemProperties,
            @BindingName(value = CommonConstants.PARTITION_CONTEXT) Map<String, Object> partitionContext,
            final ExecutionContext context) throws IOException {

        try {
            InvocationContext.setupInvocationContext(context);
            notificationHandler.executeNotificationHandling(messages, systemProperties);
            InvocationContext.getLogger().log(Level.INFO, " Notification processed");
        } catch (Exception e) {

            // per message exception are handled in NotificationHandler, and are not bubbled up. Since the entire batch has failed,  capturing batch details
            JsonNode batchDetails = InvocationContext.getInvocationBatchInfo(partitionContext, systemProperties);
            InvocationContext.getLogger().log(Level.SEVERE, String.format("Notification Processing failed for batch Partition ID: %s, Start Offset: %s, " +
                            "End Offset: %s; Exiting after %s", batchDetails.get("PartitionId").asText(), batchDetails.get("OffsetStart").asText(),
                    batchDetails.get("OffsetEnd").asText(), Constants. MAX_RETRIES), e);

            throw e; // ensure function invocation status is set to failed
        } finally {
            InvocationContext.closeInvocationContext();
        }
    }
}