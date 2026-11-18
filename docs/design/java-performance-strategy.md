# Strategie zur Verbesserung der Java-Wrapper-Performance

Stand: 18. November 2026. Dieses Dokument beschreibt die umgesetzten Schritte und die geplanten Folgearbeiten.

## Ziel und Ausgangspunkt

Der Java-Wrapper soll bei den untersuchten Byte-Nachrichten in die Größenordnung des offiziellen C#-Wrappers kommen. Die OMG-DDS-/ByteSeq-Schnittstellen, das CDR-Drahtformat, zuverlässige Zustellung und die Unabhängigkeit zurückgegebener Empfangsobjekte bleiben erhalten.

Die Ursachenanalyse ergab für 200,01 MB: 4,16 s und 2,84 GB Java-Allokationen allein beim Dekodieren. Nur die Vorreservierung der Empfangsliste senkte dies auf 1,57 s und 0,80 GB. Ein Kontrollpfad mit kompakten Byte-Arrays zeigte erhebliches weiteres Potential, ist aber noch keine gleichwertige öffentliche API-Implementierung. Das 1-GB-Szenario scheiterte bei 12 GiB Heap; mit 20 GiB gelang ein einzelner Diagnoseprozess. 20 GiB ist dabei ein konfiguriertes Limit, kein gemessener Mindestbedarf oder Heap-Peak.

## Umsetzungsstand

Schritt 1 ist im Arbeitsstand umgesetzt: Vorreservierung in `CdrCodecs.primitiveSequence`, ergänzt um Regressionstests. 20 ausgewählte Korrektheits-/Interoperabilitätstests einschließlich verschachtelter Struct-Arrays bestanden. Der frische Vergleich mit drei Prozessen je Variante ergab bei 200,01 MB 4,14 → 1,55 s reine Dekodierzeit und 2,84 → 0,80 GB Java-Allokationen. Ein separater 1-GB-DDS-Prozess mit einer Aufwärm- und drei Messnachrichten gelang mit 12 GiB Heap; durchschnittlich 12,29 s für Schreiben + Empfang. Das 8-GiB-Ziel und ähnliche Gesamtperformance wie C# sind damit noch nicht nachgewiesen.

Ergebnisse und Messgrenzen: [Schritt-1-Bericht](sequence-preallocation-results.md).

Schritt 2 ist im Arbeitsstand umgesetzt: kompakte ByteSeq und blockweise CDR-Verarbeitung, verbindlich als Generator-Standard ohne Legacy-Option. Die vollständige Suite bestand mit 108 Tests. Alle fünf 1-GB-Prozesse bestanden die Messung mit 8 GiB Heap. Der frische Vergleich liegt je nach Größe noch bei 1,82–4,81× der C#-Zeit; das Zeitziel ist deshalb noch nicht erreicht. [Schritt-2-Ergebnisse](packed-byte-sequence-results.md).

Schritt 3 ist im Arbeitsstand umgesetzt: direkte Übergabe des genutzten CDR-Puffers, weniger JNI-Zwischenpuffer und keine zusätzliche CDR-Payload im dekodierten OMG-Sample. 109 Tests bestanden. Der frische Vergleich liegt je nach Größe bei 1,18–2,09× C#; das Zeitziel ist bei 164 KiB erreicht. [Kopierpfad, Teilmessungen und Ergebnisse](cdr-copy-reduction-results.md).

## Vorgeschlagene Abnahmekriterien

Diese Werte sind Entwicklungsziele, keine zugesagten Ergebnisse:

- Alle vier Nutzdatengrößen funktionieren: 41.984 B, 167.936 B, 200.010.000 B und 1.000.000.000 B.
- Ziel für den lokalen Write+Take-Vergleich: Java benötigt höchstens das 1,5-Fache der C#-Zeit je Größe. Zwischen Faktor 1,5 und 2 folgt eine Bewertung der verbleibenden Kosten; über Faktor 2 ist das Performance-Ziel nicht erreicht.
- Der 1-GB-Test soll schließlich mit höchstens 8 GiB Java-Heap zuverlässig funktionieren. Zunächst wird nach jeder Speicherverbesserung das bestehende 12-GiB-Szenario geprüft.
- Keine verlorenen, vertauschten oder beschädigten Nachrichten. Jede erfolgreiche Messnachricht besteht die vollständige Inhaltsprüfung.
- Kein Rückschritt bei CDR-Kompatibilität, Sequenzgrenzen, Try-construct, benutzerdefinierten ByteSeq-Implementierungen und eigenständigen Empfangsobjekten.
- Allokationen, GC-Pausen und Prozessspeicher müssen mitberichtet werden. Eine reine Verschiebung von Heap-Allokationen in native Puffer erfüllt das Speicherziel nicht.

Diese Kriterien gelten zunächst für lokale Byte-Nachrichten mit dem getesteten Schema. Ein separater Prozess-/Netzwerktest folgt nach Erreichen des lokalen Ziels; seine Ergebnisse werden getrennt ausgewiesen.

## Schritt 1: Empfangsliste vorreservieren

Kleinster Eingriff mit bereits gemessenem Nutzen, als separat überprüfbare Änderung.

In `CdrCodecs.primitiveSequence` nach vollständiger Prüfung der Sequenzlänge und Bestimmung des tatsächlich zu speichernden `limit` die Kapazität reservieren. Für bestehende ArrayList-Implementierungen kann `ensureCapacity(limit)` verwendet werden. Andere Listen bleiben auf dem vorhandenen Pfad. Bei TRIM/USE_DEFAULT nur die tatsächlich zu speichernde Länge reservieren; alle erforderlichen Eingabebytes trotzdem korrekt konsumieren.

Prüfungen: leere Sequenz, große Sequenz, Bound-Überschreitung, abgeschnittene Eingabe, TRIM/USE_DEFAULT und korrektes Lesen nachfolgender Felder. Vor einer Reservierung müssen Längenvalidierung und Überlaufprüfungen erfolgt sein.

Messpunkt: 164 KiB und 200,01 MB sowie ein begrenzter 1-GB-Funktionstest mit 12 GiB Heap. Der gemessene Vorteil von 2,65× bei der isolierten 200-MB-Dekodierung dient als Referenz, nicht als Zusage für die komplette DDS-Zeit.

## Schritt 2: Kompakte ByteSeq plus blockweiser Codec

Diese Stufe adressiert den größten verbleibenden strukturellen Verlust.

### Speicherung und Kompatibilität

Eine zusätzliche, auf `byte[]` basierende Implementierung von `ByteSeq` einführen, beispielsweise `PackedByteSequence`. Die vorhandene öffentliche `ByteSequence extends ArrayList<Byte>` bleibt erhalten, einschließlich ihrer Konstruktoren und bisherigen Verwendungsmöglichkeiten. Ein Austausch ihrer Oberklasse würde Anwendungen betreffen, die sie als ArrayList verwenden, und wird deshalb vermieden.

Der Generator verwendet die neue Implementierung für neue Standardinstanzen und Empfangsfabriken. Getter, Setter und Konstruktorparameter der generierten Nachrichtentypen bleiben bei `ByteSeq`. Bereits generierte Klassen mit `ByteSequence::new` laufen weiter; ihre Empfangsdarstellung wird ohne Neugenerierung nicht automatisch kompakt. Das Binding ist noch nicht veröffentlicht: Der Generator verwendet für Byte-Sequenzen verbindlich die kompakte Implementierung. Es gibt keine Legacy-Generatoroption. Anwendungen und Beispiele verwenden die deklarierte ByteSeq-Schnittstelle statt eines Downcasts auf ArrayList.

Die neue Implementierung benötigt vollständige List-/ByteSeq-Semantik: primitive und boxed Zugriffe, Einfügen/Löschen, Iteratoren und deren Änderungen, Sublisten, Gleichheit/Hashcode, Array- und Bereichsoperationen sowie Serialisierung. Das bisherige Verhalten bei null-Werten explizit erhalten oder über einen kompatiblen Ausweichpfad abbilden; ein nacktes byte[] kann null-Listenelemente nicht darstellen. Die DDS-Kodierung muss ungültige Elemente weiterhin zurückweisen.

### Codec

Für unterstützte Byte-Sequenzen nach einmaliger Längen-/Bound-Prüfung blockweise kopieren. Beim Empfang eine kompakte, eigenständig besessene Sequenz erzeugen. Beim Senden deren primitiven Speicher direkt in den CDR-Puffer kopieren. Array-/Bereichsoperationen in der neuen Sequenz ebenfalls blockweise implementieren.

Für bestehende oder benutzerdefinierte ByteSeq-Implementierungen bleibt der generische Codec korrekt. Eine beliebige übergebene Factory darf nicht stillschweigend ignoriert werden: Das könnte Aufrufer mit einem konkreten Rückgabetyp brechen. Der neue Generator wählt die neue Factory ausdrücklich; der Laufzeitcodec erkennt einen unterstützten kompakten Speicherpfad.

Die erste Version behält eine klare Owned-Data-Grenze beim Empfang. Kein vorzeitig freigegebener nativer Speicher und kein Empfangspuffer, der durch den nächsten Take überschrieben wird. Öffentliche Array-Konstruktoren und Rückgabemethoden dürfen nicht unbeabsichtigt neue Alias-Beziehungen einführen.

Prüfungen: vollständige List-/ByteSeq-Semantik, alte und neue generierte Klassen, Legacy-ByteSequence, eigene ByteSeq-Implementierung, XCDR1/XCDR2, Endianness, begrenzte und unbeschränkte Sequenzen, Recovery-Verhalten und unabhängige Empfangsobjekte. Die vorhandenen GeneratedTypes-, CDR-, Annotation- und C-Interoperabilitätstests erweitern und ausführen.

Messpunkt: vollständige Vier-Größen-Matrix. Bei 1 GB zuerst 12 GiB, bei Erfolg anschließend 8 GiB Heap prüfen. Die bisher verwendete ArrayList-Typumwandlung im Benchmark nur in der Vorbereitung durch eine schnittstellenbasierte bzw. gleichwertig vorreservierte Erstellung ersetzen. Messgrenzen und Validierung bleiben gleich; den Legacy-Pfad mit demselben angepassten Harness erneut kontrollieren.

## Schritt 3: CDR-Puffer und JNI-Kopien reduzieren

Nach Schritt 2 neu profilieren und nur die dann relevanten Kosten bearbeiten.

### CDR

Die für primitive Sequenzen bekannte Größe vor einem Blocktransfer reservieren. Das blockweise Schreiben aus Schritt 2 vermeidet bereits viele Wachstumsschritte. Kleine Puffer weiterhin begrenzt wiederverwenden; den globalen Pool nicht einfach um beliebig große Nachrichten erweitern.

Die zusätzliche Vollkopie durch `toByteArray()` intern vermeiden: Eine Operation kann einen exklusiv gehaltenen Puffer mit gültiger Länge bis zum Abschluss des nativen Schreibaufrufs besitzen. Erst danach darf er freigegeben oder wiederverwendet werden. Hierfür den internen Write-Ablauf anpassen; das öffentliche `toByteArray()` behält seine unabhängige Kopie. Andere Kodiernutzer, etwa Instanz-/Schlüsseloperationen, müssen weiter korrekte Lebensdauern erhalten.

### JNI

Zuerst den Empfangspfad bearbeiten: Statt `NewByteArray` plus kopierendem `GetByteArrayElements`, Befüllen des Zwischenpuffers und Rückkopie einen direkten Transfer aus einer stabil gehaltenen nativen CDR-Referenz in das Java-Array untersuchen, beispielsweise über `SetByteArrayRegion`. Die native Referenz bleibt bis zum abgeschlossenen Transfer gültig.

Anschließend beim Schreiben prüfen, ob direkt in den endgültigen nativen Serdata-Puffer kopiert werden kann und damit JNI-Zwischenpuffer plus anschließendes `from_iov`-Umkopieren entfallen können. Validierung und Besitzübernahme müssen unverändert funktionieren. Kein langer DDS-Aufruf innerhalb einer kritischen JVM-Array-Sektion.

Prüfungen: Schreiben/Taking/Löschen, wiederholte Nachrichten, Lebensdauer bereits zurückgegebener Daten, Fehler- und Ausnahmewege sowie vorhandene native/C-Interoperabilitätstests. Mit Kopierzählern erneut nachweisen, welche vollständigen Transfers tatsächlich entfallen.

Messpunkt: erneutes Profil und vollständiger Java-/C#-Vergleich. Erreicht diese Stufe das Abnahmeziel, folgen keine zusätzlichen Eingriffe in die native Typverarbeitung ohne gemessenen Bedarf.

## Optionaler Schritt 4: Native Normalisierung

Nur wenn das neue Profil noch einen wesentlichen Anteil in der Normalisierungskopie zeigt. Eine sicher begründete Spezialisierung für passende Typen prüfen. Validierung, Schlüsselverarbeitung und Try-construct dürfen nicht pauschal entfernt werden. Dieser Schritt ist wegen seiner funktionalen Reichweite kein erster Optimierungsversuch.

Weitere primitive Sequenztypen erst nach dem erfolgreichen Byte-Pfad übertragen und jeweils Datendarstellung, Alignment, Endianness und Recovery prüfen.

## Mess- und Freigabeablauf

Jede Stufe ist eine getrennt prüfbare Änderung. Zuerst Korrektheit und gezielter schneller Benchmark, dann an den genannten Meilensteinen die vollständige Vergleichsmatrix.

- Gleiche Maschine, gleicher native DDS-Core, gleiches Schema, QoS und Owned-Data-Verhalten für Java und C#.
- Mindestens fünf unabhängige Prozesse je regulärem Vergleichspunkt; gepaarte, wechselnde Reihenfolge. Java und C# frisch am selben Messtermin ausführen. Alte Rohdaten separat erhalten.
- Die bekannten fehlgeschlagenen 1-GB-Baseline-Läufe als historische Fehler dokumentieren; diese nicht als frisch wiederholte Zeitmessungen ausgeben.
- Vergleichsserie mit unveränderten Messgrenzen und Aufwärmregeln. Ergänzend die JVM-Stabilisierung prüfen; zusätzliche längere Aufwärmreihen getrennt ausweisen und bei Bedarf beide Implementierungen damit neu messen.
- Schreiben, Empfang, gesamte Write+Take-Zeit, Java-Allokationen, GC-Pausen, beobachteter Heap und Prozessspeicher separat erfassen. Speicherdiagnosen und JFR-Läufe nicht mit uninstrumentierten Vergleichsläufen vermischen. RSS ist ein ergänzender Prozessindikator, keine präzise Aufteilung aller Speicherbesitzer.
- Vollständige Inhalts- und Reihenfolgeprüfung außerhalb der Zeitmessung. Keine Änderungen am Validierungsumfang zugunsten einer Variante.
- Für 1 GB jeden gescheiterten Lauf mit Heap-Limit und Ursache ausweisen. Ein einzelner erfolgreicher Diagnoselauf ersetzt keine vollständige Messserie.

Am Ende jeder Stufe entsteht eine aktualisierte Tabelle und ein Diagramm mit Zeitverhältnis zu C#, Streuung sowie Speicherkennzahlen. Werden Zeit- und Speicherziele erreicht, endet die Optimierung dieser Byte-Nachrichten. Andernfalls bestimmt das neue Profil den nächsten Eingriff.

## Empfohlene Reihenfolge

1. Vorreservierung als kleine, bereits belegte Verbesserung.
2. Kompakte ByteSeq und blockweiser Codec als wichtigste strukturelle Änderung.
3. Verbleibende CDR-/JNI-Kopien nach dem neuen Profil reduzieren.
4. Native Normalisierung nur bei weiterem nachgewiesenem Bedarf.

Ein größerer Heap kann Messläufe ermöglichen, ersetzt aber keine dieser Ursachenbehebungen.
