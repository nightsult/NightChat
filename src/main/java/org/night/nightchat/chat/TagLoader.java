package org.night.nightchat.chat;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import org.night.nightchat.Nightchat;

import java.util.*;

public class TagLoader {

    public static Map<String, TagDefinition> load(CommentedFileConfig cfg) {
        Map<String, TagDefinition> map = new LinkedHashMap<>();
        loadArrayOfTables(cfg, "Tags", map);
        loadArrayOfTables(cfg, "Custom_Tags", map);
        return map;
    }

    @SuppressWarnings("unchecked")
    private static void loadArrayOfTables(CommentedFileConfig cfg, String tableName, Map<String, TagDefinition> out) {
        if (!cfg.contains(tableName)) return;
        Object v = cfg.get(tableName);
        if (!(v instanceof List<?> list)) return;

        for (Object o : list) {
            if (!(o instanceof UnmodifiableConfig uc)) continue;
            String id = optString(uc, "id", null);
            if (id == null || id.isBlank()) continue;
            TagDefinition def = new TagDefinition(id);

            // hover: pode ser string única ou lista de strings
            Object hover = uc.get("hover");
            if (hover instanceof String s) {
                def.hover.add(s);
            } else if (hover instanceof List<?> hl) {
                for (Object ho : hl) if (ho != null) def.hover.add(String.valueOf(ho));
            }

            // suggest (linhas extra no hover)
            Object suggest = uc.get("suggest");
            if (suggest instanceof String s) {
                def.suggest.add(s);
            } else if (suggest instanceof List<?> sl) {
                for (Object so : sl) if (so != null) def.suggest.add(String.valueOf(so));
            }

            // suggestCommand (lista, usaremos o primeiro comando)
            Object sc = uc.get("suggestCommand");
            if (sc instanceof String s) {
                def.suggestCommand.add(s);
            } else if (sc instanceof List<?> scl) {
                for (Object sco : scl) if (sco != null) def.suggestCommand.add(String.valueOf(sco));
            }

            String perm = optString(uc, "permission", null);
            if (perm != null && !perm.isBlank()) def.permission = perm;

            out.put(def.id, def);
        }
    }

    private static String optString(UnmodifiableConfig uc, String key, String def) {
        Object v = uc.get(key);
        return v == null ? def : String.valueOf(v);
    }
}