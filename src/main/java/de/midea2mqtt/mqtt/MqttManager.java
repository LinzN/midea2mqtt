/*
 * Copyright (c) 2026 MirraNET, Niklas Linz. All rights reserved.
 *
 * This file is part of the MirraNET project and is licensed under the
 * GNU Lesser General Public License v3.0 (LGPLv3).
 *
 * You may use, distribute and modify this code under the terms
 * of the LGPLv3 license. You should have received a copy of the
 * license along with this file. If not, see <https://www.gnu.org/licenses/lgpl-3.0.html>
 * or contact: niklas.linz@mirranet.de
 */

package de.midea2mqtt.mqtt;

import de.midea2mqtt.Configuration;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connection to the local MQTT broker.
 *
 * <p>Topics, with {@code <base>} from {@code mqtt.baseTopic}:
 * <pre>
 * &lt;base&gt;/bridge/status              online / offline (retained, last will)
 * &lt;base&gt;/&lt;device&gt;/availability     online / offline (retained)
 * &lt;base&gt;/&lt;device&gt;/state            full state as JSON
 * &lt;base&gt;/&lt;device&gt;/state/&lt;key&gt;      single values (only with mqtt.publishAttributes)
 * &lt;base&gt;/&lt;device&gt;/capabilities     what the unit supports, JSON
 * &lt;base&gt;/&lt;device&gt;/error            why a command failed, JSON (not retained)
 * &lt;base&gt;/&lt;device&gt;/set              command as JSON object      (subscribed)
 * &lt;base&gt;/&lt;device&gt;/set/&lt;key&gt;        single value as plain text   (subscribed)
 * &lt;base&gt;/&lt;device&gt;/get              any payload: refresh now     (subscribed)
 * </pre>
 */
public class MqttManager implements MqttCallbackExtended {

    private static final Logger LOG = Logger.getLogger(MqttManager.class.getName());
    private static final long RETRY_MILLIS = 10_000;

    private final Configuration config;
    private final MqttClient client;
    private final MqttConnectOptions opts;
    private final String bridgeStatusTopic;
    // paho callbacks must not block, so subscribing and re-publishing after a reconnect run here
    private final ExecutorService callbackWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "midea2mqtt-mqtt");
        t.setDaemon(true);
        return t;
    });

    private volatile CommandListener commandListener;
    private volatile Runnable onConnected = () -> { };

    /** Receives commands: device name, sub topic ({@code set}, {@code set/mode}, {@code get}) and payload. */
    @FunctionalInterface
    public interface CommandListener {
        void onCommand(String device, String subTopic, String payload);
    }

    public MqttManager(Configuration config) {
        this.config = config;
        this.bridgeStatusTopic = config.baseTopic + "/bridge/status";
        String brokerUrl = "tcp://" + config.hostname + ":" + config.port;
        try {
            client = new MqttClient(brokerUrl, config.clientId, new MemoryPersistence());
        } catch (MqttException e) {
            throw new IllegalStateException("Invalid MQTT broker settings: " + e.getMessage(), e);
        }
        client.setCallback(this);
        opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        if (!config.username.isBlank()) {
            opts.setUserName(config.username);
        }
        if (!config.password.isEmpty()) {
            opts.setPassword(config.password.toCharArray());
        }
        opts.setConnectionTimeout(15);
        opts.setKeepAliveInterval(30);
        opts.setMaxReconnectDelay(30_000);
        opts.setAutomaticReconnect(true);
        opts.setWill(bridgeStatusTopic, "offline".getBytes(StandardCharsets.UTF_8), 1, true);
    }

    public void setCommandListener(CommandListener listener) {
        this.commandListener = listener;
    }

    /** Called after every (re)connect, once the subscriptions are in place. */
    public void setOnConnected(Runnable onConnected) {
        this.onConnected = onConnected;
    }

    /**
     * Connects, retrying until the broker is reachable. Paho's automatic reconnect only takes over
     * after the first successful connection, so the first attempt is retried here.
     */
    public void connect() {
        while (true) {
            try {
                client.connect(opts);
                return;
            } catch (MqttException e) {
                LOG.warning("Cannot connect to MQTT broker " + client.getServerURI() + ": " + e.getMessage()
                        + " - retrying in " + RETRY_MILLIS / 1000 + " s");
                try {
                    Thread.sleep(RETRY_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public void disconnect() {
        try {
            if (client.isConnected()) {
                publish(bridgeStatusTopic, "offline", true);
                client.disconnect(5_000);
            }
            client.close();
        } catch (MqttException e) {
            LOG.log(Level.FINE, "Disconnect failed", e);
        }
        callbackWorker.shutdownNow();
    }

    public String deviceTopic(String deviceName) {
        return config.baseTopic + "/" + deviceName;
    }

    /** Publishes with the configured retain flag (state, availability, capabilities). */
    public void publishRetained(String topic, String payload) {
        publish(topic, payload, config.retain);
    }

    /** Publishes the full state, and every value on its own topic if {@code publishAttributes} is on. */
    public void publishState(String stateTopic, JSONObject state) {
        publishRetained(stateTopic, state.toString());
        if (config.publishAttributes) {
            for (String key : state.keySet()) {
                publishRetained(stateTopic + "/" + key, String.valueOf(state.get(key)));
            }
        }
    }

    public void publish(String topic, String payload, boolean retained) {
        if (!client.isConnected()) {
            LOG.fine(() -> "Not connected, dropping message for " + topic);
            return;
        }
        MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        message.setRetained(retained);
        try {
            client.publish(topic, message);
        } catch (MqttException e) {
            LOG.warning("Publishing to " + topic + " failed: " + e.getMessage());
        }
    }

    // ---- paho callbacks

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        LOG.info((reconnect ? "Reconnected" : "Connected") + " to MQTT broker " + serverURI);
        callbackWorker.execute(() -> {
            try {
                String base = config.baseTopic;
                client.subscribe(new String[]{base + "/+/set", base + "/+/set/+", base + "/+/get"},
                        new int[]{1, 1, 1});
            } catch (MqttException e) {
                LOG.log(Level.SEVERE, "Subscribing to command topics failed", e);
            }
            publish(bridgeStatusTopic, "online", true);
            onConnected.run();
        });
    }

    @Override
    public void connectionLost(Throwable cause) {
        LOG.warning("Lost connection to MQTT broker: " + cause.getMessage() + " - reconnecting");
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String prefix = config.baseTopic + "/";
        if (!topic.startsWith(prefix)) {
            return;
        }
        String rest = topic.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return;
        }
        String device = rest.substring(0, slash);
        String subTopic = rest.substring(slash + 1);
        if (message.isRetained()) {
            // A retained command would be executed again on every reconnect.
            LOG.warning("Ignoring retained command on " + topic + " - publish commands without the retain flag");
            return;
        }
        CommandListener listener = commandListener;
        if (listener != null) {
            listener.onCommand(device, subTopic, new String(message.getPayload(), StandardCharsets.UTF_8));
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }
}
