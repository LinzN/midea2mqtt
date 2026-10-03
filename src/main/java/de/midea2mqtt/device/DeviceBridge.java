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

package de.midea2mqtt.device;

import de.midea2mqtt.mqtt.MqttManager;
import de.mirranet.midea.ac.AcState;
import de.mirranet.midea.ac.MideaAirConditioner;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connects one air conditioner to MQTT.
 *
 * <p>Read path: the API polls the unit in the background and calls the listener on every refresh,
 * command confirmation and push notification; each snapshot is published to {@code .../state}.
 * Write path: messages on {@code .../set} are queued on this device's own worker thread, so a slow
 * or unreachable unit never blocks the MQTT client or the other devices.
 */
public class DeviceBridge {

    private static final Logger LOG = Logger.getLogger(DeviceBridge.class.getName());

    private final DeviceSettings settings;
    private final MqttManager mqtt;
    private final MideaAirConditioner ac;
    private final ExecutorService worker;
    private final String topic;

    private volatile Boolean lastAvailable;
    private volatile boolean capabilitiesPublished;

    public DeviceBridge(DeviceSettings settings, MqttManager mqtt) {
        this.settings = settings;
        this.mqtt = mqtt;
        this.topic = mqtt.deviceTopic(settings.name());
        this.ac = new MideaAirConditioner(settings.toDeviceConfig());
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "midea2mqtt-" + settings.name());
            t.setDaemon(true);
            return t;
        });
    }

    public String getName() {
        return settings.name();
    }

    public void start() {
        LOG.info("Starting device '" + settings.name() + "' (" + settings.host() + ", "
                + settings.protocol() + ", poll every " + settings.pollInterval().toSeconds() + " s)");
        ac.addListener(this::onState);
        ac.startPolling(settings.pollInterval());
    }

    public void stop() {
        worker.shutdownNow();
        ac.close();
        mqtt.publishRetained(topic + "/availability", "offline");
    }

    // ---- read path

    private void onState(AcState state) {
        boolean available = state.isAvailable();
        if (lastAvailable == null || lastAvailable != available) {
            lastAvailable = available;
            mqtt.publishRetained(topic + "/availability", available ? "online" : "offline");
            LOG.info("Device '" + settings.name() + "' is " + (available ? "online" : "offline"));
        }
        if (!available) {
            return;
        }
        JSONObject json = StateMapper.state(state);
        mqtt.publishState(topic + "/state", json);
        if (!capabilitiesPublished && ac.getCapabilities().isReceived()) {
            publishCapabilities();
        }
    }

    private void publishCapabilities() {
        mqtt.publishRetained(topic + "/capabilities", StateMapper.capabilities(ac.getCapabilities()).toString());
        capabilitiesPublished = true;
    }

    /** Re-publishes everything retained, e.g. after the broker connection came back. */
    public void republish() {
        Boolean available = lastAvailable;
        if (available != null) {
            mqtt.publishRetained(topic + "/availability", available ? "online" : "offline");
        }
        if (Boolean.TRUE.equals(available)) {
            mqtt.publishState(topic + "/state", StateMapper.state(ac.getState()));
        }
        if (capabilitiesPublished) {
            publishCapabilities();
        }
    }

    // ---- write path

    /**
     * Handles a message below this device's topic.
     *
     * @param subTopic the part after {@code <base>/<device>/}, e.g. {@code set} or {@code set/mode}
     */
    public void handle(String subTopic, String payload) {
        try {
            worker.execute(() -> process(subTopic, payload));
        } catch (RejectedExecutionException e) {
            // shutting down
        }
    }

    private void process(String subTopic, String payload) {
        JSONObject command;
        if (subTopic.equals("get")) {
            refreshAndPublish();
            return;
        } else if (subTopic.equals("set")) {
            try {
                command = new JSONObject(payload);
            } catch (JSONException e) {
                publishError("set", payload, "payload is not a JSON object: " + e.getMessage());
                return;
            }
        } else if (subTopic.startsWith("set/") && subTopic.indexOf('/', 4) < 0) {
            String key = subTopic.substring(4);
            command = new JSONObject().put(key, payload.trim());
        } else {
            return;
        }

        LOG.info("Device '" + settings.name() + "' command: " + command);
        try {
            ensureStateKnown();
            List<String> errors = CommandMapper.execute(ac, command);
            for (String error : errors) {
                publishError(subTopic, payload, error);
            }
        } catch (IllegalArgumentException e) {
            publishError(subTopic, payload, e.getMessage());
        } catch (IOException e) {
            publishError(subTopic, payload, "unit not reachable: " + e.getMessage());
        }
    }

    /** The API builds commands from the last known state, so it needs one refresh first. */
    private void ensureStateKnown() throws IOException {
        if (ac.getState().getLastUpdate() == null) {
            ac.refresh();
        }
    }

    private void refreshAndPublish() {
        try {
            ac.refresh();   // listener publishes the state
            publishCapabilities();
        } catch (IOException e) {
            publishError("get", "", "unit not reachable: " + e.getMessage());
        }
    }

    private void publishError(String subTopic, String payload, String message) {
        LOG.warning("Device '" + settings.name() + "' " + subTopic + " failed: " + message);
        JSONObject j = new JSONObject();
        j.put("topic", topic + "/" + subTopic);
        j.put("payload", payload);
        j.put("error", message);
        try {
            mqtt.publish(topic + "/error", j.toString(), false);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Could not publish error", e);
        }
    }
}
