// SPDX-License-Identifier: Apache-2.0
package ca.ponderosa.jackolantern;

import java.util.Map;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/** A voice skin: a jack-o'-lantern that listens, thinks and talks. */
public final class JackOLanternPlugin implements KioskPlugin {
    private final SkinController skin = new SkinController();

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        skin.start(host);
        skin.configure(settings);
    }

    @Override
    public synchronized void configure(Map<String, Object> settings) {
        skin.configure(settings);
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        throw new IllegalArgumentException("Unknown command: " + command);
    }

    @Override
    public synchronized void onEvent(String event, Map<String, Object> payload) {
        skin.onEvent(event, payload);
    }

    @Override
    public synchronized void stop() {
        skin.stop();
    }
}
