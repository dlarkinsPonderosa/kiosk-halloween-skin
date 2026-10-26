# Jack-o'-Lantern

A Halloween voice skin for [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite). While it is picked, the assistant is a scary jack-o'-lantern instead of the usual overlay.

| In the dark (default) | Whole pumpkin |
| --- | --- |
| ![The In the dark style: only the candlelit eyes, nose and jagged mouth glowing on black](assets/voice-skin.png) | ![The Whole pumpkin style: an orange pumpkin with stem, its carved face lit from inside](docs/whole-pumpkin.png) |

- **Listening:** the candle flickers hard and the face slowly pushes in and cocks its head, as if hanging on every word. Your voice makes the flame flare.
- **Thinking:** it ponders. The eyes glance up and to the side, one squints, the mouth tightens and the nose spins like a loading spinner while the light pulses.
- **Answering:** it settles back to face you and its jagged mouth opens and closes with the answer.

What you said and the answer show under the face, as with the built-in skins. Docked overlay mode shows a small pumpkin beside the conversation.

## Requirements

- Kiosk Satellite 2026.10.30 or later (the first release with plugin voice skins)
- The native Voice Satellite runtime turned on. Skins draw only its turns.

## Installation

### Community Plugins (Recommended)

Jack-o'-Lantern is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=dlarkinsPonderosa/kiosk-halloween-skin)

Or search for `Jack-o'-Lantern` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/dlarkinsPonderosa/kiosk-halloween-skin` and choose **Preview**.
3. Review the plugin and choose **Trust and install**, then enable it.

### Pick the skin

Open **Settings > Voice Satellite > Appearance > Skin** and pick **Jack-o'-Lantern**. **Preview** shows it for five seconds.

When Halloween is over, pick your usual skin again.

## Settings

| Setting | What it does |
| --- | --- |
| Pumpkin style | **In the dark** (default) shows only the candlelit eyes, nose and mouth on black. **Whole pumpkin** draws the orange shell and stem too. |
| Animation quality | **Auto** (default) starts with the full glow and switches to a lighter one, then to 30 frames a second, if the device can't keep up. **High** always draws the full glow. **Low** always draws the lighter glow at 30 frames a second; pick it for slow devices such as the Echo Show 5. **Off** draws no glow. |

The skin also follows **Voice Satellite > Appearance**: Theme and Background apply to Whole pumpkin, Text size scales the conversation, and with Reactive activity bar off nothing moves with the voice.

## Data

The plugin reads only the kiosk's own Voice Satellite state: whether its skin is picked, the voice level, the phase of the turn and the words shown on screen. It sends nothing anywhere and does not talk to Home Assistant.

## Build

GitHub Actions builds a test ZIP on every push (**Actions > Build**) for **Plugin Manager > Developer Tools > Install from ZIP**. Publishing a release tagged with the version, such as `v1.0.0`, runs **Release**, which attaches the files Kiosk Satellite installs.

To build locally: Python 3, JDK 17 or newer and an Android SDK with `platforms;android-35` and `build-tools;35.0.0`, then `python3 tools/build.py --android-platform 35`.

`tools/render_art.py` redraws the icon, the Skin picker thumbnail and the README pictures from the same geometry as `Pumpkin.java`.

The SDK under `sdk/` and the build tools under `tools/` come from [kiosk-satellite-plugins](https://github.com/jxlarrea/kiosk-satellite-plugins) (Apache-2.0).

## License

[Apache-2.0](LICENSE)
