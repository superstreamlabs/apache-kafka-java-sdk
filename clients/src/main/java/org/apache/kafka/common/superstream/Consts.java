package org.apache.kafka.common.superstream;

import java.util.ArrayList;
import java.util.List;

public class Consts {
    public static final String sdkVersion = "3.5.118";
    public static final String clientReconnectionUpdateSubject = "internal_tasks.clientReconnectionUpdate";
    public static final String clientTypeUpdateSubject = "internal.clientTypeUpdate";
    public static final String clientConfigUpdateSubject = "internal.clientConfigUpdate";
    public static final String clientRegisterSubject = "internal.registerClient";
    public static final String originalSerializer = "original.serializer";
    public static final String originalDeserializer = "original.deserializer";
    public static final String superstreamDefaultToken = "no-auth";
    public static final String superstreamErrorSubject = "internal.clientErrors";
    public static final String superstreamUpdatesSubject = "internal.updates.%s";
    public static final String superstreamClientsUpdateSubject = "internal_tasks.clientsUpdate.%s.%s";
    public static final String superstreamLearningSubject = "internal.schema.learnSchema.%s";
    public static final String superstreamRegisterSchemaSubject = "internal_tasks.schema.registerSchema.%s";
    public static final String superstreamInternalUsername = "superstream_internal";
    public static final String superstreamGetSchemaSubject = "internal.schema.getSchema.%s";
    public static final Integer superstreamDefaultLearningFactor = 20;
    public static final String superstreamLearningFactorKey = "superstream.learning.factor";
    public static final String superstreamTagsKey = "superstream.tags";
    public static final String superstreamHostKey = "superstream.host";
    public static final String superstreamTokenKey = "superstream.token";
    public static final String superstreamReductionEnabledKey = "superstream.reduction.enabled";
    public static final String superstreamCompressionEnabledKey = "superstream.compression.enabled";
    public static final String superstreamConnectionKey = "superstream.connection";
    public static final String superstreamInnerConsumerKey = "superstream.inner.consumer";
    public static final String superstreamMetadataTopic = "superstream.metadata";
    public static final String clientStartSubject = "internal.startClient.%s";

    public static final String PRODUCER = "producer";
    public static final String CONSUMER = "consumer";
    public static final String ADMIN = "admin";
    public static final String[] CLIENT_TYPES_LIST = {PRODUCER, CONSUMER, ADMIN};
    public static final String OPTIMIZED_CONFIGURATION_KEY = "optimized_configuration";
    public static final String START_KEY = "start";
    public static final String ERROR_KEY = "error";
    public static final long MAX_TIME_WAIT_CAN_START = 10 * 60 * 1000;
    public static final long WAIT_INTERVAL_CAN_START = 3000;
    public static final long WAIT_INTERVAL_SUPERSTREAM_CONFIG = 30;
    public static final long TIMEOUT_SUPERSTREAM_CONFIG_DEFAULT = 3000;

    public static final String SUPERSTREAM_RESPONSE_TIMEOUT_ENV_VAR = "SUPERSTREAM_RESPONSE_TIMEOUT";
    public static final String SUPERSTREAM_DEBUG_ENV_VAR_ENV_VAR = "SUPERSTREAM_DEBUG";
    public static final String SUPERSTREAM_REDUCTION_ENABLED_ENV_VAR = "SUPERSTREAM_REDUCTION_ENABLED";
    public static final String SUPERSTREAM_TAGS_ENV_VAR = "SUPERSTREAM_TAGS";
    public static final String SUPERSTREAM_LEARNING_FACTOR_ENV_VAR = "SUPERSTREAM_LEARNING_FACTOR";
    public static final String SUPERSTREAM_TOKEN_ENV_VAR = "SUPERSTREAM_TOKEN";
    public static final String SUPERSTREAM_HOST_ENV_VAR = "SUPERSTREAM_HOST";

}
