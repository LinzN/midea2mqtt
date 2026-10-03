# midea2mqtt

A standalone Java bridge between **Midea air conditioners** and a **local MQTT broker**. It reads the state of your
units and publishes it to MQTT, and it takes commands from MQTT and sends them to the units. Everything stays on your
LAN: no cloud, no Home Assistant required. Works with Home Assistant, Node-RED, [STEM](https://github.com/LinzN/Stem)
or anything else that speaks MQTT.

Built on [midea-ac](https://github.com/LinzN/midea-ac), so it supports the same units: Midea and the many brands
using Midea indoor units and Wi-Fi modules (Comfee, Inventor, Carrier, Toshiba, Electrolux, Pro Klima, ...), protocol
V2 and V3.

---

## How It Works

```
                        state (JSON) ──►
Midea AC  ◄── LAN ──►  midea2mqtt  ◄──►  Local MQTT Broker  ◄──►  Home Assistant / Node-RED / STEM
                        ◄── commands (JSON)
```

Every configured unit is polled in the background (full refresh every `pollInterval`, a heartbeat every 10 s in
between, and changes the unit reports on its own, e.g. from the IR remote, are picked up immediately). Each new state
is published to `midea2mqtt/<device>/state`. Messages on `midea2mqtt/<device>/set` are translated into API calls.

---

## Requirements

- Java 21 or higher (use the version your `simplyConfiguration` build targets, if that is newer)
- Maven 3.x
- A running local MQTT broker (e.g. [Mosquitto](https://mosquitto.org/))
- The air conditioner on the same network, and for V3 units its token and key (see Step 1)

---

## Setup

### Step 1 — Build and find your units

```bash
git clone https://github.com/LinzN/midea2mqtt.git
cd midea2mqtt
mvn clean package
```

This builds `target/midea2mqtt.jar` and copies all libraries to `target/lib/`. The jar loads them from the `lib`
folder next to it, so when you move the bridge somewhere else, copy `midea2mqtt.jar` together with the whole `lib`
folder.

Let it find your units and write them into
`config.yml`:

```bash
java -jar target/midea2mqtt.jar discover-config
```

| Option            | Default      | Description                                                     |
|-------------------|--------------|-----------------------------------------------------------------|
| `--ip IP`         | broadcast    | ask one address instead of broadcasting (works across VLANs)    |
| `--timeout N`     | `5`          | seconds to wait for answers                                     |
| `--config FILE`   | `config.yml` | config file to write                                            |

Every unit found is written under `devices` with host, port, id and protocol. A unit whose id is already in the
config **overwrites** that entry (the device name is kept, all other values are reset to defaults); new units are added
as `ac-<id>`. Entries for units that weren't found stay untouched, and the example device is removed. `token` and `key`
are left empty.

V2 units work right away. **V3 units need a token and key** before they connect; until then the bridge skips them
with a warning. The midea-ac command line tool, reachable through the same jar, fetches them:

```bash
java -jar target/midea2mqtt.jar keys --ip 192.168.1.50 --cloud msmart --user you@example.com --password secret
```

`keys` logs in to the Midea cloud once and prints a `token` and `key` that were tested against the unit. Paste them
into the device's entry in `config.yml`. After that the cloud is never contacted again. See the
[midea-ac README](https://github.com/LinzN/midea-ac#getting-started) for details and the other cloud options.
`java -jar target/midea2mqtt.jar discover` only lists the units without touching the config.

### Step 2 — First run & configuration

If you skipped `discover-config`, the first run creates a `config.yml` in the working directory with an example device
that is disabled. Either way, check the file before starting the bridge for real:

```bash
java -jar target/midea2mqtt.jar
nano config.yml
```

```yaml
mqtt:
  ip: 127.0.0.1
  port: 1883
  username: ''
  password: ''
  clientId: midea2mqtt
  baseTopic: midea2mqtt
  retain: true
  publishAttributes: false
devices:
  livingroom:                 # device name, used in the topics
    enabled: true
    host: 192.168.1.50
    port: 6444
    id: '151732605161920'     # keep the quotes
    protocol: 3               # 2 or 3
    token: 4b2f...            # V3 only, 128 hex characters
    key: 9a1c...              # V3 only, 64 hex characters
    beep: false               # unit beeps on every command
    pollInterval: 30          # seconds, at least 5
    powerAnalysisMethod: 1    # energy encoding, try 2 or 3 if kWh values look wrong
```

| Parameter                | Description                                                                 |
|--------------------------|-----------------------------------------------------------------------------|
| `mqtt.ip` / `mqtt.port`  | Your local MQTT broker                                                      |
| `mqtt.username/password` | Broker credentials, leave empty for anonymous                               |
| `mqtt.clientId`          | MQTT client id, must be unique on the broker                                |
| `mqtt.baseTopic`         | Prefix of all topics                                                        |
| `mqtt.retain`            | Publish state, availability and capabilities as retained messages           |
| `mqtt.publishAttributes` | Additionally publish every state value on its own topic (`.../state/<key>`) |

Add as many units as you like under `devices`. Use a different config file with `--config /path/to/config.yml`.

### Step 3 — Run

```bash
java -jar target/midea2mqtt.jar
```

The first refresh of a unit can take up to a minute: the API probes which optional queries (energy, humidity, service
data, capabilities) the unit answers. After that a refresh takes well under a second.

---

## MQTT Topics

`<base>` is `mqtt.baseTopic` (default `midea2mqtt`), `<device>` is the name from `config.yml`.

| Topic                         | Direction | Content                                                            |
|-------------------------------|-----------|--------------------------------------------------------------------|
| `<base>/bridge/status`        | read      | `online` / `offline` (retained, also the MQTT last will)           |
| `<base>/<device>/availability`| read      | `online` / `offline`, whether the unit answers (retained)          |
| `<base>/<device>/state`       | read      | full state as JSON                                                 |
| `<base>/<device>/state/<key>` | read      | single value, only with `publishAttributes: true`                  |
| `<base>/<device>/capabilities`| read      | supported modes, temperature limits and features, JSON             |
| `<base>/<device>/error`       | read      | why a command failed, JSON (not retained)                          |
| `<base>/<device>/set`         | write     | command as JSON object, any number of keys                         |
| `<base>/<device>/set/<key>`   | write     | one value as plain text, e.g. `set/mode` ← `cool`                  |
| `<base>/<device>/get`         | write     | any payload: refresh now and publish state and capabilities        |

If the bridge itself dies, the broker sets `bridge/status` to `offline`, but the device `availability` keeps its last
value. Consumers should check both (Home Assistant supports multiple availability topics).

Publish commands **without** the retain flag. A retained command would run again on every reconnect, so the bridge
ignores retained messages on the command topics.

### Reading

Example `state`:

```json
{
  "available": true,
  "lastUpdate": "2026-10-03T18:57:36Z",
  "power": true,
  "mode": "cool",
  "targetTemperature": 22.5,
  "fanSpeed": "auto",
  "fanSpeedRaw": 102,
  "swing": "vertical",
  "preset": "none",
  "screenDisplay": true,
  "indoorTemperature": 24.1,
  "outdoorTemperature": 29.8,
  "indoorHumidity": 55,
  "realtimePower": 891.2,
  "totalEnergyConsumption": 123.45,
  "errorCode": 0
}
```

(shortened). Temperatures are always Celsius. Values the unit doesn't report (energy, humidity, service data such as
`compressorFrequency` or coil temperatures on many units) are left out rather than sent as `null`.

### Writing

Every writable key uses the **same name and format as in `state`**, so you can send back what you read.

```bash
# several settings in one command (one beep, no intermediate states)
mosquitto_pub -t midea2mqtt/livingroom/set -m '{"mode":"cool","targetTemperature":22.5,"fanSpeed":"auto"}'

# a single value
mosquitto_pub -t midea2mqtt/livingroom/set/power -m off
mosquitto_pub -t midea2mqtt/livingroom/set/targetTemperature -m 21
```

| Key                      | Values                                                                    |
|--------------------------|---------------------------------------------------------------------------|
| `power`                  | `true`/`false`, also `on`/`off`, `1`/`0`                                   |
| `mode`                   | `auto`, `cool`, `dry`, `heat`, `fan_only` (also switches the unit on)      |
| `targetTemperature`      | 16 to 31.5, rounded to 0.5                                                 |
| `fanSpeed`               | `silent`, `low`, `medium`, `high`, `full`, `auto`, or a number 1–100 / 102 |
| `swing`                  | `off`, `vertical`, `horizontal`, `both`                                    |
| `preset`                 | `none`, `comfort`, `eco`, `boost`, `sleep`, `away`                         |
| `powerSaving`            | boolean (exclusive with the presets)                                       |
| `auxHeating`, `dry`, `smartEye`, `naturalWind`, `anion`, `fahrenheit` | boolean                       |
| `screenDisplay`          | boolean, LED display                                                       |
| `screenDisplayAlternate` | boolean, display on units using the newer protocol                         |
| `breezeless`, `indirectWind`, `outdoorSilent`, `sound`, `selfClean` | boolean                         |
| `horizontalVane`         | `off`, `left`, `left_mid`, `middle`, `right_mid`, `right`                  |
| `verticalVane`           | `off`, `up`, `up_mid`, `middle`, `down_mid`, `down`                        |
| `rateSelect`             | power limit: 1, 20, 40, 60, 80, 100 (or 50, 75, 100 on two-level units)   |
| `freshAir`               | boolean, fresh air module                                                  |
| `freshAirFanSpeed`       | 0 to 100                                                                   |

Not every unit has every feature; check `capabilities` first. In a combined command `power` is applied last, so
`{"power": false, "mode": "heat"}` changes the mode and leaves the unit off.

The whole command is validated before anything is sent: an unknown key or an invalid value rejects it and nothing
reaches the unit. Errors are published to `<base>/<device>/error`:

```json
{"topic":"midea2mqtt/livingroom/set","payload":"{\"mode\":\"turbo\"}","error":"'mode': 'turbo' is not one of auto, cool, dry, heat, fan_only"}
```

The new state is published as soon as the unit confirms a command.

---

## Logging

Output goes through `java.util.logging`. To see every frame exchanged with the units, raise the level of the
`de.mirranet.midea` logger, e.g. with a `logging.properties`:

```properties
handlers=java.util.logging.ConsoleHandler
java.util.logging.ConsoleHandler.level=FINEST
de.mirranet.midea.level=FINEST
```

```bash
java -Djava.util.logging.config.file=logging.properties -jar target/midea2mqtt.jar
```

---

## Project Structure

```
midea2mqtt/
├── src/main/java/de/midea2mqtt/
│   ├── MideaApp.java              # entry point, wires MQTT and devices, CLI pass-through
│   ├── Configuration.java         # config.yml
│   ├── mqtt/MqttManager.java      # broker connection, topics, last will, command routing
│   └── device/
│       ├── DeviceSettings.java    # one device entry from config.yml
│       ├── DeviceBridge.java      # one unit: polling -> state topic, set topic -> commands
│       ├── StateMapper.java       # AcState / Capabilities -> JSON
│       └── CommandMapper.java     # JSON -> API calls, validation
├── config.yml                     # auto-generated on first run
└── pom.xml
```

---

## Related Projects

- [midea-ac](https://github.com/LinzN/midea-ac) – the local Midea AC API this bridge is built on
- [eufymake2mqtt](https://github.com/LinzN/eufymake2mqtt) – the same idea for eufyMake printers
- [STEM](https://github.com/LinzN/Stem) – Smart Technology Framework for Enhanced Home Management

---

## Author

**LinzN** – [GitHub](https://github.com/LinzN)

---

## Disclaimer

This project is not affiliated with or endorsed by Midea. Midea, MSmartHome and NetHome Plus are trademarks of their
owners. Use at your own risk.
