# Vorreservierung von Empfangssequenzen

Validiert am 18. November 2026 auf einem Apple M1 Max mit 32 GiB RAM,
Temurin 19.0.2, G1 und maximal 12 GiB Java-Heap.

## Umfang der Änderung

`CdrCodecs.primitiveSequence` reserviert ArrayList-basierten Ergebnissen nach
Prüfung der Eingabelänge und des Recovery-Limits Platz für die tatsächlich
behaltenen Elemente. Die angegebene Factory wird weiter verwendet; andere
Listenimplementierungen bleiben auf dem bisherigen Pfad.

Die Änderung betrifft primitive Sequenzen, einschließlich solcher innerhalb
benutzerdefinierter Strukturen. Sequenzen aus Strukturen reservierten ihre
Ergebnisliste bereits mit `new ArrayList<>(limit)`. Feste Arrays werden bereits
mit ihrer endgültigen Länge angelegt. Für diese äußeren Container ist deshalb
keine zusätzliche Vorreservierung erforderlich.

## Isolierte Dekodiermessung

Drei frische Prozesse pro Größe und Variante, wechselnde Reihenfolge, identisches
Runtime-JAR. Für den bisherigen Stand wurde ausschließlich der unveränderte
HEAD-Codec vor das Runtime-JAR in den Classpath gestellt. Angegeben ist der Median
der Prozessmittelwerte. Vollständige Inhaltsprüfung außerhalb der Zeitmessung.
Allokationen sind kumulierte Java-Allokationen je Nachricht, keine Heap-Peaks.

| Nutzdaten | Vorher | Nachher | Java-Allokation vorher → nachher |
|---|---:|---:|---:|
| 164 KiB | 0,917 ms | 0,873 ms | 2,882 → 0,672 MB |
| 200,01 MB | 4.139,1 ms | 1.551,6 ms | 2.838,7 → 800,0 MB |

Bei 164 KiB wurden je Prozess 200 Aufwärm- und 500 Messnachrichten verarbeitet;
bei 200,01 MB eine Aufwärm- und drei Messnachrichten. Die 200-MB-Prozessmittel
streuten vorher zwischen 3,13 und 4,29 s und nachher zwischen 1,54 und 1,56 s.
Kurze Aufwärmphasen garantieren keinen JVM-Steady-State.

## Verschachtelte Custom-Strukturen

Der generierte Testtyp `ComplexCollections` kombiniert:

- ein festes Array aus zwei `PayloadGroup`-Strukturen;
- je Gruppe ein festes Array aus drei `PayloadEntry`-Strukturen sowie eine
  begrenzte Sequenz weiterer Einträge;
- eine zusätzliche begrenzte Sequenz von Gruppen;
- je Eintrag eine Position mit zwei doubles, eine UTF-8-Zeichenkette und eine
  Octet-Sequenz mit 41 oder 164 KiB;
- ein nachfolgendes Kontrollfeld.

Die Testnachricht enthält 20 Einträge mit insgesamt 1.847.296 Nutzdatenbytes;
ihre XCDR1-Darstellung ist 1.848.248 Byte groß.

Eine zusätzliche reine Dekodiermessung mit je drei frischen Prozessen,
zehn Aufwärm- und 30 Messnachrichten pro Prozess ergab:

| Komplexe Nachricht | Vorher | Nachher |
|---|---:|---:|
| Median der Prozessmittel | 11,964 ms | 9,612 ms |
| Java-Allokation je Nachricht | 29,903 MB | 7,406 MB |

Das sind ungefähr 20 % weniger Dekodierzeit und 75 % weniger Allokationen.
Hier profitieren die primitiven Nutzdatensequenzen innerhalb der Strukturen;
die äußeren Struct-Arrays und Struct-Sequenzen waren bereits vorreserviert.
Dies ist kein Nachweis einer Beschleunigung reiner Struct-Arrays ohne solche
primitiven Sequenzen und keine DDS-End-to-End-Zeit.

## Korrektheit und DDS

20 ausgewählte CTest-Fälle erfolgreich: `cdr`, neun `upstream-cdr-*`-Fälle,
`upstream-bounds-sequence`, `generated-types`, `complex-collections`,
`annotations`, `c-interop`, `collections-interop`, `structured-interop`,
`broader-idl-interop`, `xcdr1-broader-interop` und `xcdr1`.

Die ergänzten CDR-Tests prüfen leere und große primitive Sequenzen,
Sequenzgrenzen, TRIM/USE_DEFAULT mit nachfolgendem Feld, eine eigene
LinkedList-Factory und ungültige Längen vor Konstruktion einer Ergebnisliste.

`ComplexCollectionsTest` prüft sämtliche Felder und Nutzdatenbytes in XCDR1 und
XCDR2, jeweils mit beiden Byte-Reihenfolgen. Er prüft zudem feste Arraygrößen,
abgeschnittene Daten und unabhängige dekodierte Objektgraphen. Zwei aufeinander
folgende DDS-Schreib-/Lesevorgänge prüfen, dass geänderte Sendedaten und ein
späterer Take bereits zurückgegebene Empfangsdaten nicht überschreiben.

Ein separater 1-GB-DDS-Diagnoseprozess mit einer Aufwärm- und drei Messnachrichten
war mit 12 GiB Heap erfolgreich. Alle vier Nachrichten wurden vollständig
geprüft. Schreiben plus Empfangen dauerte im Mittel 12,29 s pro Messnachricht.
Die früheren fünf Versuche ohne Vorreservierung waren bei diesem Heap-Limit
fehlgeschlagen. Der erfolgreiche Einzellauf belegt weder den minimal nötigen
Heap noch eine stabile Performance-Serie. Das 8-GiB-Ziel und ähnliche
Gesamtperformance wie C# sind damit noch nicht nachgewiesen.

## Lokale Diagnoseartefakte

Die umfangreichen Rohdaten, Messprogramme und früheren Profile liegen lokal in
`benchmarks/presize-step1-20261118` und `benchmarks/java-analysis-20261118`.
Diese Verzeichnisse sind bewusst nicht versioniert. Die funktionalen
Regressionstests und ihre IDL-Typen liegen dagegen im regulären Testbaum und
werden durch CMake/CTest gebaut und ausgeführt.
