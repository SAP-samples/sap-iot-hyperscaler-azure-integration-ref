package com.sap.iot.azure.ref.ingestion;

import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.Cardinality;
import com.microsoft.azure.functions.annotation.EventHubTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.sap.iot.azure.ref.ingestion.exception.IngestionErrorType;
import com.sap.iot.azure.ref.ingestion.exception.IngestionRuntimeException;
import com.sap.iot.azure.ref.ingestion.output.ADXEventHubProcessor;
import com.sap.iot.azure.ref.ingestion.service.AvroMessageService;
import com.sap.iot.azure.ref.ingestion.util.Constants;
import com.sap.iot.azure.ref.integration.commons.constants.CommonConstants;
import com.sap.iot.azure.ref.integration.commons.context.InvocationContext;
import com.sap.iot.azure.ref.integration.commons.exception.base.IoTRuntimeException;
import com.sap.iot.azure.ref.integration.commons.metrics.MetricsClient;
import com.sap.iot.azure.ref.integration.commons.retry.RetryTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

import static com.sap.iot.azure.ref.ingestion.util.Constants.*;

@SuppressWarnings("unused") //AZ Function
public class AvroParserFunction {

    private final AvroMessageService avroMessageService;
    private static final RetryTaskExecutor retryTaskExecutor = new RetryTaskExecutor();
    private final ADXEventHubProcessor adxEventHubProcessor;

    public AvroParserFunction() {
        this(new AvroMessageService(), new ADXEventHubProcessor());
    }

    static {
        InvocationContext.setupInitializationContext(AVRO_PARSER_FUNCTION);
    }

    AvroParserFunction(AvroMessageService avroMessageService, ADXEventHubProcessor adxEventHubProcessor) {
        this.avroMessageService = avroMessageService;
        this.adxEventHubProcessor = adxEventHubProcessor;

        InvocationContext.closeInitializationContext();
    }

    /**
     * Azure function which invoked by an EventHub trigger.
     * The Trigger is connected to the built in ProcessedTimeSeriesIN EventHub Endpoint.
     * The supported payload is AVRO in {@link CommonConstants#TRIGGER_EVENT_HUB_DATA_TYPE_BINARY} format.
     * The message payloads are brought in to an AVRO format following SAP-defined Processed-Time-Series AVRO Schema,
     * and deserialized into a map of sourceId & list of processedMessages and sent to the downstream ADXEventHub using the {@link ADXEventHubProcessor}.
     *
     * @param avroMessages,     incoming message payload in AVRO binary format
     * @param systemProperties, system properties including message header information, such as the PartitionKey
     * @param context,          invocation context of the current Azure Function invocation
     */
    @FunctionName(value = AVRO_PARSER_FUNCTION)
    public void run(
            @EventHubTrigger(name = Constants.TRIGGER_NAME,
                    eventHubName = Constants.PROCESSED_TIME_SERIES_IN_EVENT_HUB,
                    connection = Constants.PROCESSED_TIME_SERIES_IN_CONNECTION_STRING,
                    consumerGroup = Constants.PROCESSED_TIME_SERIES_IN_EVENT_HUB_CONSUMER_GROUP,
                    cardinality = Cardinality.MANY,
                    dataType = CommonConstants.TRIGGER_EVENT_HUB_DATA_TYPE_BINARY)
                    List<byte[]> avroMessages,
            @BindingName(value = CommonConstants.TRIGGER_SYSTEM_PROPERTIES_ARRAY_NAME) Map<String, Object>[] systemProperties,
            @BindingName(value = CommonConstants.PARTITION_CONTEXT) Map<String, Object> partitionContext,
            final ExecutionContext context) {
        JsonNode batchDetails = InvocationContext.getInvocationBatchInfo(partitionContext, systemProperties);
        try {
            InvocationContext.setupInvocationContext(context);
            retryTaskExecutor.executeWithRetry(() -> processMessages(avroMessages, systemProperties), Constants.MAX_RETRIES).join();
        } catch (IoTRuntimeException e) {
            e.addIdentifiers((ObjectNode) batchDetails);
            throw e;
        } catch (Exception e) {
            throw new IngestionRuntimeException("Parsing Avro Message and write to ADX Event Hub failed", e, IngestionErrorType.INVALID_PROCESSED_MESSAGE,
                    batchDetails, false);
        } finally {
            InvocationContext.closeInvocationContext();
        }
    }

    /**
     * all processes in this method happens in a async thread so that any exception can be caught by the catchExceptionally block
     *
     * @param messages,         list of Avro messages
     * @param systemProperties, system properties
     * @return completable future for processing the incoming message asynchronously
     */
    private CompletableFuture<Void> processMessages(List<byte[]> messages, Map<String, Object>[] systemProperties) {

        return CompletableFuture.supplyAsync(InvocationContext.withContext((Supplier<Void>) () ->
                CompletableFuture.allOf(avroMessageService.createProcessedMessage(messages, systemProperties)
                        .entrySet().stream()
                        .filter(Objects::nonNull)
                        .peek(entry -> {
                            // metric to capture the number of measurements processed - each batch "messages" can have multiple Avro messages and each Avro
                            // message can have multiple measurements
                            MetricsClient.trackMetric(MetricsClient.getMetricName("MessagesProcessed"), entry.getValue().getProcessedMessages().size());
                        })
                        .map(messageGroup -> CompletableFuture.allOf(adxEventHubProcessor.apply(messageGroup))).toArray(CompletableFuture[]::new))
                        .join()
        ));
    }
}