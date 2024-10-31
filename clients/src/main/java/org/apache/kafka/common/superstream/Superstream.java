package org.apache.kafka.common.superstream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import io.nats.client.*;
import io.nats.client.api.ServerInfo;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.apache.kafka.common.superstream.Consts.*;

public class Superstream {
    public Connection brokerConnection;
    public JetStream jetstream;
    public String superstreamJwt;
    public String superstreamNkey;
    public byte[] descriptorAsBytes;
    public Descriptors.Descriptor descriptor;
    public String natsConnectionID;
    public String clientHash;
    public String accountName;
    public int learningFactor = 20;
    public int learningFactorCounter = 0;
    public boolean learningRequestSent = false;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    public String ProducerSchemaID = "0";
    public String ConsumerSchemaID = "0";
    public Map<String, Descriptors.Descriptor> SchemaIDMap = new HashMap<>();
    public Map<String, Object> configs;
    private Map<String, Object> fullClientConfigs = new HashMap<>();
    private Map<String, ?> superstreamConfigs;
    public SuperstreamCounters clientCounters = new SuperstreamCounters();
    private Subscription updatesSubscription;
    private String host;
    private String token;
    public String type;
    public Boolean reductionEnabled;
    public Map<String, Set<Integer>> topicPartitions = new ConcurrentHashMap<>();
    public ExecutorService executorService = Executors.newFixedThreadPool(3);
    private Integer kafkaConnectionID = 0;
    public Boolean superstreamReady = false;
    private String tags = "";
    public Boolean canStart = false;
    public Boolean compressionEnabled;
    public String compressionType = "zstd";
    public Boolean compressionTurnedOffBySuperstream = false;
    private String clientIp;
    private String clientHost;
    private static boolean isStdoutSuppressed = false;
    private static boolean isStderrSuppressed = false;
    private static PrintStream superstreamPrintStream;
    private static PrintStream superstreamErrStream;
    private static final PrintStream originalOut = System.out;
    private static final PrintStream originalErr = System.err;
    private SuperstreamConfigParser configParser = null;

    static {
        if (Boolean.parseBoolean(System.getenv(SUPERSTREAM_DEBUG_ENV_VAR_ENV_VAR))) {
            System.out.println("Superstream library has been loaded.");
        }
    }

    public Superstream(String token, String host, Integer learningFactor, Map<String, Object> configs,
                       Boolean enableReduction, String type, String tags, Boolean enableCompression) {
        this.learningFactor = learningFactor;
        this.token = token;
        this.host = host;
        this.configs = deepCopyMap(configs);
        this.reductionEnabled = enableReduction;
        this.type = type;
        this.tags = tags;
        this.compressionEnabled = enableCompression;
        superstreamPrintStream = new PrintStream(new ClassOutputStream());
        superstreamErrStream = new PrintStream(new ClassErrorStream());
        this.configParser = new SuperstreamConfigParser();
        // SuperstreamStaticLog.class.hashCode();  
    }

    public Superstream(String token, String host, Integer learningFactor, Map<String, Object> configs,
                       Boolean enableReduction, String type) {
        this(token, host, learningFactor, configs, enableReduction, type, "", false);
    }

    public void init() {
        executorService.submit(() -> {
            try {
                initializeNatsConnection(token, host);
                if (this.brokerConnection != null) {
                    registerClient(configs);
                    waitForStart();
                    if (!canStart) {
                        throw new Exception("Could not start superstream");
                    }
                    superstreamPrintStream.println("Successfully connected to superstream");
                    subscribeToUpdates();
                    superstreamReady = true;
                    reportClientsUpdate();
                    sendClientTypeUpdateReq();
                }
            } catch (Exception e) {
                handleError(e.getMessage());
            }
        });
    }

    private static void checkStdoutEnvVar() {
        if (Boolean.parseBoolean(System.getenv(SUPERSTREAM_DEBUG_ENV_VAR_ENV_VAR))) {
            isStdoutSuppressed = false;
            isStderrSuppressed = false;
        } else {
            isStdoutSuppressed = true;
            isStderrSuppressed = true;
        }
    }

    public void close() {
        try {
            if (brokerConnection != null) {
                brokerConnection.close();
            }
            executorService.shutdown();
        } catch (Exception e) {
        }
    }

    // private Boolean getBooleanEnv(String key, Boolean defaultValue) {
    //     String value = System.getenv(key);
    //     return (value != null) ? Boolean.parseBoolean(value) : defaultValue;
    // }

    private void initializeNatsConnection(String token, String host) {
        try {
            Options options = new Options.Builder()
                    .server(host)
                    .userInfo(superstreamInternalUsername, token)
                    .maxReconnects(-1)
                    .connectionTimeout(Duration.ofSeconds(10))
                    .reconnectWait(Duration.ofSeconds(1))
                    .connectionListener(new ConnectionListener() {
                        @Override
                        public void connectionEvent(Connection conn, Events type) {
                            if (type == Events.DISCONNECTED) {
                                brokerConnection = null;
                                superstreamReady = false;
                                superstreamPrintStream.println("superstream: disconnected from superstream");
                            } else if (type == Events.RECONNECTED) {
                                try {
                                    brokerConnection = conn;
                                    if (brokerConnection != null) {
                                        natsConnectionID = generateNatsConnectionID();
                                        Map<String, Object> reqData = new HashMap<>();
                                        reqData.put("new_nats_connection_id", natsConnectionID);
                                        reqData.put("client_hash", clientHash);
                                        ObjectMapper mapper = new ObjectMapper();
                                        byte[] reqBytes = mapper.writeValueAsBytes(reqData);
                                        brokerConnection.publish(clientReconnectionUpdateSubject, reqBytes);
                                        subscribeToUpdates();
                                        superstreamReady = true;
                                        reportClientsUpdate();
                                    }
                                } catch (Exception e) {
                                    superstreamPrintStream.println(
                                            "superstream: failed to reconnect: " + e.getMessage());
                                }
                                superstreamPrintStream.println("superstream: reconnected to superstream");
                            }
                        }
                    })
                    .build();

            Connection nc = Nats.connect(options);
            if (nc == null) {
                throw new Exception(String.format("Failed to connect to host: %s", host));
            }
            JetStream js = nc.jetStream();
            if (js == null) {
                throw new Exception(String.format("Failed to connect to host: %s", host));
            }
            brokerConnection = nc;
            jetstream = js;
            natsConnectionID = generateNatsConnectionID();
        } catch (Exception e) {
            superstreamPrintStream.println(String.format("superstream: %s", e.getMessage()));
        }
    }

    private String generateNatsConnectionID() {
        ServerInfo serverInfo = brokerConnection.getServerInfo();
        String connectedServerName = serverInfo.getServerName();
        int serverClientID = serverInfo.getClientId();
        return connectedServerName + ":" + serverClientID;
    }

    public void registerClient(Map<String, ?> configs) {
        try {
            String kafkaConnID = consumeConnectionID();
            if (kafkaConnID != null) {
                try {
                    kafkaConnectionID = Integer.parseInt(kafkaConnID);
                } catch (Exception e) {
                    kafkaConnectionID = 0;
                }
            }
            InetAddress localHost = InetAddress.getLocalHost();
            this.clientIp = localHost.getHostAddress();
            this.clientHost = localHost.getHostName();
            Map<String, Object> configToSend = populateConfigToSend(configs);
            Map<String, Object> reqData = new HashMap<>();
            reqData.put("nats_connection_id", natsConnectionID);
            reqData.put("language", "java");
            reqData.put("learning_factor", learningFactor);
            reqData.put("version", sdkVersion);
            reqData.put("config", configToSend);
            reqData.put("reduction_enabled", reductionEnabled);
            reqData.put("connection_id", kafkaConnectionID);
            reqData.put("tags", tags);
            reqData.put("client_ip", clientIp);
            reqData.put("client_host", clientHost);
            ObjectMapper mapper = new ObjectMapper();
            byte[] reqBytes = mapper.writeValueAsBytes(reqData);
            reqData.put("type", this.type);
            Message reply = brokerConnection.request(clientRegisterSubject, reqBytes, Duration.ofMinutes(5));
            if (reply != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> replyData = mapper.readValue(reply.getData(), Map.class);
                Object clientHashObject = replyData.get("client_hash");
                if (clientHashObject != null) {
                    clientHash = clientHashObject.toString();
                } else {
                    superstreamPrintStream.println("superstream: client_hash is not a valid string: " + clientHashObject);
                }
                Object accountNameObject = replyData.get("account_name");
                if (accountNameObject != null) {
                    accountName = accountNameObject.toString();
                } else {
                    superstreamPrintStream.println("superstream: account_name is not a valid string: " + accountNameObject);
                }
                Object learningFactorObject = replyData.get("learning_factor");
                if (learningFactorObject instanceof Integer) {
                    learningFactor = (Integer) learningFactorObject;
                } else if (learningFactorObject instanceof String) {
                    try {
                        learningFactor = Integer.parseInt((String) learningFactorObject);
                    } catch (NumberFormatException e) {
                        superstreamPrintStream.println(
                                "superstream: learning_factor is not a valid integer: " + learningFactorObject);
                    }
                } else {
                    superstreamPrintStream.println("superstream: learning_factor is not a valid integer: " + learningFactorObject);
                }
            } else {
                String errMsg = "superstream: registering client: No reply received within the timeout period.";
                superstreamPrintStream.println(errMsg);
                handleError(errMsg);
            }

        } catch (Exception e) {
            superstreamPrintStream.println(String.format("superstream: %s", e.getMessage()));
        }
    }
    public Map<String, Object> deepCopyMap(Map<String, ?> originalMap) {
        Map<String, Object> copiedMap = new HashMap<>();

        for (Map.Entry<String, ?> entry : originalMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Object copiedValue;
            copiedValue = deepCopyObject(value);
            copiedMap.put(key, copiedValue);
        }
        return copiedMap;
    }

    @SuppressWarnings("unchecked")
    private <T> T deepCopyObject(Object object) {
    try {
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(byteOut);
        out.writeObject(object);
        ByteArrayInputStream byteIn = new ByteArrayInputStream(byteOut.toByteArray());
        ObjectInputStream in = new ObjectInputStream(byteIn);
        return (T) in.readObject();
    } catch (IOException | ClassNotFoundException e) {
        throw new RuntimeException("Error during deep copy", e);
    }
}

    private Map<String, Object> populateConfigToSend(Map<String, ?> configs) {
        Map<String, Object> configToSend = new HashMap<>();
        if (configs != null && !configs.isEmpty()) {
            for (Map.Entry<String, ?> entry : configs.entrySet()) {
                if (!superstreamConnectionKey.equalsIgnoreCase(entry.getKey())) {
                    configToSend.put(entry.getKey(), entry.getValue());
                }
            }
        }

        return configToSend;
    }

    private void waitForStart() {
        CountDownLatch latch = new CountDownLatch(1);
        Dispatcher dispatcher = brokerConnection.createDispatcher((msg) -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> messageData = mapper.readValue(msg.getData(), Map.class);
                if (messageData.containsKey(START_KEY)) {
                    boolean start = (Boolean) messageData.get(START_KEY);
                    if (start) {
                        canStart = true;
                        if(messageData.containsKey(OPTIMIZED_CONFIGURATION_KEY)){
                            Map<String, Object> receivedConfig = (Map<String, Object>) messageData.get(OPTIMIZED_CONFIGURATION_KEY);
                            this.superstreamConfigs = this.configParser.parse(receivedConfig);
                        }
                        latch.countDown();
                    } else {
                        String err = (String) messageData.get(ERROR_KEY);
                        superstreamPrintStream.println("superstream: could not start: " + err);
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        dispatcher.subscribe(String.format(clientStartSubject, clientHash));

        try {
            if (!latch.await(10, TimeUnit.MINUTES)) {
                superstreamPrintStream.println("superstream: unable not connect with superstream for 10 minutes");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            superstreamPrintStream.println("superstream: Could not start superstream: " + e.getMessage());
        } finally {
            dispatcher.unsubscribe(String.format(clientStartSubject, clientHash));
        }
    }

    private String consumeConnectionID() {
        Properties consumerProps = copyAuthConfig();
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        consumerProps.put(superstreamInnerConsumerKey, "true");
        consumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        String connectionId = null;
        KafkaConsumer<String, String> consumer = null;
        try {
            consumer = new KafkaConsumer<>(consumerProps);
            List<PartitionInfo> partitions = consumer.partitionsFor(superstreamMetadataTopic,
                    Duration.ofMillis(10000));
            if (partitions == null || partitions.isEmpty()) {
                if (consumer != null) {
                    consumer.close();
                }
                return "0";
            }
            TopicPartition topicPartition = new TopicPartition(superstreamMetadataTopic, 0);
            consumer.assign(Collections.singletonList(topicPartition));
            consumer.seekToEnd(Collections.singletonList(topicPartition));
            long endOffset = consumer.position(topicPartition);
            if (endOffset > 0) {
                consumer.seek(topicPartition, endOffset - 1);
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(10000));
                if (!records.isEmpty()) {
                    connectionId = records.iterator().next().value();
                }
            }
        } catch (Exception e) {
            if (e.getMessage().toLowerCase().contains("timeout")) {
                try {
                    Thread.sleep(10000);
                    if (consumer == null) {
                        consumer = new KafkaConsumer<>(consumerProps);
                    }
                    List<PartitionInfo> partitions = consumer.partitionsFor(superstreamMetadataTopic,
                            Duration.ofMillis(10000));
                    if (partitions == null || partitions.isEmpty()) {
                        if (consumer != null) {
                            consumer.close();
                        }
                        return "0";
                    }
                    TopicPartition topicPartition = new TopicPartition(superstreamMetadataTopic, 0);
                    consumer.assign(Collections.singletonList(topicPartition));
                    consumer.seekToEnd(Collections.singletonList(topicPartition));
                    long endOffset = consumer.position(topicPartition);
                    if (endOffset > 0) {
                        consumer.seek(topicPartition, endOffset - 1);
                        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(10000));
                        if (!records.isEmpty()) {
                            connectionId = records.iterator().next().value();
                        }
                    }
                } catch (Exception e2) {
                    handleError(String.format("consumeConnectionID retry: %s", e2.getMessage()));
                }
            }
            if (connectionId == null || connectionId.equals("0")) {
                handleError(String.format("consumeConnectionID: %s", e.getMessage()));
                if (consumer != null) {
                    consumer.close();
                }
                return "0";
            }
        } finally {
            if (consumer != null) {
                consumer.close();
            }
        }
        return connectionId != null ? connectionId : "0";
    }

    private Properties copyAuthConfig() {
        String[] relevantKeys = {
                // Authentication-related keys
                "security.protocol",
                "ssl.truststore.location",
                "ssl.truststore.password",
                "ssl.keystore.location",
                "ssl.keystore.password",
                "ssl.key.password",
                "ssl.endpoint.identification.algorithm",
                "sasl.mechanism",
                "sasl.jaas.config",
                "sasl.kerberos.service.name",
                // Networking-related keys
                "bootstrap.servers",
                "client.dns.lookup",
                "connections.max.idle.ms",
                "request.timeout.ms",
                "metadata.max.age.ms",
                "reconnect.backoff.ms",
                "reconnect.backoff.max.ms"
        };

        Properties relevantProps = new Properties();
        for (String key : relevantKeys) {
            if (configs.containsKey(key)) {
                if (key == ProducerConfig.BOOTSTRAP_SERVERS_CONFIG) {
                    Object value = configs.get(key);
                    if (value instanceof String[]) {
                        relevantProps.put(key, Arrays.toString((String[]) value));
                    } else if (value instanceof ArrayList) {
                        @SuppressWarnings("unchecked")
                        ArrayList<String> arrayList = (ArrayList<String>) value;
                        relevantProps.put(key, String.join(", ", arrayList));
                    } else {
                        relevantProps.put(key, value);
                    }
                } else {
                    relevantProps.put(key, String.valueOf(configs.get(key)));
                }
            }
        }
        return relevantProps;
    }

    public void sendClientTypeUpdateReq() {
        if (type == "" || type == null) {
            return;
        }
        try {
            Map<String, Object> reqData = new HashMap<>();
            reqData.put("client_hash", clientHash);
            reqData.put("type", type);
            ObjectMapper mapper = new ObjectMapper();
            byte[] reqBytes = mapper.writeValueAsBytes(reqData);
            brokerConnection.publish(clientTypeUpdateSubject, reqBytes);
        } catch (Exception e) {
            handleError(String.format("sendClientTypeUpdateReq: %s", e.getMessage()));
        }
    }

    private void executeSendClientConfigUpdateReqWithWait() {
        new Thread(() -> {
            try {
                waitForCanStart();
                sendClientConfigUpdateReq();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Thread was interrupted: " + e.getMessage());
            } catch (RuntimeException e) {
                System.err.println("Error: " + e.getMessage());
            }
        }).start();
    }

    private void waitForCanStart() throws InterruptedException {
        long remainingTime = MAX_TIME_WAIT_CAN_START;
        while (remainingTime > 0) {
            if (!this.canStart) {
                Thread.sleep(WAIT_INTERVAL_CAN_START);
                remainingTime -= WAIT_INTERVAL_CAN_START;
            } else {
                break;
            }

            if (remainingTime <= 0) {
                superstreamPrintStream.println("superstream could not start within the expected timeout period");
            }
        }
    }

    public void waitForSuperstreamConfigs(AbstractConfig config) throws InterruptedException {
        String timeoutEnv = System.getenv(SUPERSTREAM_RESPONSE_TIMEOUT_ENV_VAR);
        long remainingTime = timeoutEnv != null ? Long.parseLong(timeoutEnv) : TIMEOUT_SUPERSTREAM_CONFIG_DEFAULT;
        while (remainingTime > 0) {
            if (this.superstreamConfigs != null) {
                config.getValues().putAll(this.getSuperstreamConfigs());
                break;
            }

            remainingTime -= WAIT_INTERVAL_SUPERSTREAM_CONFIG;
            if (remainingTime > 0) {
                Thread.sleep(WAIT_INTERVAL_SUPERSTREAM_CONFIG);
            } else {
                superstreamPrintStream.println("superstream client configuration was not set within the expected timeout period");
            }
        }
    }

    private void sendClientConfigUpdateReq() {
        if (this.fullClientConfigs != null && !this.fullClientConfigs.isEmpty()) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> reqData = new HashMap<>();
                reqData.put("client_hash", clientHash);
                convertEntryValueWhenNoSerializer(this.fullClientConfigs, mapper);
                reqData.put("config", this.fullClientConfigs);
                byte[] reqBytes = mapper.writeValueAsBytes(reqData);
                brokerConnection.publish(clientConfigUpdateSubject, reqBytes);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            } catch (Exception e) {
                handleError(String.format("sendClientConfigUpdateReq: %s", e.getMessage()));
            }
        }
    }

    private void convertEntryValueWhenNoSerializer(Map<String, Object> config, ObjectMapper mapper) {
        if (config != null && !config.isEmpty()) {
            for (Map.Entry<String, Object> entry : config.entrySet()) {
                Object value = entry.getValue();
                String key = entry.getKey();
                if (key == "sasl.jaas.config"){
                    entry.setValue("[hidden]");
                    continue;
                }
                try {
                    mapper.writeValueAsBytes(value);
                } catch (JsonProcessingException e) {
                    entry.setValue(value.toString());
                }
            }
        }
    }

    public void subscribeToUpdates() {
        try {
            String subject = String.format(superstreamUpdatesSubject, clientHash);
            Dispatcher dispatcher = brokerConnection.createDispatcher(this.updatesHandler());
            updatesSubscription = dispatcher.subscribe(subject, this.updatesHandler());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void reportClientsUpdate() {
        ScheduledExecutorService singleExecutorService = Executors.newSingleThreadScheduledExecutor();
        singleExecutorService.scheduleAtFixedRate(() -> {
            if (brokerConnection != null && superstreamReady) {
                long backupReadBytes = clientCounters.getTotalReadBytesReduced();
                long backupWriteBytes = clientCounters.getTotalWriteBytesReduced();
                Double producerCompressionRate = clientCounters.getProducerCompressionRate();
                long calculatedWriteBytes = Math.round(backupWriteBytes * producerCompressionRate);
                Double consumerCompressionRate = clientCounters.getConsumerCompressionRate();
                long calculatedReadBytes = Math.round(backupReadBytes * consumerCompressionRate);
                clientCounters.reset();
                try {
                    Map<String, Object> countersMap = new HashMap<>();
                    countersMap.put("total_read_bytes_reduced", calculatedReadBytes);
                    countersMap.put("total_write_bytes_reduced", calculatedWriteBytes);
                    countersMap.put("connection_id", kafkaConnectionID);
                    byte[] byteCounters = objectMapper.writeValueAsBytes(countersMap);
                    brokerConnection.publish(
                            String.format(superstreamClientsUpdateSubject, "counters", clientHash),
                            byteCounters);
                } catch (Exception e) {
                    clientCounters.incrementTotalReadBytesReduced(backupReadBytes);
                    clientCounters.incrementTotalWriteBytesReduced(backupWriteBytes);
                    handleError("reportClientsUpdate config: " + e.getMessage());
                }
                try {
                    Map<String, Object> topicPartitionConfig = new HashMap<>();
                    if (!topicPartitions.isEmpty()) {
                        Map<String, Integer[]> topicPartitionsToSend = convertMap(topicPartitions);
                        switch (this.type) {
                            case "producer":
                                topicPartitionConfig.put("producer_topics_partitions", topicPartitionsToSend);
                                topicPartitionConfig.put("consumer_group_topics_partitions",
                                        new HashMap<String, Integer[]>());
                                break;
                            case "consumer":
                                topicPartitionConfig.put("producer_topics_partitions",
                                        new HashMap<String, Integer[]>());
                                topicPartitionConfig.put("consumer_group_topics_partitions", topicPartitionsToSend);
                                break;
                        }
                    }
                    topicPartitionConfig.put("connection_id", kafkaConnectionID);
                    byte[] byteConfig = objectMapper.writeValueAsBytes(topicPartitionConfig);

                    brokerConnection.publish(
                            String.format(superstreamClientsUpdateSubject, "config", clientHash),
                            byteConfig);

                } catch (Exception e) {
                    handleError("reportClientsUpdate config: " + e.getMessage());
                }
            }
        }, 0, 10, TimeUnit.MINUTES);
    }

    public static Map<String, Integer[]> convertMap(Map<String, Set<Integer>> topicPartitions) {
        Map<String, Integer[]> result = new HashMap<>();
        for (Map.Entry<String, Set<Integer>> entry : topicPartitions.entrySet()) {
            Integer[] array = entry.getValue().toArray(new Integer[0]);
            result.put(entry.getKey(), array);
        }
        return result;
    }

    public void sendLearningMessage(byte[] msg) {
        try {
            brokerConnection.publish(String.format(superstreamLearningSubject, clientHash), msg);
        } catch (Exception e) {
            handleError("sendLearningMessage: " + e.getMessage());
        }
    }

    public void sendRegisterSchemaReq() {
        try {
            brokerConnection.publish(String.format(superstreamRegisterSchemaSubject, clientHash), new byte[0]);
            learningRequestSent = true;
        } catch (Exception e) {
            handleError("sendLearningMessage: " + e.getMessage());
        }
    }

    public JsonToProtoResult jsonToProto(byte[] msgBytes) throws Exception {
        try {
            String jsonString = new String(msgBytes);
            if (!isJsonObject(jsonString)) {
                jsonString = convertEscapedJsonString(jsonString);
            }
            if (jsonString == null || jsonString.isEmpty()) {
                return new JsonToProtoResult(false, msgBytes);
            }
            if (jsonString != null && jsonString.length() > 2 && jsonString.startsWith("\"{")
                    && jsonString.endsWith("}\"")) {
                jsonString = jsonString.substring(1, jsonString.length() - 1);
            }
            DynamicMessage.Builder newMessageBuilder = DynamicMessage.newBuilder(descriptor);
            JsonFormat.parser().merge(jsonString, newMessageBuilder);
            DynamicMessage message = newMessageBuilder.build();
            return new JsonToProtoResult(true, message.toByteArray());
        } catch (Exception e) {
            return new JsonToProtoResult(false, msgBytes);
        }
    }

    public class JsonToProtoResult {
        private final boolean success;
        private final byte[] messageBytes;

        public JsonToProtoResult(boolean success, byte[] messageBytes) {
            this.success = success;
            this.messageBytes = messageBytes;
        }

        public boolean isSuccess() {
            return success;
        }

        public byte[] getMessageBytes() {
            return messageBytes;
        }
    }

    private boolean isJsonObject(String jsonString) {
        try {
            JsonParser.parseString(jsonString).getAsJsonObject();
            return true;
        } catch (JsonSyntaxException | IllegalStateException e) {
            return false;
        }
    }

    private static String convertEscapedJsonString(String escapedJsonString) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonNode = mapper.readTree(escapedJsonString);
        return mapper.writeValueAsString(jsonNode).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    public byte[] protoToJson(byte[] msgBytes, Descriptors.Descriptor desc) throws Exception {
        try {
            DynamicMessage message = DynamicMessage.parseFrom(desc, msgBytes);
            String jsonString = JsonFormat.printer().omittingInsignificantWhitespace().print(message);
            return jsonString.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            if (e.getMessage().contains("the input ended unexpectedly")) {
                return msgBytes;
            } else {
                throw e;
            }
        }
    }

    private MessageHandler updatesHandler() {
        return (msg) -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> update = objectMapper.readValue(msg.getData(), Map.class);
                processUpdate(update);
            } catch (IOException e) {
                handleError("updatesHandler at json.Unmarshal: " + e.getMessage());
            }
        };
    }

    private void processUpdate(Map<String, Object> update) {
        String type = (String) update.get("type");
        try {
            String payloadBytesString = (String) update.get("payload");
            byte[] payloadBytes = Base64.getDecoder().decode(payloadBytesString);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(payloadBytes, Map.class);
            Map<String, String> envVars = System.getenv();
            switch (type) {
                case "LearnedSchema":
                    String descriptorBytesString = (String) payload.get("desc");
                    String masterMsgName = (String) payload.get("master_msg_name");
                    String fileName = (String) payload.get("file_name");
                    descriptor = compileMsgDescriptor(descriptorBytesString, masterMsgName, fileName);
                    String schemaID = (String) payload.get("schema_id");
                    ProducerSchemaID = schemaID;
                    break;

                case "ToggleReduction":
                    // if defined as false in env vars - override the value from superstream
                    String reductionEnabledString = envVars.get(SUPERSTREAM_REDUCTION_ENABLED_ENV_VAR);
                    if (reductionEnabledString != null) {
                        Boolean reductionEnabled = Boolean.parseBoolean(reductionEnabledString);
                        if (!reductionEnabled) {
                            this.reductionEnabled = false;
                            break;
                        }
                    }
                    Boolean enableReduction = (Boolean) payload.get("enable_reduction");
                    if (enableReduction) {
                        this.reductionEnabled = true;
                    } else {
                        this.reductionEnabled = false;
                    }
                    break;

                case "CompressionUpdate":
                    Boolean enableCompression = (Boolean) payload.get("enable_compression");
                    if (enableCompression) {
                        this.compressionTurnedOffBySuperstream = false;
                    } else {
                        this.compressionTurnedOffBySuperstream = true;
                    }
                    this.compressionEnabled = enableCompression;
                    String compType = (String) payload.get("compression_type");
                    if (compType != null) {
                        this.compressionType = compType;
                    }
                    break;
            }
        } catch (Exception e) {
            handleError(("processUpdate: " + e.getMessage()));
        }
    }

    public void sendGetSchemaRequest(String schemaID) {
        try {
            Map<String, Object> reqData = new HashMap<>();
            reqData.put("schema_id", schemaID);
            ObjectMapper mapper = new ObjectMapper();
            byte[] reqBytes = mapper.writeValueAsBytes(reqData);
            Message msg = brokerConnection.request(String.format(superstreamGetSchemaSubject, clientHash),
                    reqBytes, Duration.ofSeconds(5));
            if (msg == null) {
                throw new Exception("Could not get descriptor");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> respMap = objectMapper.readValue(new String(msg.getData(), StandardCharsets.UTF_8),
                    Map.class);
            if (respMap.containsKey("desc") && respMap.get("desc") instanceof String) {
                String descriptorBytesString = (String) respMap.get("desc");
                String masterMsgName = (String) respMap.get("master_msg_name");
                String fileName = (String) respMap.get("file_name");
                Descriptors.Descriptor respDescriptor = compileMsgDescriptor(descriptorBytesString, masterMsgName,
                        fileName);
                if (respDescriptor != null) {
                    SchemaIDMap.put((String) respMap.get("schema_id"), respDescriptor);
                } else {
                    throw new Exception("Error compiling schema.");
                }
            } else {
                throw new Exception("Response map does not contain expected keys.");
            }
        } catch (Exception e) {
            handleError(String.format("sendGetSchemaRequest: %s", e.getMessage()));
        }
    }

    private Descriptors.Descriptor compileMsgDescriptor(String descriptorBytesString, String masterMsgName,
                                                        String fileName) {
        try {
            byte[] descriptorAsBytes = Base64.getDecoder().decode(descriptorBytesString);
            if (descriptorAsBytes == null) {
                throw new Exception("error decoding descriptor bytes");
            }
            FileDescriptorSet descriptorSet = FileDescriptorSet.parseFrom(descriptorAsBytes);
            FileDescriptor fileDescriptor = null;
            for (DescriptorProtos.FileDescriptorProto fdp : descriptorSet.getFileList()) {
                if (fdp.getName().equals(fileName)) {
                    fileDescriptor = FileDescriptor.buildFrom(fdp, new FileDescriptor[]{});
                    break;
                }
            }

            if (fileDescriptor == null) {
                throw new Exception("file not found");
            }

            for (Descriptors.Descriptor md : fileDescriptor.getMessageTypes()) {
                if (md.getName().equals(masterMsgName)) {
                    return md;
                }
            }
        } catch (Exception e) {
            handleError(String.format("compileMsgDescriptor: %s", e.getMessage()));
        }
        return null;
    }

    public void handleError(String msg) {

        if (brokerConnection != null && superstreamReady) {
            Map<String, String> envVars = System.getenv();
            String tags = envVars.get(SUPERSTREAM_TAGS_ENV_VAR);
            if (tags == null) {
                tags = "";
            }
            if (clientHash == "") {
                String message = String.format("[sdk: java][version: %s][tags: %s] %s", sdkVersion, tags, msg);
                brokerConnection.publish(superstreamErrorSubject, message.getBytes(StandardCharsets.UTF_8));
            } else {
                String message = String.format("[clientHash: %s][sdk: java][version: %s][tags: %s] %s",
                        clientHash, sdkVersion, tags, msg);
                brokerConnection.publish(superstreamErrorSubject, message.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    public static Map<String, Object> initSuperstreamConfig(Map<String, Object> configs, String type) {
        String isInnerConsumer = (String) configs.get(superstreamInnerConsumerKey);
        if (Boolean.parseBoolean(isInnerConsumer)) {
            return configs;
        }
        String interceptorToAdd = getSuperstreamClientInterceptorName(type);
        try {
            List<String> interceptors = null;
            Object existingInterceptors = configs.get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG);
            if (!interceptorToAdd.isEmpty()) {
                if (existingInterceptors != null) {
                    if (existingInterceptors instanceof List) {
                        interceptors = new ArrayList<>((List<String>) existingInterceptors);
                    } else if (existingInterceptors instanceof String) {
                        interceptors = new ArrayList<>();
                        interceptors.add((String) existingInterceptors);
                    } else {
                        interceptors = new ArrayList<>();
                    }
                } else {
                    interceptors = new ArrayList<>();
                }
            }
            if (!interceptorToAdd.isEmpty()) {
                interceptors.add(interceptorToAdd);
                configs.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, interceptors);
            }

            Map<String, String> envVars = System.getenv();
            String superstreamHost = envVars.get(SUPERSTREAM_HOST_ENV_VAR);
            if (superstreamHost == null) {
                throw new Exception("host is required");
            }
            configs.put(superstreamHostKey, superstreamHost);
            String token = envVars.get(SUPERSTREAM_TOKEN_ENV_VAR);
            if (token == null) {
                token = superstreamDefaultToken;
            }
            configs.put(superstreamTokenKey, token);
            String learningFactorString = envVars.get(SUPERSTREAM_LEARNING_FACTOR_ENV_VAR);
            Integer learningFactor = superstreamDefaultLearningFactor;
            if (learningFactorString != null) {
                learningFactor = Integer.parseInt(learningFactorString);
            }
            configs.put(superstreamLearningFactorKey, learningFactor);
            boolean reductionEnabled = false;
            String reductionEnabledString = envVars.get(SUPERSTREAM_REDUCTION_ENABLED_ENV_VAR);
            if (reductionEnabledString != null) {
                reductionEnabled = Boolean.parseBoolean(reductionEnabledString);
            }
            configs.put(superstreamReductionEnabledKey, reductionEnabled);
            String tags = envVars.get(SUPERSTREAM_TAGS_ENV_VAR);
            if (tags == null) {
                tags = "";
            }
            boolean compressionEnabled = false;
            checkStdoutEnvVar();
            Superstream superstreamConnection = new Superstream(token, superstreamHost, learningFactor, configs,
                    reductionEnabled, type, tags, compressionEnabled);

            superstreamConnection.init();
            configs.put(superstreamConnectionKey, superstreamConnection);
        } catch (Exception e) {
            String errMsg = String.format("superstream: error initializing superstream: %s", e.getMessage());
            System.out.println(errMsg);
            handleConfigsWhenErrorInitializeSuperstream(type, configs);
        }

        return configs;
    }

    private static void handleConfigsWhenErrorInitializeSuperstream(String type, Map<String, Object> configs) {
        switch (type) {
            case PRODUCER:
                if (configs.containsKey(originalSerializer)) {
                    configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                            configs.get(originalSerializer));
                    configs.remove(originalSerializer);
                }
                break;
            case CONSUMER:
                if (configs.containsKey(originalDeserializer)) {
                    configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                            configs.get(originalDeserializer));
                    configs.remove(originalDeserializer);
                }
                break;
        }
    }

    private static String getSuperstreamClientInterceptorName(String type) {
        switch (type) {
            case "producer":
                handleSerializerLogicForPayloadReduction();
                return SuperstreamProducerInterceptor.class.getName();
            case "consumer":
                handleDeserializerLogicForPayloadReduction();
                return SuperstreamConsumerInterceptor.class.getName();
            default:
                return "";
        }
    }

    private static void handleDeserializerLogicForPayloadReduction() {
        // : handle deserializer logic for payload reduction
        // igs.containsKey(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)) {
        // if (!configs.containsKey(Consts.originalDeserializer)) {
        // igs.put(Consts.originalDeserializer,
        //
        // put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
        // SuperstreamDeserializer.class.getName());
        //
        //
    }

    private static void handleSerializerLogicForPayloadReduction() {
//             : handle serializer logic for payload reduction
//             igs.containsKey(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)) {
//             if (!configs.containsKey(Consts.originalSerializer)) {
//             igs.put(Consts.originalSerializer,
//
//             put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
//             SuperstreamSerializer.class.getName());
//
    }

    public static Properties initSuperstreamProps(Properties properties, String type) {
        String interceptors = (String) properties.get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG);
        switch (type) {
            case "producer":
                if (interceptors != null && !interceptors.isEmpty()) {
                    interceptors += "," + SuperstreamProducerInterceptor.class.getName();
                } else {
                    interceptors = SuperstreamProducerInterceptor.class.getName();
                }
                if (properties.containsKey(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)) {
                    if (!properties.containsKey(originalSerializer)) {
                        properties.put(originalSerializer,
                                properties.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
                        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                                SuperstreamSerializer.class.getName());
                    }
                }
                break;
            case "consumer":
                if (interceptors != null && !interceptors.isEmpty()) {
                    interceptors += "," + SuperstreamConsumerInterceptor.class.getName();
                } else {
                    interceptors = SuperstreamConsumerInterceptor.class.getName();
                }
                if (properties.containsKey(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)) {
                    if (!properties.containsKey(originalDeserializer)) {
                        properties.put(originalDeserializer,
                                properties.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
                        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                                SuperstreamDeserializer.class.getName());
                    }
                }
                break;
        }
        if (interceptors != null) {
            properties.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, interceptors);
        }

        try {
            Map<String, String> envVars = System.getenv();
            String superstreamHost = envVars.get(SUPERSTREAM_HOST_ENV_VAR);
            if (superstreamHost == null) {
                throw new Exception("host is required");
            }
            properties.put(superstreamHostKey, superstreamHost);
            String token = envVars.get(SUPERSTREAM_TOKEN_ENV_VAR);
            if (token == null) {
                token = superstreamDefaultToken;
            }
            properties.put(superstreamTokenKey, token);
            String learningFactorString = envVars.get(SUPERSTREAM_LEARNING_FACTOR_ENV_VAR);
            Integer learningFactor = superstreamDefaultLearningFactor;
            if (learningFactorString != null) {
                learningFactor = Integer.parseInt(learningFactorString);
            }
            properties.put(superstreamLearningFactorKey, learningFactor);
            Boolean reductionEnabled = false;
            String reductionEnabledString = envVars.get(SUPERSTREAM_REDUCTION_ENABLED_ENV_VAR);
            if (reductionEnabledString != null) {
                reductionEnabled = Boolean.parseBoolean(reductionEnabledString);
            }
            properties.put(superstreamReductionEnabledKey, reductionEnabled);
            String tags = envVars.get(SUPERSTREAM_TAGS_ENV_VAR);
            if (tags != null) {
                properties.put(superstreamTagsKey, tags);
            }
            Map<String, Object> configs = propertiesToMap(properties);
            Superstream superstreamConnection = new Superstream(token, superstreamHost, learningFactor, configs,
                    reductionEnabled, type);
            superstreamConnection.init();
            properties.put(superstreamConnectionKey, superstreamConnection);
        } catch (Exception e) {
            String errMsg = String.format("superstream: error initializing superstream: %s", e.getMessage());
            superstreamPrintStream.println(errMsg);
        }
        return properties;
    }

    public static Map<String, Object> propertiesToMap(Properties properties) {
        return properties.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> String.valueOf(e.getKey()),
                        e -> e.getValue()));
    }

    public void updateTopicPartitions(String topic, Integer partition) {
        Set<Integer> partitions = topicPartitions.computeIfAbsent(topic, k -> new HashSet<>());
        partitions.add(partition);
    }

    public void setFullClientConfigs(Map<String, ?> configsUpdate) {
        for (Map.Entry<String, ?> entry : configsUpdate.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Object copiedValue;
            if (this.configs.containsKey(key)) {
                copiedValue = deepCopyObject(this.configs.get(key));
            } else {
                copiedValue = deepCopyObject(value);
            }
            this.fullClientConfigs.put(key, copiedValue);
        }
        executeSendClientConfigUpdateReqWithWait();
    }

    public PrintStream getSuperstreamPrintStream() {
        return superstreamPrintStream;
    }

    public Map<String, ?> getSuperstreamConfigs() {
        return superstreamConfigs;
    }

    private static class ClassOutputStream extends OutputStream {
        @Override
        public void write(int b) {
            if (!isStdoutSuppressed) {
                originalOut.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (!isStdoutSuppressed) {
                originalOut.write(b, off, len);
            }
        }
    }

    private static class ClassErrorStream extends OutputStream {
        @Override
        public void write(int b) {
            if (!isStderrSuppressed) {
                originalErr.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (!isStderrSuppressed) {
                originalErr.write(b, off, len);
            }
        }
    }

}
