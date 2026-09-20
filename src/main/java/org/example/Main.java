package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

public class Main {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> createDropWindow(
                "Ordner hierher ziehen",
                "Ordner hier ablegen<br>(Drag & Drop)",
                File::isDirectory,
                "Bitte einen Ordner ablegen, keine Datei.",
                Main::handleFolder));
    }

    private static void createDropWindow(String title, String labelHtml, Predicate<File> accept,
                                         String rejectMessage, Consumer<File> onAccept) {
        JFrame frame = new JFrame(title);
        JLabel label = new JLabel("<html><center>" + labelHtml + "</center></html>", SwingConstants.CENTER);
        label.setPreferredSize(new Dimension(400, 200));
        label.setOpaque(true);
        label.setBackground(new Color(230, 230, 230));
        frame.add(label);
        frame.pack();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);

        new DropTarget(label, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent evt) {
                try {
                    evt.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> droppedFiles = (List<File>) evt.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);

                    if (!droppedFiles.isEmpty()) {
                        File dropped = droppedFiles.get(0);
                        if (accept.test(dropped)) {
                            evt.dropComplete(true);
                            frame.dispose();
                            onAccept.accept(dropped);
                            return;
                        } else {
                            System.out.println(rejectMessage);
                        }
                    }
                    evt.dropComplete(true);
                } catch (Exception e) {
                    e.printStackTrace();
                    evt.dropComplete(false);
                }
            }
        });

        frame.setVisible(true);
        System.out.println(title + " - Fenster geoeffnet.");
    }

    private static void handleFolder(File folder) {
        Scanner scanner = new Scanner(System.in);
        System.out.println();
        System.out.println("Ordner: " + folder.getAbsolutePath());
        System.out.println("Was moechtest du tun?");
        System.out.println("1 = Dateinamen auflisten (dateiliste.txt erzeugen)");
        System.out.println("2 = Dateien anhand einer CSV-Liste umbenennen");
        System.out.println("3 = Metadaten (Interpret, Album) aus CSV-Datei in die Dateien schreiben");
        System.out.print("Eingabe: ");
        String choice = scanner.nextLine().trim();

        if (choice.equals("2")) {
            SwingUtilities.invokeLater(() -> createDropWindow(
                    "CSV-Liste hierher ziehen",
                    "CSV-Datei hier ablegen<br>(alter_dateiname;neuer_dateiname)",
                    f -> f.isFile() && f.getName().toLowerCase().endsWith(".csv"),
                    "Bitte eine CSV-Datei ablegen.",
                    csvFile -> renameFromCsv(folder, csvFile)));
        } else if (choice.equals("3")) {
            SwingUtilities.invokeLater(() -> createDropWindow(
                    "Metadaten-CSV hierher ziehen",
                    "CSV-Datei hier ablegen<br>(name;interpret;album)",
                    f -> f.isFile() && f.getName().toLowerCase().endsWith(".csv"),
                    "Bitte eine CSV-Datei ablegen.",
                    csvFile -> writeMetadataFromCsv(folder, csvFile)));
        } else {
            scanAndWrite(folder);
        }
    }

    private static void scanAndWrite(File folder) {
        System.out.println("Scanne Ordner: " + folder.getAbsolutePath());
        File outputFile = new File(folder, "dateiliste.txt");

        try (Stream<Path> paths = Files.walk(folder.toPath());
             PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {

            int[] count = {0};
            paths.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(path -> {
                        writer.println(path.getFileName().toString());
                        count[0]++;
                    });

            System.out.println("Fertig. " + count[0] + " Dateien gefunden.");
            System.out.println("Ausgabe geschrieben nach: " + outputFile.getAbsolutePath());

        } catch (IOException e) {
            System.out.println("Fehler beim Scannen/Schreiben: " + e.getMessage());
        }

        System.exit(0);
    }

    private static void renameFromCsv(File folder, File csvFile) {
        if (!csvFile.isFile()) {
            System.out.println("CSV-Datei nicht gefunden: " + csvFile.getAbsolutePath());
            System.exit(1);
        }

        System.out.println("Baue Dateiindex fuer: " + folder.getAbsolutePath());
        Map<String, List<Path>> index = buildFileIndex(folder);
        if (index == null) return;

        List<String> lines = readCsvLines(csvFile);
        if (lines == null) return;

        int renamed = 0;
        int notFound = 0;
        int failed = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = stripBomIfFirstLine(lines.get(i), i);
            if (line.trim().isEmpty()) continue;

            try {
                String[] parts = parseCsvLine(line);
                if (parts.length < 2) continue;

                String oldName = parts[0].trim();
                String newName = parts[1].trim();

                if (i == 0 && oldName.equalsIgnoreCase("alter_dateiname")) {
                    continue; // Kopfzeile ueberspringen
                }
                if (oldName.isEmpty() || newName.isEmpty()) continue;

                List<Path> matches = index.get(oldName);
                if (matches == null || matches.isEmpty()) {
                    System.out.println("NICHT GEFUNDEN: " + oldName);
                    notFound++;
                    continue;
                }

                for (Path oldPath : matches) {
                    try {
                        Path newPath = oldPath.resolveSibling(newName);
                        Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING);
                        System.out.println("OK: " + oldName + "  -->  " + newName);
                        renamed++;
                    } catch (Exception e) {
                        System.out.println("FEHLER bei \"" + oldName + "\": " + e.getMessage());
                        failed++;
                    }
                }
            } catch (Exception e) {
                System.out.println("FEHLER in Zeile " + (i + 1) + ": " + e.getMessage());
                failed++;
            }
        }

        System.out.println();
        System.out.println("Fertig. " + renamed + " Dateien umbenannt, " + notFound + " nicht gefunden, " + failed + " Fehler.");
        System.exit(0);
    }

    /**
     * Liest eine CSV-Datei im Format "name;interpret;album" ein und schreibt die
     * jeweiligen Werte als Interpret- und Album-Metadaten (ID3-Tags bei MP3,
     * MP4-Atome bei M4A, ...) in die passenden Dateien im Zielordner.
     */
    private static void writeMetadataFromCsv(File folder, File csvFile) {
        if (!csvFile.isFile()) {
            System.out.println("CSV-Datei nicht gefunden: " + csvFile.getAbsolutePath());
            System.exit(1);
        }

        System.out.println("Baue Dateiindex fuer: " + folder.getAbsolutePath());
        Map<String, List<Path>> index = buildFileIndex(folder);
        if (index == null) return;

        List<String> lines = readCsvLines(csvFile);
        if (lines == null) return;

        int updated = 0;
        int skippedEmpty = 0;
        int notFound = 0;
        int failed = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = stripBomIfFirstLine(lines.get(i), i);
            if (line.trim().isEmpty()) continue;

            String[] parts;
            try {
                parts = parseCsvLine(line);
            } catch (Exception e) {
                System.out.println("FEHLER in Zeile " + (i + 1) + ": " + e.getMessage());
                failed++;
                continue;
            }
            if (parts.length < 1) continue;

            String fileName = parts[0].trim();
            String interpret = parts.length > 1 ? parts[1].trim() : "";
            String album = parts.length > 2 ? parts[2].trim() : "";

            if (i == 0 && fileName.equalsIgnoreCase("name")) {
                continue; // Kopfzeile ueberspringen
            }
            if (fileName.isEmpty()) continue;

            if (interpret.isEmpty() && album.isEmpty()) {
                skippedEmpty++;
                continue;
            }

            List<Path> matches = index.get(fileName);
            if (matches == null || matches.isEmpty()) {
                System.out.println("NICHT GEFUNDEN: " + fileName);
                notFound++;
                continue;
            }

            for (Path filePath : matches) {
                try {
                    writeTags(filePath.toFile(), interpret, album);
                    StringBuilder msg = new StringBuilder("OK: " + fileName);
                    if (!interpret.isEmpty()) msg.append("  Interpret=\"").append(interpret).append("\"");
                    if (!album.isEmpty()) msg.append("  Album=\"").append(album).append("\"");
                    System.out.println(msg);
                    updated++;
                } catch (Exception e) {
                    System.out.println("FEHLER bei \"" + fileName + "\": " + e.getMessage());
                    failed++;
                }
            }
        }

        System.out.println();
        System.out.println("Fertig. " + updated + " Dateien aktualisiert, " + skippedEmpty
                + " ohne Metadaten uebersprungen, " + notFound + " nicht gefunden, " + failed + " Fehler.");
        System.exit(0);
    }

    /**
     * Schreibt Interpret und/oder Album in die Metadaten einer Audiodatei.
     * Leere Werte werden nicht angefasst (bestehender Tag-Wert bleibt erhalten).
     */
    private static void writeTags(File audioFile, String interpret, String album) throws Exception {
        AudioFile f = AudioFileIO.read(audioFile);
        Tag tag = f.getTagOrCreateAndSetDefault();

        if (!interpret.isEmpty()) {
            tag.setField(FieldKey.ARTIST, interpret);
        }
        if (!album.isEmpty()) {
            tag.setField(FieldKey.ALBUM, album);
        }

        f.commit();
    }

    /**
     * Baut einen Index Dateiname -> Pfade fuer alle regulaeren Dateien im Ordner (rekursiv).
     * Gibt bei einem Lesefehler null zurueck (Prozess wird dabei mit exit code 1 beendet).
     */
    private static Map<String, List<Path>> buildFileIndex(File folder) {
        Map<String, List<Path>> index = new HashMap<>();
        try (Stream<Path> paths = Files.walk(folder.toPath())) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                index.computeIfAbsent(name, k -> new ArrayList<>()).add(path);
            });
        } catch (IOException e) {
            System.out.println("Fehler beim Einlesen des Ordners: " + e.getMessage());
            System.exit(1);
            return null;
        }
        return index;
    }

    /**
     * Liest alle Zeilen einer CSV-Datei als UTF-8 ein.
     * Gibt bei einem Lesefehler null zurueck (Prozess wird dabei mit exit code 1 beendet).
     */
    private static List<String> readCsvLines(File csvFile) {
        try {
            return Files.readAllLines(csvFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("Fehler beim Lesen der CSV-Datei: " + e.getMessage());
            System.exit(1);
            return null;
        }
    }

    /**
     * Entfernt ein fuehrendes UTF-8-BOM-Zeichen (\uFEFF), falls es sich um die erste Zeile handelt.
     * Manche Tools (z.B. Excel) schreiben dieses Zeichen an den Anfang von CSV-Dateien.
     */
    private static String stripBomIfFirstLine(String line, int lineIndex) {
        if (lineIndex == 0 && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
            return line.substring(1);
        }
        return line;
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ';') {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}