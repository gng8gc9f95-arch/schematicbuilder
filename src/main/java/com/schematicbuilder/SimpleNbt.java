package com.schematicbuilder;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Minimaler NBT-Leser (unabhaengig von Minecraft-Klassen, daher mapping-sicher). */
public class SimpleNbt {

    public static Map<String, Object> readGzip(File f) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(f))))) {
            int type = in.readUnsignedByte();
            if (type != 10) throw new IOException("Keine gueltige NBT-Datei");
            in.readUTF();
            return compound(in);
        }
    }

    private static Map<String, Object> compound(DataInputStream in) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        while (true) {
            int t = in.readUnsignedByte();
            if (t == 0) return m;
            String name = in.readUTF();
            m.put(name, payload(in, t));
        }
    }

    private static Object payload(DataInputStream in, int t) throws IOException {
        switch (t) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { byte[] a = new byte[in.readInt()]; in.readFully(a); return a; }
            case 8: return in.readUTF();
            case 9: {
                int et = in.readUnsignedByte(), n = in.readInt();
                List<Object> l = new ArrayList<>(Math.max(n, 0));
                for (int i = 0; i < n; i++) l.add(payload(in, et));
                return l;
            }
            case 10: return compound(in);
            case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
            case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("Unbekannter NBT-Typ " + t);
        }
    }
}
