package org.night.nightchat.persist;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PlayerState {
    public final Set<String> mutedChannels = new HashSet<>();
    public final Set<String> spyChannels = new HashSet<>();
    public final Set<UUID> mutedPlayers = new HashSet<>();
    public final Set<UUID> ignoredPlayers = new HashSet<>();

    public boolean isEmpty() {
        return mutedChannels.isEmpty() && spyChannels.isEmpty() && mutedPlayers.isEmpty() && ignoredPlayers.isEmpty();
    }
}