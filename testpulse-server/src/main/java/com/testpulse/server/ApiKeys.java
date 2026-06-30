package com.testpulse.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Maps API keys to user identities. Loaded once at startup from a CSV file:
 *
 * <pre>
 * apiKey,userId,displayName,role
 * k_abc123,jdoe,John Doe,qa
 * k_xyz789,bot,CI Bot,ci
 * </pre>
 *
 * <p>One header line, then one user per line. Lines starting with {@code #}
 * and blank lines are ignored. If the CSV is missing, the server still
 * starts but rejects every authenticated request — useful for first-run
 * diagnostics ("you forgot to set up keys" is clearer than "everything 500s").
 */
public final class ApiKeys {

    private static final Logger LOG = Logger.getLogger(ApiKeys.class.getName());

    private final Map<String, Principal> byKey;

    public ApiKeys(Path csv) {
        this.byKey = load(csv);
        LOG.info("Loaded " + byKey.size() + " API key(s) from " + csv.toAbsolutePath());
    }

    /**
     * Look up a key. Returns null if unknown — caller decides whether to
     * reject (almost always) or fall back.
     */
    public Principal lookup(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) return null;
        return byKey.get(apiKey);
    }

    public int count() {
        return byKey.size();
    }

    private static Map<String, Principal> load(Path csv) {
        if (!Files.exists(csv)) {
            LOG.warning("API keys file not found: " + csv.toAbsolutePath()
                    + " — server will reject all authenticated requests until it exists");
            return Collections.emptyMap();
        }

        Map<String, Principal> out = new HashMap<String, Principal>();
        try {
            List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            for (int lineNo = 0; lineNo < lines.size(); lineNo++) {
                String line = lines.get(lineNo).trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (lineNo == 0 && line.toLowerCase().startsWith("apikey")) continue; // header row

                String[] parts = line.split(",", -1);
                if (parts.length < 3) {
                    LOG.warning("Bad CSV line " + (lineNo + 1) + ": " + line);
                    continue;
                }
                String key = parts[0].trim();
                String userId = parts[1].trim();
                String displayName = parts[2].trim();
                String role = parts.length > 3 ? parts[3].trim() : "user";
                out.put(key, new Principal(userId, displayName, role));
            }
        } catch (IOException e) {
            LOG.warning("Failed to read API keys: " + e.getMessage());
        }
        return out;
    }

    public static final class Principal {
        public final String userId;
        public final String displayName;
        public final String role;

        public Principal(String userId, String displayName, String role) {
            this.userId = userId;
            this.displayName = displayName;
            this.role = role;
        }
    }
}
