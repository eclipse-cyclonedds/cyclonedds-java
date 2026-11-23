# Kompakte Byte-Sequenzen und CDR-Blocktransfers

Stand: 18. November 2026.

## Umsetzung

`PackedByteSequence` implementiert die bestehende `ByteSeq`-/`List<Byte>`-API
mit einem eigenen `byte[]`. Der IDL-Generator verwendet diese Klasse verbindlich
für byte-gemappte Sequenzen, sowohl für Standardwerte als auch Empfangsfabriken.
Es gibt keine Legacy-Generatoroption.

Der Byte-Codec erkennt die kompakte Implementierung und kopiert die Elemente
blockweise. Die Eingabelänge und das Recovery-Limit werden vor der Reservierung
geprüft. TRIM und USE_DEFAULT konsumieren weiterhin alle notwendigen
Eingabebytes, auch wenn nur ein Teil gespeichert wird. Benutzerdefinierte
Factories werden beachtet und andere Listen bleiben auf dem generischen Pfad.

Die bestehende `ByteSequence` bleibt verwendbar. Code arbeitet mit `ByteSeq`
statt mit einem ArrayList-Downcast. Bereits generierte Typen können weiter ihre
bisherige Factory benutzen; Neugenerierung wählt die kompakte Empfangsdarstellung.

Der neue Typ unterstützt List-Operationen, Iteratoren, Sublisten, Selbst-Einfügen,
primitive Bereichsoperationen und Serialisierung. Bei Einfügen eines null-Werts
wechselt die betreffende Instanz auf boxed Speicher, um List-Semantik zu erhalten;
CDR lehnt null-Elemente weiterhin ab. Konstruktoren und Transfers kopieren die
Nutzdaten in eigenen Speicher; es wird kein veränderbares internes Array nach
außen gegeben. Java-Serialisierung schreibt nur logische Inhalte, keine ungenutzte
Kapazität.

## Neuer Vergleich mit C#

Apple M1 Max, 32 GiB RAM, Temurin 19.0.2 und .NET 9.0.9. Beide Sprachen verwenden
dieselbe native Cyclone-DDS-Binärdatei. Reliable/KeepAll/XCDR1, lokale Zustellung
im selben Prozess und unabhängige Empfangsdaten. Java verwendet maximal 8 GiB
Heap. Gemessen wird Write bis zum vollständig erzeugten Empfangsobjekt;
vollständige Inhalts- und Counterprüfung außerhalb der Zeitmessung.

Fünf frische Prozesse pro Sprache und Größe, wechselnde Reihenfolge. Angegeben
ist der Median der fünf Prozessmittelwerte. Aufwärm-/Messnachrichten entsprechen
dem ursprünglichen Vergleich: 3.000/10.000 bei 41 KiB, 1.000/3.000 bei 164 KiB,
3/5 bei 200,01 MB und 1/3 bei 1 GB. Die kurzen großen Aufwärmreihen garantieren
keinen JVM-Steady-State. Hintergrundlast und thermischer Zustand sind nicht
kontrolliert.

| Nutzdaten je Nachricht | Java | C# | Java/C# |
|---|---:|---:|---:|
| 41,0 KiB | 0,0361 ms | 0,00752 ms | 4,81× |
| 164,0 KiB | 0,0630 ms | 0,0347 ms | 1,82× |
| 200,01 MB | 110,99 ms | 35,19 ms | 3,15× |
| 1 GB | 623,45 ms | 172,44 ms | 3,62× |

Die 1-GB-Nachricht funktionierte in allen fünf Java-Prozessen mit 8 GiB Heap;
alle 20 Aufwärm-/Messnachrichten bestanden die vollständige Datenprüfung. Ein
vorheriger separater Vorlauf mit 12 GiB Heap war ebenfalls erfolgreich.

Das Speicherziel für den 1-GB-Test ist damit erreicht. Das vorgeschlagene Zeitziel
von höchstens 1,5× C# ist noch bei keiner der vier Größen erreicht. Die weitere
Arbeit betrifft Schritt 3: verbleibende CDR-/JNI-Kopien und die dann erneut zu
profilierenden Kosten. Diese Messung bestimmt noch nicht den genauen Anteil
jeder verbleibenden Kopie oder des API-Aufwands.

## Speicherkennzahlen

| Nutzdaten | Java-Allokation je Write+Take | Java Prozess-RSS | C# Prozess-RSS |
|---|---:|---:|---:|
| 41 KiB | 0,127 MB | 401,8 MB | 63,3 MB |
| 164 KiB | 0,506 MB | 389,6 MB | 59,5 MB |
| 200,01 MB | 800,0 MB | 1.418,0 MB | 1.486,0 MB |
| 1 GB | 4.000,0 MB | 7.042,5 MB | 6.287,3 MB |

Dezimale MB. Java-Allokationen sind die auf dem Anwendungs-Thread allokierten
Bytes innerhalb des gemessenen Write+Take, keine gleichzeitig lebenden Bytes.
Bei großen Nachrichten sind das ungefähr vier Nutzdatenmengen: CDR-Puffer,
CDR-Ausgabe, empfangene CDR-Bytes und kompakte Empfangssequenz. Für kleine
Nachrichten wird der CDR-Puffer wiederverwendet.

RSS ist der Median des vom Betriebssystem gemeldeten Spitzenwerts des gesamten
Prozesses einschließlich Vorbereitung und Inhaltsprüfung. Er umfasst auch
nativen und JVM/.NET-Speicher und ist kein gemessener Heap-Mindestbedarf.
Die Messung weist kein Speicherproblem als gelöst aus, indem nur Heap-Bytes in
native Puffer verschoben werden; beide Prozesswerte sind deshalb mit aufgeführt.

## Verschachtelte Custom-Strukturen

Die Nachricht des `ComplexCollectionsTest` enthält feste Struct-Arrays,
begrenzte Struct-Sequenzen, Positionen, Strings und Byte-Sequenzen mit insgesamt
1.847.296 Nutzdatenbytes. Der Test verwendet beim Senden gemischt kompakte und
bestehende ByteSeq-Implementierungen; alle neu generierten Empfangssequenzen sind
kompakt.

Ein zusätzlicher kontrollierter Codec-Vergleich setzt nur die Empfangsfactory
von `PayloadEntry` auf die bisherige `ByteSequence` zurück. Beide Varianten
verwenden denselben aktuellen Runtime-Codec, dasselbe Drahtformat und dieselben
Inhalte. Das ist eine Darstellungskontrolle und kein vollständig zurückgebauter
historischer Runtime-Stand.

Drei frische Prozesse pro Variante, je zehn Aufwärm- und 30 Messnachrichten,
vollständige Inhaltsprüfung außerhalb der Zeitmessung:

| Reine Dekodierung | Referenzliste, vorreserviert | Kompakte ByteSeq |
|---|---:|---:|
| Median der Prozessmittelwerte | 9,718 ms | 0,483 ms |
| Java-Allokationen je Nachricht | 7,406 MB | 1,866 MB |

Damit ungefähr 20× schneller und 75 % weniger Allokationen in diesem komplexen
Codec-Fall. Der Vorteil entsteht in den enthaltenen primitiven Byte-Sequenzen,
nicht durch eine neue Reservierung der bereits passenden äußeren Struct-Arrays.

## Validierung und Artefakte

Die vollständige gebaute CTest-Suite bestand mit 108/108 Tests, einschließlich
C-Interoperabilität, Paket-/Consumer-Tests und Beispielen. Nach anschließenden
Ergänzungen für kompakte Collection-Einfügeoperationen und die Serialisierung
ungenutzter Kapazität wurden die betroffenen PackedByteSequence-/CDR-Tests erneut
gebaut und erfolgreich ausgeführt. Diese Ergänzungen ändern den gemessenen
CDR-Schreib-/Lesepfad nicht.

Die neuen Tests prüfen zusätzlich per Vergleich mit ArrayList zufällige
List-Operationen, null-Verhalten, Iteratoren, Sublisten, Selbst-Einfügen,
Serialisierung, Arraybesitz, identische CDR-Bytes, beide CDR-Versionen und
Byte-Reihenfolgen, Recovery, abgeschnittene Eingaben und die Beachtung eigener
Factories. Der Struct-Test prüft unabhängige Daten über mehrere DDS-Takes.

Lokale Rohdaten, Messprogramme, exakte Startbefehle, ein eingefrorenes
Runtime-JAR, native Hashes und das Diagramm liegen in
`benchmarks/packed-step2-20261118`. Diese Diagnoseartefakte sind nicht versioniert.
Die C#-Serie verwendet die eingefrorene C#-Implementierung des ursprünglichen
Vergleichs, deren relevante Quellen gegen das offizielle Repository bei Commit
`543eba2d1be168b6aacd0ae65f72f794d8eb6789` geprüft wurden.
