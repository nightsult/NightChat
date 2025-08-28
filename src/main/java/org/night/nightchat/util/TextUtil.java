package org.night.nightchat.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

public class TextUtil {
    // Converte códigos de cor estilo &a para Component
    public static Component legacyToComponent(String legacy) {
        // Implementação simples: substitui & por § e deixa o Minecraft processar, ou
        // constrói manualmente. Aqui, convertemos &a etc. em formatação básica.
        String withSection = legacy.replace('&', '§');
        return Component.literal(withSection).withStyle(Style.EMPTY);
    }
}