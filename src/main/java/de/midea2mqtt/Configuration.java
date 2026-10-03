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

package de.midea2mqtt;

import de.linzn.simplyConfiguration.FileConfiguration;
import de.linzn.simplyConfiguration.provider.YamlConfiguration;
import de.midea2mqtt.device.DeviceSettings;
import de.mirranet.midea.ac.DeviceInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads {@code config.yml}. Missing keys are filled with defaults and written back, so the first
 * start produces a complete file with an example device (disabled) to edit.
 */
public class Configuration {

    private static final Logger LOG = Logger.getLogger(Configuration.class.getName());

    private final FileConfiguration fileConfiguration;

    public final String hostname;
    public final int port;
    public final String username;
    public final String password;
    public final String clientId;
    public final String baseTopic;
    public final boolean retain;
    public final boolean publishAttributes;
    public final List<DeviceSettings> devices;

    /** Name and id of the example device written on the first start. */
    private static final String EXAMPLE_NAME = "livingroom";
    private static final String EXAMPLE_ID = "151732605161920";

    public Configuration(File file) {
        fileConfiguration = loadWithDefaults(file);
        this.hostname = fileConfiguration.getString("mqtt.ip");
        this.port = fileConfiguration.getInt("mqtt.port");
        this.username = fileConfiguration.getString("mqtt.username");
        this.password = fileConfiguration.getString("mqtt.password");
        this.clientId = fileConfiguration.getString("mqtt.clientId");
        this.baseTopic = stripSlashes(fileConfiguration.getString("mqtt.baseTopic"));
        this.retain = fileConfiguration.getBoolean("mqtt.retain");
        this.publishAttributes = fileConfiguration.getBoolean("mqtt.publishAttributes");
        this.devices = parseDevices(fileConfiguration.get("devices"));
    }

    public FileConfiguration getFileConfiguration() {
        return this.fileConfiguration;
    }

    /** Loads the file, fills in every missing key with its default and saves it. */
    private static FileConfiguration loadWithDefaults(File file) {
        FileConfiguration fc = YamlConfiguration.loadConfiguration(file);
        fc.getString("mqtt.ip", "127.0.0.1");
        fc.getInt("mqtt.port", 1883);
        fc.getString("mqtt.username", "");
        fc.getString("mqtt.password", "");
        fc.getString("mqtt.clientId", "midea2mqtt");
        fc.getString("mqtt.baseTopic", "midea2mqtt");
        fc.getBoolean("mqtt.retain", true);
        fc.getBoolean("mqtt.publishAttributes", false);
        if (!(fc.get("devices") instanceof Map<?, ?>)) {
            Map<String, Object> devices = new LinkedHashMap<>();
            devices.put(EXAMPLE_NAME, deviceEntry(false, "192.168.1.50", 6444, EXAMPLE_ID, 3));
            fc.set("devices", devices);
        }
        fc.save();
        return fc;
    }

    /**
     * Writes discovered units into the {@code devices} section. A unit whose id is already
     * configured overwrites that entry (keeping its name); new units are added as {@code ac-<id>}.
     * The untouched example device is removed. Token and key are left empty and have to be
     * filled in for V3 units, e.g. with the {@code keys} command.
     *
     * @return device name to discovered unit, in the order they were written
     */
    @SuppressWarnings("unchecked")
    public static Map<String, DeviceInfo> writeDiscoveredDevices(File file, List<DeviceInfo> found) {
        FileConfiguration fc = loadWithDefaults(file);
        Map<String, Object> devices = (Map<String, Object>) fc.get("devices");

        Object example = devices.get(EXAMPLE_NAME);
        if (example instanceof Map<?, ?> m && EXAMPLE_ID.equals(String.valueOf(m.get("id")))) {
            devices.remove(EXAMPLE_NAME);
        }

        Map<String, DeviceInfo> written = new LinkedHashMap<>();
        for (DeviceInfo info : found) {
            String id = String.valueOf(info.deviceId());
            String name = null;
            for (Map.Entry<String, Object> e : devices.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> m && id.equals(String.valueOf(m.get("id")).trim())) {
                    name = e.getKey();
                    break;
                }
            }
            if (name == null) {
                name = "ac-" + id;
            }
            devices.put(name, deviceEntry(true, info.ipAddress(), info.port(), id, info.protocol().value()));
            written.put(name, info);
        }
        fc.save();
        return written;
    }

    private static Map<String, Object> deviceEntry(boolean enabled, String host, int port, String id, int protocol) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("enabled", enabled);
        entry.put("host", host);
        entry.put("port", port);
        entry.put("id", id);
        entry.put("protocol", protocol);
        entry.put("token", "");
        entry.put("key", "");
        entry.put("beep", false);
        entry.put("pollInterval", 30);
        entry.put("powerAnalysisMethod", 1);
        return entry;
    }

    private static List<DeviceSettings> parseDevices(Object section) {
        List<DeviceSettings> result = new ArrayList<>();
        if (!(section instanceof Map<?, ?> map)) {
            LOG.warning("config.yml: 'devices' is not a section, no devices loaded");
            return result;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String name = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map<?, ?> values)) {
                LOG.warning("config.yml: device '" + name + "' is not a section, skipped");
                continue;
            }
            try {
                DeviceSettings settings = DeviceSettings.fromMap(name, values);
                if (settings.enabled()) {
                    result.add(settings);
                } else {
                    LOG.info("Device '" + name + "' is disabled in config.yml");
                }
            } catch (IllegalArgumentException ex) {
                LOG.warning("config.yml: device '" + name + "' skipped: " + ex.getMessage());
            }
        }
        return result;
    }

    private static String stripSlashes(String topic) {
        String t = topic.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        while (t.startsWith("/")) {
            t = t.substring(1);
        }
        return t;
    }
}
