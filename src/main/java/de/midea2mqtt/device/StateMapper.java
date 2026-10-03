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

import de.mirranet.midea.ac.AcState;
import de.mirranet.midea.ac.Capabilities;
import de.mirranet.midea.ac.OperatingMode;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;

/**
 * Turns snapshots from the API into the JSON published on MQTT.
 *
 * <p>Every key that can be written through {@code .../set} uses the same name and value format
 * here, so a value read from {@code .../state} can be sent back unchanged. Values the unit has not
 * reported (energy, humidity, service data on many units) are left out instead of being null.
 */
public final class StateMapper {

    private StateMapper() {
    }

    public static JSONObject state(AcState s) {
        JSONObject j = new JSONObject();
        j.put("available", s.isAvailable());
        if (s.getLastUpdate() != null) {
            j.put("lastUpdate", s.getLastUpdate().toString());
        }

        // writable
        j.put("power", s.isPower());
        putEnum(j, "mode", s.getMode());
        putEnum(j, "activeMode", s.getActiveMode());
        j.put("targetTemperature", s.getTargetTemperature());
        putEnum(j, "fanSpeed", s.getFanSpeed());
        j.put("fanSpeedRaw", s.getFanSpeedRaw());
        putEnum(j, "swing", s.getSwingMode());
        putEnum(j, "preset", s.getPreset());
        j.put("powerSaving", s.isPowerSaving());
        j.put("auxHeating", s.isAuxHeating());
        j.put("dry", s.isDry());
        j.put("smartEye", s.isSmartEye());
        j.put("naturalWind", s.isNaturalWind());
        j.put("anion", s.isAnion());
        j.put("fahrenheit", s.isFahrenheit());
        j.put("screenDisplay", s.isScreenDisplay());
        j.put("screenDisplayAlternate", s.isScreenDisplayAlternate());
        j.put("breezeless", s.isBreezeless());
        j.put("indirectWind", s.isIndirectWind());
        putEnum(j, "horizontalVane", s.getHorizontalVane());
        putEnum(j, "verticalVane", s.getVerticalVane());
        j.put("outdoorSilent", s.isOutSilent());
        j.put("sound", s.isSound());
        j.put("selfClean", s.isSelfClean());
        putOpt(j, "rateSelect", s.getRateSelect());
        if (s.hasFreshAir()) {
            j.put("freshAir", s.isFreshAirPower());
            j.put("freshAirFanSpeed", s.getFreshAirFanSpeed());
        }

        // read only
        j.put("swingVertical", s.isSwingVertical());
        j.put("swingHorizontal", s.isSwingHorizontal());
        j.put("eco", s.isEco());
        j.put("boost", s.isBoost());
        j.put("sleep", s.isSleep());
        j.put("comfort", s.isComfort());
        j.put("frostProtect", s.isFrostProtect());
        j.put("fullDust", s.isFullDust());
        j.put("errorCode", s.getErrorCode());
        putOpt(j, "indoorTemperature", s.getIndoorTemperature());
        putOpt(j, "outdoorTemperature", s.getOutdoorTemperature());
        putOpt(j, "indoorHumidity", s.getIndoorHumidity());
        putOpt(j, "pmv", s.getPmv());
        putOpt(j, "minTemperature", s.getMinTemperature());
        putOpt(j, "maxTemperature", s.getMaxTemperature());

        // energy
        putOpt(j, "realtimePower", s.getRealtimePower());
        putOpt(j, "totalEnergyConsumption", s.getTotalEnergyConsumption());
        putOpt(j, "totalOperatingConsumption", s.getTotalOperatingConsumption());
        putOpt(j, "currentEnergyConsumption", s.getCurrentEnergyConsumption());
        putOpt(j, "electrifyTime", s.getElectrifyTime());
        putOpt(j, "totalOperatingTime", s.getTotalOperatingTime());
        putOpt(j, "currentOperatingTime", s.getCurrentOperatingTime());

        // service data
        putOpt(j, "compressorFrequency", s.getCompressorFrequency());
        putOpt(j, "targetCompressorFrequency", s.getTargetCompressorFrequency());
        putOpt(j, "compressorCurrent", s.getCompressorCurrent());
        putOpt(j, "compressorVoltage", s.getCompressorVoltage());
        putOpt(j, "compressorPower", s.getCompressorPower());
        putOpt(j, "indoorAmbientTemperature", s.getIndoorAmbientTemperature());
        putOpt(j, "indoorCoilTemperature", s.getIndoorCoilTemperature());
        putOpt(j, "outdoorCoilTemperature", s.getOutdoorCoilTemperature());
        putOpt(j, "outdoorAmbientTemperature", s.getOutdoorAmbientTemperature());
        putOpt(j, "dischargePipeTemperature", s.getDischargePipeTemperature());
        putOpt(j, "indoorFanSpeed", s.getIndoorFanSpeed());
        putOpt(j, "targetIndoorFanSpeed", s.getTargetIndoorFanSpeed());
        putOpt(j, "outdoorFanSpeed", s.getOutdoorFanSpeed());
        putOpt(j, "waterPumpRunning", s.getWaterPumpRunning());
        return j;
    }

    public static JSONObject capabilities(Capabilities caps) {
        JSONObject j = new JSONObject();
        j.put("received", caps.isReceived());
        JSONArray modes = new JSONArray();
        for (OperatingMode m : caps.supportedModes()) {
            modes.put(name(m));
        }
        j.put("modes", modes);
        JSONObject limits = new JSONObject();
        for (OperatingMode m : OperatingMode.values()) {
            double[] l = caps.temperatureLimits(m);
            if (l != null) {
                limits.put(name(m), new JSONArray().put(l[0]).put(l[1]));
            }
        }
        j.put("temperatureLimits", limits);
        JSONObject features = new JSONObject();
        for (Map.Entry<Capabilities.Feature, Boolean> e : caps.asMap().entrySet()) {
            features.put(name(e.getKey()), e.getValue());
        }
        j.put("features", features);
        return j;
    }

    /** Enum constant as it appears in JSON: lower case, e.g. {@code FAN_ONLY -> fan_only}. */
    public static String name(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static void putEnum(JSONObject j, String key, Enum<?> value) {
        if (value != null) {
            j.put(key, name(value));
        }
    }

    private static void putOpt(JSONObject j, String key, Object value) {
        if (value != null) {
            j.put(key, value);
        }
    }
}
