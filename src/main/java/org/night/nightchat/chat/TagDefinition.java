package org.night.nightchat.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TagDefinition {
    public final String id;
    public final List<String> hover = new ArrayList<>();
    public final List<String> suggest = new ArrayList<>();
    public final List<String> suggestCommand = new ArrayList<>();
    public String permission; // opcional

    public TagDefinition(String id) {
        this.id = Objects.requireNonNull(id).toLowerCase();
    }
}