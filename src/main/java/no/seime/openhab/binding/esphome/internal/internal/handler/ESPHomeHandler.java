/**
 * Copyright (c) 2023 Contributors to the Seime Openhab Addons project
 * <p>
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 * <p>
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * <p>
 * SPDX-License-Identifier: EPL-2.0
 */
package no.seime.openhab.binding.esphome.internal.internal.handler;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.thing.type.ChannelTypeProvider;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.GeneratedMessageV3;

import io.esphome.api.BinarySensorStateResponse;
import io.esphome.api.ClimateStateResponse;
import io.esphome.api.ConnectRequest;
import io.esphome.api.ConnectResponse;
import io.esphome.api.DeviceInfoRequest;
import io.esphome.api.DeviceInfoResponse;
import io.esphome.api.DisconnectRequest;
import io.esphome.api.DisconnectResponse;
import io.esphome.api.HelloRequest;
import io.esphome.api.HelloResponse;
import io.esphome.api.ListEntitiesBinarySensorResponse;
import io.esphome.api.ListEntitiesClimateResponse;
import io.esphome.api.ListEntitiesDoneResponse;
import io.esphome.api.ListEntitiesRequest;
import io.esphome.api.ListEntitiesSelectResponse;
import io.esphome.api.ListEntitiesSensorResponse;
import io.esphome.api.ListEntitiesSwitchResponse;
import io.esphome.api.ListEntitiesTextSensorResponse;
import io.esphome.api.PingRequest;
import io.esphome.api.PingResponse;
import io.esphome.api.SelectStateResponse;
import io.esphome.api.SensorStateResponse;
import io.esphome.api.SubscribeStatesRequest;
import io.esphome.api.SwitchStateResponse;
import io.esphome.api.TextSensorStateResponse;
import no.seime.openhab.binding.esphome.internal.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.internal.ESPHomeConfiguration;
import no.seime.openhab.binding.esphome.internal.internal.PacketListener;
import no.seime.openhab.binding.esphome.internal.internal.comm.ConnectionSelector;
import no.seime.openhab.binding.esphome.internal.internal.comm.ESPHomeConnection;
import no.seime.openhab.binding.esphome.internal.internal.comm.PlainTextStreamHandler;
import no.seime.openhab.binding.esphome.internal.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.internal.comm.ProtocolException;
import no.seime.openhab.binding.esphome.internal.internal.message.AbstractMessageHandler;
import no.seime.openhab.binding.esphome.internal.internal.message.BinarySensorMessageHandler;
import no.seime.openhab.binding.esphome.internal.internal.message.ClimateMessageHandler;
import no.seime.openhab.binding.esphome.internal.internal.message.SelectMessageHandler;
import no.seime.openhab.binding.esphome.internal.internal.message.SensorMessageHandler;
import no.seime.openhab.binding.esphome.internal.internal.message.SwitchMessageHandler;
import no.seime.openhab.binding.esphome.internal.internal.message.TextSensorMessageHandler;

/**
 * The {@link ESPHomeHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Arne Seime - Initial contribution
 */
@NonNullByDefault
public class ESPHomeHandler extends BaseThingHandler implements PacketListener, ChannelTypeProvider {

    public static final int NUM_MISSED_PINGS_BEFORE_DISCONNECT = 4;
    public static final int CONNECT_TIMEOUT = 20;
    private static final long PING_INTERVAL_SECONDS = 10;
    private static final int API_VERSION_MAJOR = 1;
    private static final int API_VERSION_MINOR = 7;

    private final Logger logger = LoggerFactory.getLogger(ESPHomeHandler.class);
    private final Map<ChannelTypeUID, ChannelType> generatedChannelTypes = new HashMap<>();
    private final ConnectionSelector connectionSelector;
    private @Nullable ESPHomeConfiguration config;
    private @Nullable ESPHomeConnection connection;
    @Nullable
    private ScheduledFuture<?> pingWatchdog;
    private Instant lastPong = Instant.now();
    @Nullable
    private ScheduledFuture<?> reconnectFuture;
    private final Map<String, AbstractMessageHandler<? extends GeneratedMessageV3, ? extends GeneratedMessageV3>> commandTypeToHandlerMap = new HashMap<>();
    private final Map<Class<? extends GeneratedMessageV3>, AbstractMessageHandler<? extends GeneratedMessageV3, ? extends GeneratedMessageV3>> classToHandlerMap = new HashMap<>();
    private ConnectionState connectionState = ConnectionState.UNINITIALIZED;
    private final List<Channel> dynamicChannels = new ArrayList<>();

    public ESPHomeHandler(Thing thing, ConnectionSelector connectionSelector) {
        super(thing);
        this.connectionSelector = connectionSelector;

        // Register message handlers for each type of message pairs
        registerMessageHandler("Select", new SelectMessageHandler(this), ListEntitiesSelectResponse.class,
                SelectStateResponse.class);
        registerMessageHandler("Sensor", new SensorMessageHandler(this), ListEntitiesSensorResponse.class,
                SensorStateResponse.class);
        registerMessageHandler("BinarySensor", new BinarySensorMessageHandler(this),
                ListEntitiesBinarySensorResponse.class, BinarySensorStateResponse.class);
        registerMessageHandler("TextSensor", new TextSensorMessageHandler(this), ListEntitiesTextSensorResponse.class,
                TextSensorStateResponse.class);
        registerMessageHandler("Switch", new SwitchMessageHandler(this), ListEntitiesSwitchResponse.class,
                SwitchStateResponse.class);
        registerMessageHandler("Climate", new ClimateMessageHandler(this), ListEntitiesClimateResponse.class,
                ClimateStateResponse.class);
    }

    private void registerMessageHandler(String select,
            AbstractMessageHandler<? extends GeneratedMessageV3, ? extends GeneratedMessageV3> messageHandler,
            Class<? extends GeneratedMessageV3> listEntitiesClass, Class<? extends GeneratedMessageV3> stateClass) {

        commandTypeToHandlerMap.put(select, messageHandler);
        classToHandlerMap.put(listEntitiesClass, messageHandler);
        classToHandlerMap.put(stateClass, messageHandler);
    }

    @Override
    public void initialize() {
        logger.debug("[{}] Initializing ESPHome handler", thing.getUID());
        config = getConfigAs(ESPHomeConfiguration.class);

        if (config.hostname != null && !config.hostname.isEmpty()) {
            scheduler.submit(this::connect);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No hostname configured");
        }
    }

    private void connect() {
        try {
            dynamicChannels.clear();
            generatedChannelTypes.clear();

            logger.info("[{}] Trying to connect to {}:{}", config.hostname, config.hostname, config.port);
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE,
                    String.format("Connecting to %s:%d", config.hostname, config.port));

            connection = new ESPHomeConnection(connectionSelector, new PlainTextStreamHandler(this));
            connection.connect(new InetSocketAddress(config.hostname, config.port));

            HelloRequest helloRequest = HelloRequest.newBuilder().setClientInfo("openHAB")
                    .setApiVersionMajor(API_VERSION_MAJOR).setApiVersionMinor(API_VERSION_MINOR).build();
            connectionState = ConnectionState.HELLO_SENT;
            connection.send(helloRequest);

        } catch (ProtocolException e) {
            logger.warn("[{}] Error initial connection", config.hostname, e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            reconnectFuture = scheduler.schedule(this::connect, CONNECT_TIMEOUT * 2L, TimeUnit.SECONDS);
        }
    }

    @Override
    public void dispose() {
        if (reconnectFuture != null) {
            reconnectFuture.cancel(true);
        }
        if (connection != null) {
            if (pingWatchdog != null) {
                pingWatchdog.cancel(true);
            }

            if (connectionState == ConnectionState.CONNECTED) {
                try {
                    connection.send(DisconnectRequest.getDefaultInstance());
                } catch (ProtocolAPIError e) {
                    // Quietly ignore
                }
            } else {
                connection.close();
            }
        }
        super.dispose();
    }

    public void sendMessage(GeneratedMessageV3 message) throws ProtocolAPIError {
        connection.send(message);
    }

    @Override
    public synchronized void handleCommand(ChannelUID channelUID, Command command) {

        if (connectionState != ConnectionState.CONNECTED) {
            logger.warn("[{}] Not connected, ignoring command {}", config.hostname, command);
            return;
        }

        if (command == RefreshType.REFRESH) {
            try {
                connection.send(SubscribeStatesRequest.getDefaultInstance());
            } catch (ProtocolAPIError e) {
                logger.error("[{}] Error sending command {} to channel {}: {}", config.hostname, command, channelUID,
                        e.getMessage());
            }
            return;
        }

        Optional<Channel> optionalChannel = thing.getChannels().stream().filter(e -> e.getUID().equals(channelUID))
                .findFirst();
        optionalChannel.ifPresent(channel -> {
            try {
                String commandClass = (String) channel.getConfiguration().get(BindingConstants.COMMAND_CLASS);
                if (commandClass == null) {
                    logger.warn("[{}] No command class for channel {}", config.hostname, channelUID);
                    return;
                }

                AbstractMessageHandler<? extends GeneratedMessageV3, ? extends GeneratedMessageV3> abstractMessageHandler = commandTypeToHandlerMap
                        .get(commandClass);
                if (abstractMessageHandler == null) {
                    logger.warn("[{}] No message handler for command class {}", config.hostname, commandClass);
                } else {
                    int key = ((BigDecimal) channel.getConfiguration().get(BindingConstants.COMMAND_KEY)).intValue();
                    abstractMessageHandler.handleCommand(channel, command, key);
                }

            } catch (Exception e) {
                logger.error("[{}] Error sending command {} to channel {}: {}", config.hostname, command, channelUID,
                        e.getMessage(), e);
            }
        });
    }

    @Override
    public void onPacket(@NonNull GeneratedMessageV3 message) throws ProtocolAPIError {
        switch (connectionState) {
            case UNINITIALIZED -> logger.warn("[{}] Received packet while uninitialized.", config.hostname);
            case HELLO_SENT -> handleHelloResponse(message);
            case LOGIN_SENT -> handleLoginResponse(message);
            case CONNECTED -> handleConnected(message);
        }
    }

    @Override
    public void onEndOfStream() {
        updateStatus(ThingStatus.OFFLINE);
        connection.close();
        pingWatchdog.cancel(true);
        connectionState = ConnectionState.UNINITIALIZED;
        reconnectFuture = scheduler.schedule(this::connect, CONNECT_TIMEOUT * 2L, TimeUnit.SECONDS);
    }

    @Override
    public void onParseError() {
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                "Parse error. This could be due to api encryption being used by ESPHome device. Update your ESPHome device to use plaintext password until this is implemented in the binding.");
        connection.close();
        pingWatchdog.cancel(true);
        connectionState = ConnectionState.UNINITIALIZED;
        reconnectFuture = scheduler.schedule(this::connect, CONNECT_TIMEOUT * 2L, TimeUnit.SECONDS);
    }

    private void handleConnected(GeneratedMessageV3 message) throws ProtocolAPIError {
        logger.debug("[{}] Received message {}", config.hostname, message);
        if (message instanceof DeviceInfoResponse rsp) {
            Map<String, String> props = new HashMap<>();
            props.put("esphome_version", rsp.getEsphomeVersion());
            props.put("mac_address", rsp.getMacAddress());
            props.put("model", rsp.getModel());
            props.put("name", rsp.getName());
            props.put("manufacturer", rsp.getManufacturer());
            props.put("compilation_time", rsp.getCompilationTime());
            updateThing(editThing().withProperties(props).build());
        } else if (message instanceof ListEntitiesDoneResponse) {
            updateThing(editThing().withChannels(dynamicChannels).build());
            logger.debug("[{}] Done updating channels", config.hostname);
            connection.send(SubscribeStatesRequest.getDefaultInstance());
        } else if (message instanceof PingRequest) {
            logger.debug("[{}] Responding to ping request", config.hostname);
            connection.send(PingResponse.getDefaultInstance());

        } else if (message instanceof PingResponse) {
            logger.debug("[{}] Received ping response", config.hostname);
            lastPong = Instant.now();
        } else if (message instanceof DisconnectRequest) {
            connection.send(DisconnectResponse.getDefaultInstance());
            remoteDisconnect();
        } else if (message instanceof DisconnectResponse) {
            connection.close();
        } else {
            // Regular messages handled by message handlers
            AbstractMessageHandler<? extends GeneratedMessageV3, ? extends GeneratedMessageV3> abstractMessageHandler = classToHandlerMap
                    .get(message.getClass());
            if (abstractMessageHandler != null) {
                abstractMessageHandler.handleMessage(message);
            } else {
                logger.warn("[{}] Unhandled message of type {}. This is lack of support in the binding. Content: '{}'.",
                        config.hostname, message.getClass().getName(), message);
            }
        }
    }

    private void remoteDisconnect() {
        connection.close();
        connectionState = ConnectionState.UNINITIALIZED;
        long reconnectDelay = CONNECT_TIMEOUT;
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE,
                String.format("ESPHome device requested disconnect. Will reconnect in %d seconds", reconnectDelay));
        if (pingWatchdog != null) {
            pingWatchdog.cancel(true);
        }
        reconnectFuture = scheduler.schedule(this::connect, reconnectDelay, TimeUnit.SECONDS);
    }

    private void handleLoginResponse(GeneratedMessageV3 message) throws ProtocolAPIError {
        if (message instanceof ConnectResponse connectResponse) {
            logger.debug("[{}] Received login response {}", config.hostname, connectResponse);

            if (connectResponse.getInvalidPassword()) {
                logger.error("[{}] Invalid password", config.hostname);
                connection.close();
                connectionState = ConnectionState.UNINITIALIZED;
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Invalid password");
                return;
            }
            connectionState = ConnectionState.CONNECTED;
            updateStatus(ThingStatus.ONLINE);

            // Reset last pong
            lastPong = Instant.now();

            pingWatchdog = scheduler.scheduleAtFixedRate(() -> {

                if (lastPong.plusSeconds(NUM_MISSED_PINGS_BEFORE_DISCONNECT * PING_INTERVAL_SECONDS)
                        .isBefore(Instant.now())) {
                    logger.warn(
                            "[{}] Ping responses lacking Waited {} times {} seconds, total of {}. Assuming connection lost and disconnecting",
                            config.hostname, NUM_MISSED_PINGS_BEFORE_DISCONNECT, PING_INTERVAL_SECONDS,
                            NUM_MISSED_PINGS_BEFORE_DISCONNECT * PING_INTERVAL_SECONDS);
                    pingWatchdog.cancel(false);
                    connection.close();
                    connectionState = ConnectionState.UNINITIALIZED;
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            String.format("ESPHome did not respond to ping requests. %d pings sent with %d s delay",
                                    NUM_MISSED_PINGS_BEFORE_DISCONNECT, PING_INTERVAL_SECONDS));
                    reconnectFuture = scheduler.schedule(this::connect, 10, TimeUnit.SECONDS);

                } else {

                    try {
                        logger.debug("[{}] Sending ping", config.hostname);
                        connection.send(PingRequest.getDefaultInstance());
                    } catch (ProtocolAPIError e) {
                        logger.warn("[{}] Error sending ping request", config.hostname, e);
                    }
                }
            }, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);

            connection.send(DeviceInfoRequest.getDefaultInstance());
            connection.send(ListEntitiesRequest.getDefaultInstance());

        }
    }

    @Override
    public void updateState(ChannelUID channelUID, State state) {
        super.updateState(channelUID, state);
    }

    private void handleHelloResponse(GeneratedMessageV3 message) throws ProtocolAPIError {
        if (message instanceof HelloResponse helloResponse) {
            logger.debug("[{}] Received hello response {}", config.hostname, helloResponse);
            logger.info("[{}] Server {} running {} on protocol version {}.{}", config.hostname, helloResponse.getName(),
                    helloResponse.getServerInfo(), helloResponse.getApiVersionMajor(),
                    helloResponse.getApiVersionMinor());
            connectionState = ConnectionState.LOGIN_SENT;

            if (config.password != null) {
                connection.send(ConnectRequest.newBuilder().setPassword(config.password).build());
            } else {
                connection.send(ConnectRequest.getDefaultInstance());

            }

        }

        // Check if
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Collections.singleton(CallbackChannelsTypeProvider.class);
    }

    public void addChannelType(ChannelType channelType) {
        generatedChannelTypes.put(channelType.getUID(), channelType);
    }

    @Override
    public Collection<ChannelType> getChannelTypes(@Nullable final Locale locale) {
        return generatedChannelTypes.values();
    }

    @Override
    public @Nullable ChannelType getChannelType(final ChannelTypeUID channelTypeUID, @Nullable final Locale locale) {
        return generatedChannelTypes.get(channelTypeUID);
    }

    public void addChannel(Channel channel) {
        dynamicChannels.add(channel);
    }

    private enum ConnectionState {
        // Initial state, no connection
        UNINITIALIZED,
        // TCP connected to ESPHome, first handshake sent
        HELLO_SENT,

        // First handshake received, login sent (with password)
        LOGIN_SENT,

        // Connection established
        CONNECTED

    }
}
