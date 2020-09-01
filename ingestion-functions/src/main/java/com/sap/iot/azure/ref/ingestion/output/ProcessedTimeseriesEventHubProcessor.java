package com.sap.iot.azure.ref.ingestion.output;

import com.google.common.annotations.VisibleForTesting;
import com.microsoft.azure.eventhubs.EventData;
import com.microsoft.azure.eventhubs.EventHubClient;
import com.sap.iot.azure.ref.ingestion.exception.IngestionErrorType;
import com.sap.iot.azure.ref.ingestion.exception.IngestionRuntimeException;
import com.sap.iot.azure.ref.ingestion.util.Constants;
import com.sap.iot.azure.ref.integration.commons.api.Processor;
import com.sap.iot.azure.ref.integration.commons.avro.AvroHelper;
import com.sap.iot.azure.ref.integration.commons.connection.EventHubClientFactory;
import com.sap.iot.azure.ref.integration.commons.eventhub.BaseEventHubProcessor;
import com.sap.iot.azure.ref.integration.commons.exception.CommonErrorType;
import com.sap.iot.azure.ref.integration.commons.exception.IdentifierUtil;
import com.sap.iot.azure.ref.integration.commons.exception.base.IoTRuntimeException;
import com.sap.iot.azure.ref.integration.commons.mapping.MappingHelper;
import com.sap.iot.azure.ref.integration.commons.model.timeseries.processed.ProcessedMessage;
import com.sap.iot.azure.ref.integration.commons.model.timeseries.processed.ProcessedMessageContainer;
import org.apache.avro.AvroRuntimeException;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.sap.iot.azure.ref.integration.commons.exception.IdentifierUtil.getIdentifier;

public class ProcessedTimeseriesEventHubProcessor extends BaseEventHubProcessor<ProcessedMessageContainer> implements Processor<Map.Entry<String, ProcessedMessageContainer>,
        CompletableFuture<Void>> {

    private static final String CONNECTION_STRING = System.getenv(Constants.PROCESSED_TIMESERIES_CONNECTION_STRING_PROP);

    public ProcessedTimeseriesEventHubProcessor() {
        this(new EventHubClientFactory().getEhClient(CONNECTION_STRING));
    }

    @VisibleForTesting
    ProcessedTimeseriesEventHubProcessor(CompletableFuture<EventHubClient> eventHubClient) {
        super(eventHubClient);
    }

    /**
     * Send a single group of processed messages to the Processed Timeseries Event Hub with the key as partition key.
     * The processed messages will be converted into an AVRO format, using the {@link AvroHelper} and an AVRO schema which is fetched from the
     * {@link MappingHelper}.
     *
     * @param processedMessage processed messages in application model
     * @return completable future from sending adx message to event hub
     */
    @Override
    public CompletableFuture<Void> process(Map.Entry<String, ProcessedMessageContainer> processedMessage) {
        return super.process(processedMessage.getValue(), processedMessage.getKey());
    }

    @Override
    protected List<EventData> createEventData(ProcessedMessageContainer processedMessageContainer) {
        List<EventData> eventDataList = new LinkedList<>();

        List<ProcessedMessage> processedMessages = processedMessageContainer.getProcessedMessages();
        if (processedMessages.size() == 0) return Collections.emptyList();

        try {
            String schemaString = processedMessageContainer.getAvroSchema().orElseThrow(() ->
                    new IngestionRuntimeException("No Avro Schema provided in the processed message", IngestionErrorType.INVALID_PROCESSED_MESSAGE, IdentifierUtil.empty(), false));

            List<byte[]> avroMessages = AvroHelper.serializeJsonToAvro(processedMessages, schemaString);
            for (byte[] avroMessage : avroMessages) {
                eventDataList.add(EventData.create(avroMessage));
            }

        } catch (AvroRuntimeException e) {
            throw IoTRuntimeException.wrapNonTransient(getIdentifier(processedMessages.get(0).getSourceId(), processedMessages.get(0).getStructureId()),
                    CommonErrorType.AVRO_EXCEPTION, "Avro runtime exception while processing message", e);
        }

        return eventDataList;
    }
}