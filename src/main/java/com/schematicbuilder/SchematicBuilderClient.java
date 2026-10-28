package com.schematicbuilder;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.util.*;

/**
 * Schematic-Builder: laeuft selbststaendig zu fehlenden Bloecken, platziert sie per Rechtsklick,
 * erkennt Gefahren (Lava, Feuer, Abgruende), schleicht an Kanten und loggt sich aus, wenn man Schaden nimmt.
 */
public class SchematicBuilderClient implements ClientModInitializer {

    // ---- Einstellungen ----
    public static int perTick = 2;              // Platzierungen pro Tick
    public static double reach = 4.0;           // Reichweite
    public static boolean logoutOnHit = true;   // bei Schaden vom Server trennen
    public static boolean autoWalk = true;      // selbststaendig laufen

    // ---- Zustand ----
    private static KeyMapping openKey;
    private static List<LitematicReader.Entry> queue;
    private static boolean[] done;
    private static int[] attempts, nextTry;
    private static int head, remaining, tick, lastPlace, targetIdx, giveUps, noTargetTicks, lastProgress;
    private static double bestDist;
    private static float lastHealth;
    private static BlockPos origin;
    private static final Map<String, BlockState> cache = new HashMap<>();
    private static final Set<String> reportedMissing = new HashSet<>();
    private static int lastCooling, lastMissing;

    // Tasten, die die Mod steuert
    private static boolean fUp, fJump, fSneak;
    private static final boolean[] applied = new boolean[3];

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("schematicbuilder", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.schematicbuilder.open", InputConstants.Type.KEYSYM, InputConstants.KEY_K, category));

        // Tasten ganz am Tick-Anfang setzen, damit sie in diesem Tick wirken
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            boolean active = queue != null && client.screen == null && client.player != null;
            apply(client.options.keyUp, active && fUp, 0);
            apply(client.options.keyJump, active && fJump, 1);
            apply(client.options.keyShift, active && fSneak, 2);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (client.player != null) client.setScreen(new SchematicScreen());
            }
            tickBuild(client);
        });
    }

    private static void apply(KeyMapping k, boolean want, int idx) {
        if (want) { k.setDown(true); applied[idx] = true; }
        else if (applied[idx]) { k.setDown(false); applied[idx] = false; }
    }

    // ---------------------------------------------------------------- Steuerung

    public static void start(File file) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        try {
            queue = LitematicReader.read(file);
            int n = queue.size();
            done = new boolean[n];
            attempts = new int[n];
            nextTry = new int[n];
            head = 0; remaining = n; tick = 0; lastPlace = -100;
            targetIdx = -1; giveUps = 0; noTargetTicks = 0; lastHealth = -1f;
            origin = mc.player.blockPosition();
            reportedMissing.clear();
            msg(n + " Bloecke ab " + origin.toShortString() + (autoWalk ? ". Ich laufe selbst zu den Bloecken." : "."));
        } catch (Exception e) {
            queue = null;
            msg("Fehler beim Lesen: " + e);
        }
    }

    public static void stop() {
        if (queue != null) msg("Gestoppt, noch offen: " + remaining + "/" + queue.size());
        halt();
    }

    private static void halt() { queue = null; fUp = fJump = fSneak = false; }

    private static void logout(Minecraft mc, String reason) {
        halt();
        if (mc.getConnection() != null)
            mc.getConnection().getConnection().disconnect(Component.literal("[SchematicBuilder] " + reason));
    }

    // ---------------------------------------------------------------- Haupt-Tick

    private static void tickBuild(Minecraft mc) {
        fUp = false; fJump = false;
        if (queue == null) { fSneak = false; return; }
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        if (p == null || lvl == null || mc.gameMode == null) { halt(); return; }
        if (mc.screen != null) return; // GUI offen -> pausieren

        // Schaden -> ausloggen
        float hp = p.getHealth() + p.getAbsorptionAmount();
        if (logoutOnHit && lastHealth >= 0 && hp < lastHealth - 0.01f) {
            logout(mc, "Schaden erhalten - ausgeloggt");
            return;
        }
        lastHealth = hp;
        if (p.isDeadOrDying()) { halt(); return; }
        if (p.isOnFire() || p.isInLava()) { msg("Feuer/Lava - gestoppt!"); halt(); return; }

        tick++;
        boolean prevSneak = fSneak;
        fSneak = false;

        Vec3 eye = p.getEyePosition();
        double look2 = (reach + 0.9) * (reach + 0.9);
        int placed = 0;
        boolean readyFound = false, blockedByPlayer = false;
        Vec3 blockerCenter = null;

        while (head < queue.size() && done[head]) head++;

        for (int i = head; i < queue.size() && placed < perTick; i++) {
            if (done[i] || nextTry[i] > tick) continue;
            LitematicReader.Entry e = queue.get(i);
            BlockPos pos = origin.offset(e.x(), e.y(), e.z());
            if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > look2) continue;
            if (!lvl.hasChunkAt(pos)) continue;

            BlockState target = parse(e.state());
            if (target == null) { finish(i); continue; }
            BlockState cur = lvl.getBlockState(pos);
            if (cur.getBlock() == target.getBlock()) { finish(i); continue; }
            if (!(cur.isAir() || cur.canBeReplaced())) { finish(i); continue; }
            if (target.hasProperty(BlockStateProperties.SLAB_TYPE)
                    && target.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) { finish(i); continue; }
            Item item = target.getBlock().asItem();
            if (item == Items.AIR) { finish(i); continue; }

            if (p.getBoundingBox().intersects(new AABB(pos))) {   // Spieler steht im Weg
                blockedByPlayer = true;
                blockerCenter = Vec3.atCenterOf(pos);
                continue;
            }

            Direction bestFace = null;
            BlockPos bestNeighbor = null;
            int bestScore = -1;
            for (Direction d : Direction.values()) {
                BlockPos np = pos.relative(d);
                BlockState ns = lvl.getBlockState(np);
                if (ns.isAir() || ns.canBeReplaced() || !ns.getFluidState().isEmpty() || isInteractive(ns)) continue;
                Direction face = d.getOpposite();
                int score = faceScore(target, face);
                if (score > bestScore) { bestScore = score; bestFace = face; bestNeighbor = np; }
            }
            if (bestFace == null) continue;

            Vec3 hit = hitVec(bestNeighbor, bestFace, target);
            if (eye.distanceToSqr(hit) > reach * reach) continue;

            if (!hasItem(p, item)) {
                reportMissing(item);
                nextTry[i] = tick + 40;
                continue;
            }
            ensureItem(mc, p, item);
            readyFound = true;

            // An Kanten schleichen: erst ducken, dann platzieren
            boolean edge = nearEdge(lvl, p.blockPosition());
            fSneak = edge;
            if (edge && !p.isShiftKeyDown()) return;

            Direction want = lookFor(target);
            if (want != null && p.getDirection() != want) {
                p.setYRot(want.toYRot());
                return; // platziert wird im naechsten Tick
            }

            if (++attempts[i] > 8) { finish(i); continue; }
            nextTry[i] = tick + 6;
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, bestFace, bestNeighbor, false));
            p.swing(InteractionHand.MAIN_HAND);
            placed++;
        }

        if (placed > 0) lastPlace = tick;
        if (!readyFound) fSneak = (tick - lastPlace < 8) && prevSneak;

        if (remaining <= 0) { msg("Fertig!"); halt(); return; }
        if (tick % 200 == 0) msg("Noch offen: " + remaining + "/" + queue.size());

        // Navigation, wenn gerade nichts zu platzieren ist
        if (autoWalk && placed == 0 && tick - lastPlace >= 8) {
            if (blockedByPlayer) stepAway(lvl, p, blockerCenter);
            else navigate(mc, p, lvl);
        }
    }

    private static void finish(int i) {
        if (!done[i]) { done[i] = true; remaining--; }
    }

    // ---------------------------------------------------------------- Navigation

    private static void giveUp() {
        if (targetIdx >= 0) nextTry[targetIdx] = tick + 400;
        targetIdx = -1;
        if (++giveUps > 40) {
            msg("Zu viele unerreichbare Bloecke - gestoppt. Offen: " + remaining);
            halt();
        }
    }

    private static void navigate(Minecraft mc, LocalPlayer p, ClientLevel lvl) {
        if (targetIdx < 0 || done[targetIdx] || nextTry[targetIdx] > tick) {
            targetIdx = pickTarget(p, lvl);
            bestDist = Double.MAX_VALUE;
            lastProgress = tick;
            if (targetIdx < 0) {
                if (lastCooling == 0 && ++noTargetTicks > 40) {
                    msg(lastMissing > 0
                            ? "Material fehlt (siehe Chat) - pausiert. Offen: " + remaining
                            : "Nichts mehr platzierbar (kein Halt zum Anklicken / unerreichbar). Offen: " + remaining);
                    halt();
                }
                return;
            }
            noTargetTicks = 0;
        }

        LitematicReader.Entry e = queue.get(targetIdx);
        Vec3 center = Vec3.atCenterOf(origin.offset(e.x(), e.y(), e.z()));
        Vec3 eye = p.getEyePosition();
        double d = eye.distanceTo(center);

        if (d < bestDist - 0.1) { bestDist = d; lastProgress = tick; }
        else if (tick - lastProgress > 80) { giveUp(); return; }
        if (d <= 3.2) return; // angekommen, Platzierung uebernimmt

        double dx = center.x - p.getX(), dz = center.z - p.getZ();
        double horiz = Math.hypot(dx, dz);
        if (horiz < 0.6) { giveUp(); return; } // Ziel genau ueber/unter uns
        double ux = dx / horiz, uz = dz / horiz;

        if (hazardAhead(lvl, p, ux, uz)) {
            msg("Gefahr voraus (Lava/Feuer/Abgrund) - ueberspringe Ziel.");
            giveUp();
            return;
        }
        walk(lvl, p, ux, uz);
    }

    private static void walk(ClientLevel lvl, LocalPlayer p, double ux, double uz) {
        p.setYRot((float) Math.toDegrees(Math.atan2(-ux, uz)));
        fUp = true;
        BlockPos front = BlockPos.containing(p.getX() + ux * 0.7, p.getY(), p.getZ() + uz * 0.7);
        boolean frontSolid = !lvl.getBlockState(front).getCollisionShape(lvl, front).isEmpty();
        fJump = p.onGround() && (frontSolid || p.horizontalCollision);
    }

    private static void stepAway(ClientLevel lvl, LocalPlayer p, Vec3 blocker) {
        double dx = p.getX() - blocker.x, dz = p.getZ() - blocker.z;
        double h = Math.hypot(dx, dz);
        if (h < 0.01) { dx = 1; dz = 0; h = 1; }
        double ux = dx / h, uz = dz / h;
        if (hazardAhead(lvl, p, ux, uz)) return;
        walk(lvl, p, ux, uz);
    }

    /** Naechstgelegenen platzierbaren, bezahlbaren und anklickbaren Block waehlen. */
    private static int pickTarget(LocalPlayer p, ClientLevel lvl) {
        Vec3 eye = p.getEyePosition();
        double best = Double.MAX_VALUE;
        int bi = -1;
        lastCooling = 0;
        lastMissing = 0;
        for (int i = head; i < queue.size(); i++) {
            if (done[i]) continue;
            if (nextTry[i] > tick) { lastCooling++; continue; }
            LitematicReader.Entry e = queue.get(i);
            BlockPos pos = origin.offset(e.x(), e.y(), e.z());
            double d2 = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (d2 >= best) continue;
            if (!lvl.hasChunkAt(pos)) continue;
            BlockState t = parse(e.state());
            if (t == null) { finish(i); continue; }
            BlockState cur = lvl.getBlockState(pos);
            if (cur.getBlock() == t.getBlock() || !(cur.isAir() || cur.canBeReplaced())) { finish(i); continue; }
            Item item = t.getBlock().asItem();
            if (item == Items.AIR) { finish(i); continue; }
            if (!hasItem(p, item)) { lastMissing++; reportMissing(item); continue; }
            if (!hasSupport(lvl, pos)) continue;
            best = d2;
            bi = i;
        }
        return bi;
    }

    private static boolean hasSupport(ClientLevel lvl, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState ns = lvl.getBlockState(pos.relative(d));
            if (!(ns.isAir() || ns.canBeReplaced() || !ns.getFluidState().isEmpty() || isInteractive(ns))) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- Gefahren

    private static boolean dangerous(BlockState s) {
        if (s.getFluidState().is(FluidTags.LAVA)) return true;
        String n = path(s);
        return n.equals("fire") || n.equals("soul_fire") || n.equals("magma_block") || n.equals("cactus")
                || n.contains("campfire") || n.equals("sweet_berry_bush") || n.equals("wither_rose")
                || n.equals("powder_snow") || n.equals("lava");
    }

    private static boolean collides(ClientLevel lvl, BlockPos b) {
        return !lvl.getBlockState(b).getCollisionShape(lvl, b).isEmpty();
    }

    /** Ist an dieser Fuss-Position etwas Gefaehrliches oder fehlt der Boden (Abgrund)? */
    private static boolean hazard(ClientLevel lvl, BlockPos feet) {
        for (int dy = 0; dy <= 1; dy++) if (dangerous(lvl.getBlockState(feet.above(dy)))) return true;
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos b = feet.below(dy);
            BlockState s = lvl.getBlockState(b);
            if (dangerous(s)) return true;
            if (collides(lvl, b) || !s.getFluidState().isEmpty()) return false; // Boden (oder Wasser) gefunden
        }
        return true; // mehr als 3 Bloecke Fall
    }

    private static boolean hazardAhead(ClientLevel lvl, LocalPlayer p, double ux, double uz) {
        for (double dist : new double[]{0.7, 1.5}) {
            BlockPos f = BlockPos.containing(p.getX() + ux * dist, p.getY(), p.getZ() + uz * dist);
            if (hazard(lvl, f)) return true;
        }
        return false;
    }

    /** Steht der Spieler direkt an einer Kante (Abgrund in einer Nachbarspalte)? */
    private static boolean nearEdge(ClientLevel lvl, BlockPos feet) {
        int[][] offs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] o : offs) {
            BlockPos c = feet.offset(o[0], 0, o[1]);
            boolean ground = false;
            for (int dy = 0; dy <= 2; dy++) if (collides(lvl, c.below(dy))) { ground = true; break; }
            if (!ground) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- Platzieren: Hilfsfunktionen

    private static BlockState parse(String s) {
        if (cache.containsKey(s)) return cache.get(s);
        BlockState st = null;
        try {
            st = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false).blockState();
        } catch (Exception ignored) {}
        cache.put(s, st);
        return st;
    }

    private static void reportMissing(Item item) {
        String key = BuiltInRegistries.ITEM.getKey(item).getPath();
        if (reportedMissing.add(key)) msg("Fehlt im Inventar: " + key);
    }

    private static boolean hasItem(LocalPlayer p, Item item) {
        if (p.getAbilities().instabuild) return true;
        Inventory inv = p.getInventory();
        for (int s = 0; s < 36; s++) if (inv.getItem(s).is(item)) return true;
        return false;
    }

    private static boolean ensureItem(Minecraft mc, LocalPlayer p, Item item) {
        Inventory inv = p.getInventory();
        int sel = inv.getSelectedSlot();
        if (inv.getItem(sel).is(item)) return true;
        for (int h = 0; h < 9; h++) {
            if (inv.getItem(h).is(item)) { inv.setSelectedSlot(h); return true; }
        }
        if (p.getAbilities().instabuild) {
            mc.gameMode.handleCreativeModeItemAdd(new ItemStack(item), 36 + sel);
            return true;
        }
        for (int s = 9; s < 36; s++) {
            if (inv.getItem(s).is(item)) {
                mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, s, sel, ClickType.SWAP, p);
                return true;
            }
        }
        return false;
    }

    private static String path(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
    }

    private static boolean isInteractive(BlockState s) {
        String n = path(s);
        for (String k : new String[]{"door", "button", "chest", "barrel", "furnace", "smoker", "table", "anvil",
                "gate", "lever", "shulker", "bed", "note_block", "repeater", "comparator", "dispenser",
                "dropper", "hopper", "loom", "grindstone", "stonecutter", "enchanting", "brewing", "beacon",
                "cake", "jukebox", "lectern", "cauldron", "composter", "bell", "sign", "candle"}) {
            if (n.contains(k)) return true;
        }
        return false;
    }

    private static boolean clickedFaceType(String n) {
        return n.contains("wall_") || n.contains("button") || n.contains("ladder") || n.contains("trapdoor");
    }

    private static boolean isTop(BlockState t) {
        return (t.hasProperty(BlockStateProperties.HALF) && t.getValue(BlockStateProperties.HALF) == Half.TOP)
                || (t.hasProperty(BlockStateProperties.SLAB_TYPE) && t.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.TOP);
    }

    private static boolean isBottom(BlockState t) {
        return (t.hasProperty(BlockStateProperties.HALF) && t.getValue(BlockStateProperties.HALF) == Half.BOTTOM)
                || (t.hasProperty(BlockStateProperties.SLAB_TYPE) && t.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.BOTTOM);
    }

    private static int faceScore(BlockState t, Direction face) {
        int s = 0;
        if (t.hasProperty(BlockStateProperties.AXIS) && face.getAxis() == t.getValue(BlockStateProperties.AXIS)) s += 3;
        if (t.hasProperty(BlockStateProperties.HORIZONTAL_AXIS) && face.getAxis() == t.getValue(BlockStateProperties.HORIZONTAL_AXIS)) s += 3;
        if (isTop(t)) s += face == Direction.DOWN ? 2 : face.getAxis().isHorizontal() ? 1 : 0;
        if (isBottom(t)) s += face == Direction.UP ? 2 : face.getAxis().isHorizontal() ? 1 : 0;
        if (t.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && clickedFaceType(path(t))
                && t.getValue(BlockStateProperties.HORIZONTAL_FACING) == face) s += 4;
        return s;
    }

    private static Vec3 hitVec(BlockPos neighbor, Direction face, BlockState target) {
        Vec3 c = Vec3.atCenterOf(neighbor);
        double y = c.y + face.getStepY() * 0.5;
        if (face.getAxis().isHorizontal()) y = neighbor.getY() + (isTop(target) ? 0.75 : 0.25);
        return new Vec3(c.x + face.getStepX() * 0.5, y, c.z + face.getStepZ() * 0.5);
    }

    private static Direction lookFor(BlockState t) {
        if (!t.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return null;
        String n = path(t);
        if (clickedFaceType(n)) return null;
        Direction f = t.getValue(BlockStateProperties.HORIZONTAL_FACING);
        boolean direct = n.contains("stairs") || n.contains("door") || n.contains("fence_gate") || n.contains("bed");
        return direct ? f : f.getOpposite();
    }

    private static void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[SchematicBuilder] " + s), false);
    }
}
