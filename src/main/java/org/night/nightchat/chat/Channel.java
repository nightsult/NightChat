package org.night.nightchat.chat;

import java.util.*;

public class Channel {
    public final String id;
    public final ChannelType type;
    public final Set<String> commands = new LinkedHashSet<>();
    public final String permission;

    public final double radius;
    public final double delaySeconds;
    public final boolean mentionable;
    public final boolean highlight;
    public final boolean preventCapslock;

    public final boolean currencyEnabled;
    public final String currencyId;
    public final double minBalance;
    public final double messageCost;
    public final boolean showMessageCost;

    public final String format;   // string única (já resolvida da lista do toml)
    public final String spyFormat;

    // Novo: tags por canal
    public final Map<String, TagDefinition> tags; // id -> def

    public Channel(String id, ChannelType type, String permission,
                   double radius, double delaySeconds, boolean mentionable, boolean highlight, boolean preventCapslock,
                   boolean currencyEnabled, String currencyId, double minBalance, double messageCost, boolean showMessageCost,
                   String format, String spyFormat, Collection<String> commands, Map<String, TagDefinition> tags) {
        this.id = id;
        this.type = type;
        this.permission = permission;
        this.radius = radius;
        this.delaySeconds = delaySeconds;
        this.mentionable = mentionable;
        this.highlight = highlight;
        this.preventCapslock = preventCapslock;
        this.currencyEnabled = currencyEnabled;
        this.currencyId = currencyId;
        this.minBalance = minBalance;
        this.messageCost = messageCost;
        this.showMessageCost = showMessageCost;
        this.format = format;
        this.spyFormat = spyFormat;
        if (commands != null) this.commands.addAll(commands);
        this.tags = tags == null ? Map.of() : new LinkedHashMap<>(tags);
    }

    public TagDefinition getTag(String id) {
        if (id == null) return null;
        return tags.get(id.toLowerCase(Locale.ROOT));
    }
}