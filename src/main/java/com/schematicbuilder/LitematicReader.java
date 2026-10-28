package com.schematicbuilder;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Liest .litematic-Dateien (Litematica-Format) ohne Abhaengigkeit von Litematica. */
public class LitematicReader {

    public record Entry(int x, int y, int z, String state) {}

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }

    private static int i(Map<String, Object> m, String k) { return ((Number) m.get(k)).intValue(); }

    public static List<Entry> read(File file) throws IOException {
        Map<String, Object> root = SimpleNbt.readGzip(file);
        Map<String, Object> regions = map(root.get("Regions"));
        List<Entry> out = new ArrayList<>();

        for (Object regionObj : regions.values()) {
            Map<String, Object> region = map(regionObj);
            Map<String, Object> size = map(region.get("Size"));
            Map<String, Object> pos = map(region.get("Position"));
            int sx = i(size, "x"), sy = i(size, "y"), sz = i(size, "z");
            int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            int bx = i(pos, "x") + (sx < 0 ? sx + 1 : 0);
            int by = i(pos, "y") + (sy < 0 ? sy + 1 : 0);
            int bz = i(pos, "z") + (sz < 0 ? sz + 1 : 0);

            List<Object> paletteNbt = (List<Object>) region.get("BlockStatePalette");
            String[] palette = new String[paletteNbt.size()];
            boolean[] air = new boolean[palette.length];
            for (int p = 0; p < palette.length; p++) {
                Map<String, Object> e = map(paletteNbt.get(p));
                String n = (String) e.get("Name");
                StringBuilder sb = new StringBuilder(n);
                if (e.containsKey("Properties")) {
                    Map<String, Object> props = map(e.get("Properties"));
                    if (!props.isEmpty()) {
                        sb.append('[');
                        boolean first = true;
                        for (Map.Entry<String, Object> pe : props.entrySet()) {
                            if (!first) sb.append(',');
                            sb.append(pe.getKey()).append('=').append(pe.getValue());
                            first = false;
                        }
                        sb.append(']');
                    }
                }
                palette[p] = sb.toString();
                air[p] = n.equals("minecraft:air") || n.equals("minecraft:cave_air") || n.equals("minecraft:void_air");
            }

            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
            long mask = (1L << bits) - 1L;
            long[] data = (long[]) region.get("BlockStates");

            for (int y = 0; y < ay; y++)
                for (int z = 0; z < az; z++)
                    for (int x = 0; x < ax; x++) {
                        long index = ((long) y * az + z) * ax + x;
                        long start = index * bits;
                        int i0 = (int) (start >>> 6);
                        int i1 = (int) ((start + bits - 1) >>> 6);
                        int off = (int) (start & 63);
                        long v = (i0 == i1)
                                ? (data[i0] >>> off) & mask
                                : ((data[i0] >>> off) | (data[i1] << (64 - off))) & mask;
                        int id = (int) v;
                        if (id >= palette.length || air[id]) continue;
                        out.add(new Entry(bx + x, by + y, bz + z, palette[id]));
                    }
        }
        // Von unten nach oben, damit nichts herunterfaellt
        out.sort(Comparator.comparingInt(Entry::y).thenComparingInt(Entry::z).thenComparingInt(Entry::x));
        return out;
    }
}
