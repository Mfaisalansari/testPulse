package com.testpulse.cucumber5;

import io.cucumber.java.Scenario;

/**
 * Strategy for determining a scenario's LOB. Different teams identify a
 * scenario's LOB differently — by feature directory, by tag, by runner
 * class, by system property — so this is pluggable.
 *
 * <p>Default chain (see {@link LobResolvers#defaultChain()}):
 * <ol>
 *   <li>{@code @lob:X} tag on the scenario or its feature</li>
 *   <li>{@link com.testpulse.LobContext#get()} (set by testpulse-testng's BaseRunner)</li>
 *   <li>{@code -Dtestpulse.lob} system property</li>
 *   <li>{@code null}</li>
 * </ol>
 *
 * <p>Consumers override via {@link Cucumber5Config#setLobResolver}.
 */
public interface LobResolver {
    String resolve(Scenario scenario);
}
