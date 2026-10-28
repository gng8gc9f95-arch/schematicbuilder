# Schematic Builder - Auto-Version (Fabric, Minecraft 1.21.11)

Laeuft selbststaendig zu fehlenden Bloecken und platziert sie per Rechtsklick.

## Bauen
JDK 21 + Gradle 9.x:  `gradle build`  ->  build/libs/schematicbuilder-1.0.0.jar
(Jar + Fabric API 1.21.11 in .minecraft/mods/)

## Benutzen
1. .litematic in `.minecraft/schematics/`.
2. `K` -> Schematic anklicken. Ursprung = deine aktuelle Position.
3. Die Mod geht zum naechsten platzierbaren Block, platziert alles in Reichweite und macht weiter.
   Material aus Hotbar/Inventar (Kreativ: gratis). Fehlendes Material wird im Chat gemeldet.
4. Stop: `K` -> Stop. Bei offener GUI pausiert sie.

## Sicherheit
- Gefahrenerkennung vor jedem Schritt: Lava, Feuer, Magma, Kaktus, Lagerfeuer, Beeren, Pulverschnee,
  Abgruende (>3 Bloecke Fall). Betroffene Bloecke werden uebersprungen.
- An Kanten (Abgrund in Nachbarspalte) wird vor dem Platzieren geduckt (Sneak).
- Nimmt der Spieler Schaden (Gesundheit sinkt), wird die Verbindung zum Server getrennt.
  Abschalten: logoutOnHit = false. Auto-Laufen abschalten: autoWalk = false.

## Grenzen
- Kein Wegfinden: laeuft geradlinig, springt ueber 1-Block-Hindernisse. Haengt sie fest, ueberspringt
  sie das Ziel nach ~4 s. Hohe Waende (ueber Reichweite) erreicht sie nicht (kein Hochbauen/Pillar).
- Bloecke brauchen einen Nachbarblock zum Anklicken.
- Ausrichtung von Treppen/Platten/Achsen ist angenaehert.
- Auf Multiplayer-Servern meist verboten (Bot/Printer) - vorher Regeln pruefen.
