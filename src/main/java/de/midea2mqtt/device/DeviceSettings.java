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

import de.mirranet.midea.ac.DeviceConfig;
import de.mirranet.midea.ac.ProtocolVersion;

import java.time.Duration;
import java.util.Map;

/**
 * One entry below {@code devices:} in config.yml. The entry's key is the device name and becomes
 * part of every MQTT topic for that unit.
 */
public record DeviceSettings(String name,
                             boolean enabled,
                             String host,
                             int port,
                             long deviceId,
                             ProtocolVersion protocol,
                             String token,
                             String key,
                             boolean beep,
                             Duration pollInterval,
                             int powerAnalysisMethod) {

    public static DeviceSettings fromMap(String name, Map<?, ?> m) {
        if (name.isEmpty() || name.contains("/") || name.contains("+") || name.contains("#")) {
            throw new IllegalArgumentException("name must not be empty or contain '/', '+' or '#'");
        }
        boolean enabled = bool(m.get("enabled"), true);
        String host = str(m.get("host"), null);
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("'host' is missing");
        }
        String id = str(m.get("id"), null);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("'id' is missing");
        }
        long deviceId;
        try {
            deviceId = Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'id' is not a number: " + id);
        }
        int protocolNumber = (int) num(m.get("protocol"), 3);
        ProtocolVersion protocol = switch (protocolNumber) {
            case 2 -> ProtocolVersion.V2;
            case 3 -> ProtocolVersion.V3;
            default -> throw new IllegalArgumentException("'protocol' must be 2 or 3");
        };
        String token = emptyToNull(str(m.get("token"), null));
        String key = emptyToNull(str(m.get("key"), null));
        if (enabled && protocol == ProtocolVersion.V3 && (token == null || key == null)) {
            throw new IllegalArgumentException("protocol 3 needs 'token' and 'key' - get them with: "
                    + "java -jar midea2mqtt.jar keys --ip " + host.trim());
        }
        long poll = num(m.get("pollInterval"), 30);
        if (poll < 5) {
            throw new IllegalArgumentException("'pollInterval' must be at least 5 seconds");
        }
        return new DeviceSettings(name, enabled, host.trim(),
                (int) num(m.get("port"), DeviceConfig.DEFAULT_PORT),
                deviceId, protocol, token, key,
                bool(m.get("beep"), false),
                Duration.ofSeconds(poll),
                (int) num(m.get("powerAnalysisMethod"), 1));
    }

    public DeviceConfig toDeviceConfig() {
        DeviceConfig.Builder b = DeviceConfig.builder()
                .host(host)
                .port(port)
                .deviceId(deviceId)
                .protocol(protocol)
                .promptTone(beep)
                .powerAnalysisMethod(powerAnalysisMethod);
        if (token != null && key != null) {
            b.token(token).key(key);
        }
        return b.build();
    }

    // YAML gives Integer, Long, String or Boolean depending on how the value was written,
    // so these helpers accept all of them.

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static long num(Object o, long def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + o);
        }
    }

    private static boolean bool(Object o, boolean def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(o).trim());
    }
}
