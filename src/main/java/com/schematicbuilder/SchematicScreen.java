package com.schematicbuilder;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

public class SchematicScreen extends Screen {
    private static final int PER_PAGE = 7;
    private File[] files = new File[0];
    private int page = 0;

    public SchematicScreen() { super(Component.literal("Schematic Builder")); }

    @Override
    protected void init() {
        File dir = FabricLoader.getInstance().getGameDir().resolve("schematics").toFile();
        File[] found = dir.listFiles((d, n) -> n.endsWith(".litematic"));
        files = found == null ? new File[0] : found;
        Arrays.sort(files, Comparator.comparing(File::getName));

        int w = 220, x = width / 2 - w / 2, y = 35;
        int from = page * PER_PAGE;
        for (int i = from; i < Math.min(files.length, from + PER_PAGE); i++) {
            File f = files[i];
            addRenderableWidget(Button.builder(Component.literal(f.getName().replace(".litematic", "")), b -> {
                SchematicBuilderClient.start(f);
                onClose();
            }).bounds(x, y, w, 20).build());
            y += 24;
        }
        int by = height - 30;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { if (page > 0) { page--; rebuildWidgets(); } })
                .bounds(x, by, 40, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Stop"), b -> { SchematicBuilderClient.stop(); onClose(); })
                .bounds(x + 50, by, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Schliessen"), b -> onClose())
                .bounds(x + 115, by, 65, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            if ((page + 1) * PER_PAGE < files.length) { page++; rebuildWidgets(); }
        }).bounds(x + 180, by, 40, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(font, Component.literal("Schematic waehlen - baut an deiner Position"), width / 2, 12, 0xFFFFFFFF);
        if (files.length == 0)
            g.drawCenteredString(font, Component.literal("Keine .litematic im Ordner 'schematics' gefunden"), width / 2, 60, 0xFFFF5555);
    }
}
