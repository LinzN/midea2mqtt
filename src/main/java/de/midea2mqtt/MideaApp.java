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

import de.midea2mqtt.device.DeviceBridge;
import de.midea2mqtt.device.DeviceSettings;
import de.midea2mqtt.mqtt.MqttManager;
import de.mirranet.midea.ac.DeviceInfo;
import de.mirranet.midea.ac.ProtocolVersion;
import de.mirranet.midea.ac.discovery.MideaDiscovery;
import de.mirranet.midea.ac.example.Cli;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/**
 * Entry point.
 *
 * <pre>
 * java -jar midea2mqtt.jar [--config config.yml]   run the bridge
 * java -jar midea2mqtt.jar discover-config         find units and write them into config.yml
 * java -jar midea2mqtt.jar discover                find units on the LAN (print only)
 * java -jar midea2mqtt.jar keys --ip ...           fetch token/key for V3 units
 * </pre>
 * The helper commands are the midea-ac command line tool, which ships inside this jar.
 */
public class MideaApp {

    private static final Set<String> CLI_COMMANDS = Set.of("discover", "keys", "status", "set", "watch");

    static {
        if (System.getProperty("java.util.logging.SimpleFormatter.format") == null) {
            System.setProperty("java.util.logging.SimpleFormatter.format",
                    "%1$tF %1$tT [%4$s] %3$s: %5$s%6$s%n");
        }
    }

    private static final Logger LOG = Logger.getLogger(MideaApp.class.getName());

    private final Configuration configuration;
    private final MqttManager mqttManager;
    private final Map<String, DeviceBridge> devices = new LinkedHashMap<>();

    public MideaApp(File configFile) {
        this.configuration = new Configuration(configFile);
        this.mqttManager = new MqttManager(configuration);
        for (DeviceSettings settings : configuration.devices) {
            devices.put(settings.name(), new DeviceBridge(settings, mqttManager));
        }
        mqttManager.setCommandListener(this::onCommand);
        mqttManager.setOnConnected(() -> devices.values().forEach(DeviceBridge::republish));
    }

    public void start() {
        if (devices.isEmpty()) {
            LOG.warning("No enabled devices in config.yml - add one under 'devices' (see README) and restart");
        }
        mqttManager.connect();
        devices.values().forEach(DeviceBridge::start);
        LOG.info("midea2mqtt running with " + devices.size() + " device(s), base topic '"
                + configuration.baseTopic + "'");
    }

    public void stop() {
        // java.util.logging resets itself in its own shutdown hook, so this goes to stdout directly
        System.out.println("midea2mqtt shutting down");
        devices.values().forEach(DeviceBridge::stop);
        mqttManager.disconnect();
    }

    private void onCommand(String device, String subTopic, String payload) {
        DeviceBridge bridge = devices.get(device);
        if (bridge == null) {
            LOG.fine(() -> "Command for unknown device '" + device + "' ignored");
            return;
        }
        bridge.handle(subTopic, payload);
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public MqttManager getMqttManager() {
        return mqttManager;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && CLI_COMMANDS.contains(args[0])) {
            Cli.main(args);
            return;
        }
        boolean discoverConfig = args.length > 0 && args[0].equals("discover-config");
        File configFile = new File("config.yml");
        String ip = null;
        int timeout = 5;
        for (int i = discoverConfig ? 1 : 0; i < args.length; i++) {
            if ((args[i].equals("--config") || args[i].equals("-c")) && i + 1 < args.length) {
                configFile = new File(args[++i]);
            } else if (discoverConfig && args[i].equals("--ip") && i + 1 < args.length) {
                ip = args[++i];
            } else if (discoverConfig && args[i].equals("--timeout") && i + 1 < args.length) {
                timeout = Integer.parseInt(args[++i]);
            } else {
                System.err.println("Unknown argument: " + args[i]);
                printUsage();
                System.exit(2);
            }
        }
        if (discoverConfig) {
            System.exit(discoverConfig(configFile, ip, timeout));
        }

        MideaApp app = new MideaApp(configFile);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            stopped.countDown();
        }, "midea2mqtt-shutdown"));
        app.start();
        stopped.await();
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar midea2mqtt.jar [--config config.yml]");
        System.err.println("       java -jar midea2mqtt.jar discover-config [--ip IP] [--timeout 5] [--config config.yml]");
        System.err.println("       java -jar midea2mqtt.jar discover | keys | status | set | watch ...");
    }

    /**
     * Finds units on the LAN (or asks one address) and writes them into config.yml. Units that are
     * already configured (same id) are overwritten; token and key stay empty.
     */
    private static int discoverConfig(File configFile, String ip, int timeoutSeconds) throws IOException {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        System.out.println(ip == null
                ? "Searching for air conditioners on the LAN (" + timeoutSeconds + " s)..."
                : "Asking " + ip + " (" + timeoutSeconds + " s)...");
        List<DeviceInfo> found = new ArrayList<>();
        if (ip == null) {
            found.addAll(MideaDiscovery.discover(timeout));
        } else {
            DeviceInfo info = MideaDiscovery.discover(ip, timeout);
            if (info != null && info.isAirConditioner()) {
                found.add(info);
            }
        }
        found.removeIf(info -> {
            if (info.protocol() == ProtocolVersion.V1) {
                System.out.println("Skipping " + info.ipAddress() + " (id " + info.deviceId()
                        + "): protocol V1 is not supported");
                return true;
            }
            return false;
        });
        if (found.isEmpty()) {
            System.out.println("No air conditioner found, " + configFile + " left unchanged.");
            return 1;
        }

        Map<String, DeviceInfo> written = Configuration.writeDiscoveredDevices(configFile, found);
        System.out.println("Wrote " + written.size() + " device(s) to " + configFile + ":");
        boolean needsKeys = false;
        for (Map.Entry<String, DeviceInfo> e : written.entrySet()) {
            DeviceInfo info = e.getValue();
            System.out.printf("  %-24s %s:%d  id=%d  model=%s  %s%n", e.getKey(), info.ipAddress(), info.port(),
                    info.deviceId(), info.model(), info.protocol());
            needsKeys |= info.protocol() == ProtocolVersion.V3;
        }
        if (needsKeys) {
            System.out.println();
            System.out.println("V3 units need 'token' and 'key' in config.yml before they work. Get them with:");
            System.out.println("  java -jar midea2mqtt.jar keys --ip <IP> [--cloud msmart --user U --password P]");
        }
        return 0;
    }
}
