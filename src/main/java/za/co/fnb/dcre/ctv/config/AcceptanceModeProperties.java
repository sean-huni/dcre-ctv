package za.co.fnb.dcre.ctv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * R-41 per-client acceptance mode. The client token is the header's InitgPty;
 * unmapped clients fall to the default. Binding is directly to the enum, so an
 * unknown mode value fails startup (fail closed).
 */
@ConfigurationProperties(prefix = "dcre.ctv.acceptance-mode")
public class AcceptanceModeProperties {

    public enum Mode { ALL_OR_NOTHING, PARTIAL }

    /** R-41 default: whole-file rejection on any business FAIL. */
    private Mode defaultMode = Mode.ALL_OR_NOTHING;
    private Map<String, Mode> clients = new HashMap<>();

    public Mode getDefault() {
        return defaultMode;
    }

    public void setDefault(Mode defaultMode) {
        this.defaultMode = defaultMode;
    }

    public Map<String, Mode> getClients() {
        return clients;
    }

    public void setClients(Map<String, Mode> clients) {
        this.clients = clients;
    }

    public Mode modeFor(String clientToken) {
        if (clientToken == null || clientToken.isBlank()) {
            return defaultMode;
        }
        return clients.getOrDefault(clientToken.strip(), defaultMode);
    }
}
