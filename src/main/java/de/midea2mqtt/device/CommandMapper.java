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

import de.mirranet.midea.ac.AcControl;
import de.mirranet.midea.ac.AcState;
import de.mirranet.midea.ac.FanSpeed;
import de.mirranet.midea.ac.MideaAirConditioner;
import de.mirranet.midea.ac.OperatingMode;
import de.mirranet.midea.ac.Preset;
import de.mirranet.midea.ac.SwingMode;
import de.mirranet.midea.ac.Vane;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Translates a JSON command from MQTT into API calls.
 *
 * <p>The whole command is parsed and validated first, so a typo or an out-of-range value rejects
 * the command before anything is sent. Then all basic settings go out as one {@link AcControl}
 * command (one beep, no intermediate states) and the extra features follow as separate commands,
 * the same split the API itself makes.
 */
public final class CommandMapper {

    /** Keys handled by {@link AcControl}, sent together as one command. */
    static final Set<String> BASIC_KEYS = Set.of(
            "power", "mode", "targetTemperature", "fanSpeed", "swing", "preset",
            "powerSaving", "auxHeating", "dry", "smartEye", "naturalWind", "anion", "fahrenheit");

    /** Keys that the API sends as their own command. */
    static final Set<String> EXTRA_KEYS = Set.of(
            "screenDisplay", "screenDisplayAlternate", "breezeless", "indirectWind",
            "horizontalVane", "verticalVane", "outdoorSilent", "sound", "selfClean",
            "rateSelect", "freshAir", "freshAirFanSpeed");

    private CommandMapper() {
    }

    public static boolean isWritable(String key) {
        return BASIC_KEYS.contains(key) || EXTRA_KEYS.contains(key);
    }

    /**
     * Validates and runs a command.
     *
     * @return the problems that came up while sending; empty if everything went through
     * @throws IllegalArgumentException if the command is invalid; nothing was sent in that case
     */
    public static List<String> execute(MideaAirConditioner ac, JSONObject command) {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("empty command");
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (String key : command.keySet()) {
            if (!isWritable(key)) {
                throw new IllegalArgumentException("unknown or read-only key '" + key + "'");
            }
            parsed.put(key, parse(key, command.get(key)));
        }

        List<String> errors = new ArrayList<>();
        if (parsed.keySet().stream().anyMatch(BASIC_KEYS::contains)) {
            try {
                AcControl c = ac.control();
                // mode() also switches the unit on, so power is applied last:
                // {"power": false, "mode": "cool"} sets the mode and leaves the unit off.
                parsed.forEach((k, v) -> {
                    if (!k.equals("power")) {
                        applyBasic(c, k, v);
                    }
                });
                if (parsed.containsKey("power")) {
                    applyBasic(c, "power", parsed.get("power"));
                }
                c.send();
            } catch (IOException | RuntimeException e) {
                errors.add("basic settings: " + message(e));
            }
        }
        for (Map.Entry<String, Object> e : parsed.entrySet()) {
            String key = e.getKey();
            if (!EXTRA_KEYS.contains(key) || key.equals("freshAirFanSpeed")) {
                continue;
            }
            try {
                applyExtra(ac, key, e.getValue(), parsed);
            } catch (IOException | RuntimeException ex) {
                errors.add(key + ": " + message(ex));
            }
        }
        // Fan speed alone (without "freshAir") still has to reach the unit.
        if (parsed.containsKey("freshAirFanSpeed") && !parsed.containsKey("freshAir")) {
            try {
                int speed = (Integer) parsed.get("freshAirFanSpeed");
                ac.setFreshAir(speed > 0, speed);
            } catch (IOException | RuntimeException ex) {
                errors.add("freshAirFanSpeed: " + message(ex));
            }
        }
        return errors;
    }

    private static Object parse(String key, Object raw) {
        try {
            return switch (key) {
                case "mode" -> enumValue(OperatingMode.class, raw);
                case "swing" -> enumValue(SwingMode.class, raw);
                case "preset" -> enumValue(Preset.class, raw);
                case "horizontalVane" -> enumValue(Vane.Horizontal.class, raw);
                case "verticalVane" -> enumValue(Vane.Vertical.class, raw);
                case "fanSpeed" -> fanSpeed(raw);
                case "targetTemperature" -> number(raw).doubleValue();
                case "rateSelect", "freshAirFanSpeed" -> integer(raw);
                default -> bool(raw);
            };
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + key + "': " + e.getMessage());
        }
    }

    private static void applyBasic(AcControl c, String key, Object v) {
        switch (key) {
            case "power" -> c.power((Boolean) v);
            case "mode" -> c.mode((OperatingMode) v);
            case "targetTemperature" -> c.targetTemperature((Double) v);
            case "fanSpeed" -> {
                if (v instanceof FanSpeed f) {
                    c.fanSpeed(f);
                } else {
                    c.fanSpeedRaw((Integer) v);
                }
            }
            case "swing" -> c.swing((SwingMode) v);
            case "preset" -> c.preset((Preset) v);
            case "powerSaving" -> c.powerSaving((Boolean) v);
            case "auxHeating" -> c.auxHeating((Boolean) v);
            case "dry" -> c.dry((Boolean) v);
            case "smartEye" -> c.smartEye((Boolean) v);
            case "naturalWind" -> c.naturalWind((Boolean) v);
            case "anion" -> c.anion((Boolean) v);
            case "fahrenheit" -> c.fahrenheitDisplay((Boolean) v);
            default -> {
                // extra keys are handled separately
            }
        }
    }

    private static void applyExtra(MideaAirConditioner ac, String key, Object v,
                                   Map<String, Object> all) throws IOException {
        switch (key) {
            case "screenDisplay" -> ac.setScreenDisplay((Boolean) v);
            case "screenDisplayAlternate" -> ac.setScreenDisplayAlternate((Boolean) v);
            case "breezeless" -> ac.setBreezeless((Boolean) v);
            case "indirectWind" -> ac.setIndirectWind((Boolean) v);
            case "horizontalVane" -> ac.setHorizontalVane((Vane.Horizontal) v);
            case "verticalVane" -> ac.setVerticalVane((Vane.Vertical) v);
            case "outdoorSilent" -> ac.setOutdoorSilent((Boolean) v);
            case "sound" -> ac.setSound((Boolean) v);
            case "selfClean" -> ac.setSelfClean((Boolean) v);
            case "rateSelect" -> ac.setRateSelect((Integer) v);
            case "freshAir" -> {
                boolean on = (Boolean) v;
                Integer speed = (Integer) all.get("freshAirFanSpeed");
                if (speed == null) {
                    AcState s = ac.getState();
                    speed = s.getFreshAirFanSpeed() > 0 ? s.getFreshAirFanSpeed() : 100;
                }
                ac.setFreshAir(on, speed);
            }
            default -> throw new IllegalStateException("unhandled key " + key);
        }
    }

    // ---- value parsing; MQTT payloads often arrive as strings, so strings are accepted everywhere

    static <E extends Enum<E>> E enumValue(Class<E> type, Object raw) {
        String s = String.valueOf(raw).trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            return Enum.valueOf(type, s);
        } catch (IllegalArgumentException e) {
            StringBuilder allowed = new StringBuilder();
            for (E c : type.getEnumConstants()) {
                if (!allowed.isEmpty()) {
                    allowed.append(", ");
                }
                allowed.append(StateMapper.name(c));
            }
            throw new IllegalArgumentException("'" + raw + "' is not one of " + allowed);
        }
    }

    private static Object fanSpeed(Object raw) {
        if (raw instanceof Number || String.valueOf(raw).trim().matches("\\d+")) {
            return integer(raw);
        }
        return enumValue(FanSpeed.class, raw);
    }

    static Number number(Object raw) {
        if (raw instanceof Number n) {
            return n;
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a number");
        }
    }

    static int integer(Object raw) {
        Number n = number(raw);
        if (n.doubleValue() != Math.rint(n.doubleValue())) {
            throw new IllegalArgumentException("'" + raw + "' is not a whole number");
        }
        return n.intValue();
    }

    static boolean bool(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof Number n) {
            return n.intValue() != 0;
        }
        return switch (String.valueOf(raw).trim().toLowerCase(Locale.ROOT)) {
            case "true", "on", "1", "yes" -> true;
            case "false", "off", "0", "no" -> false;
            default -> throw new IllegalArgumentException("'" + raw + "' is not a boolean (true/false, on/off)");
        };
    }

    private static String message(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
