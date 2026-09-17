package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;

public class Main {

    /** Dateiendungen, die von jaudiotagger als Audiodateien gelesen werden koennen. */
    private static final List<String> AUDIO_EXTENSIONS = List.of(
            ".mp3", ".m4a", ".m4b", ".m4p", ".flac", ".ogg", ".wav", ".wma", ".aif", ".aiff"
    );

    private static final List<String> IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png");

    /** Spalten der Metadaten-CSV (Optionen 3, 4, 5) in fester Reihenfolge. */
    private static final String[] CSV_HEADER =
            {"name", "interpret", "album", "titel", "tracknummer", "jahr", "genre"};
    private static final FieldKey[] CSV_FIELDKEYS =
            {null, FieldKey.ARTIST, FieldKey.ALBUM, FieldKey.TITLE, FieldKey.TRACK, FieldKey.YEAR, FieldKey.GENRE};
    private static final int COLS = CSV_HEADER.length;

    // ------------------------------------------------------------------
    // Einstieg / CLI
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        Map<String, String> opts = parseArgs(args);

        if (opts.containsKey("help")) {
            printUsage();
            return;
        }

        if (opts.containsKey("folder") && opts.containsKey("action")) {
            runCli(opts);
            return;
        }

        SwingUtilities.invokeLater(() -> createDropWindow(
                "Ordner hierher ziehen",
                "Ordner hier ablegen<br>(Drag & Drop)",
                File::isDirectory,
                "Bitte einen Ordner ablegen, keine Datei.",
                Main::handleFolder));
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    opts.put(key, args[i + 1]);
                    i++;
                } else {
                    opts.put(key, "true");
                }
            }
        }
        return opts;
    }

    private static void printUsage() {
        System.out.println("Interaktiv: Programm ohne Parameter starten, Ordner per Drag & Drop ablegen.");
        System.out.println();
        System.out.println("Kommandozeile:");
        System.out.println("  --folder <pfad>     Zielordner (zusammen mit --action erforderlich)");
        System.out.println("  --action <1-7>      Auszufuehrende Aktion");
        System.out.println("  --csv <pfad>        CSV-Datei fuer Aktion 2 und 3");
        System.out.println("  --image <pfad>      Bilddatei fuer Aktion 7");
        System.out.println("  --pattern <muster>  Dateinamen-Muster fuer Aktion 6 (Standard: \"{interpret} - {titel}\")");
        System.out.println("  --yes               Sicherheitsabfragen automatisch bestaetigen (fuer Skripte)");
        System.out.println("  --help              Diese Hilfe anzeigen");
        System.out.println();
        System.out.println("Aktionen:");
        System.out.println("  1 = Dateinamen auflisten (dateiliste.txt)");
        System.out.println("  2 = Dateien anhand CSV umbenennen (alter_dateiname;neuer_dateiname)");
        System.out.println("  3 = Metadaten aus CSV schreiben (name;interpret;album;titel;tracknummer;jahr;genre)");
        System.out.println("  4 = Dateien mit fehlenden Metadaten auflisten (fehlende_metadaten.csv)");
        System.out.println("  5 = Alle Metadaten exportieren (alle_metadaten.csv)");
        System.out.println("  6 = Dateien anhand ihrer Tags umbenennen");
        System.out.println("  7 = Cover-Bild in alle Audiodateien einbetten");
    }

    private static void runCli(Map<String, String> opts) {
        File folder = new File(opts.get("folder"));
        if (!folder.isDirectory()) {
            System.out.println("Ordner nicht gefunden: " + folder.getAbsolutePath());
            System.exit(1);
            return;
        }
        boolean yes = opts.containsKey("yes");
        String action = opts.get("action");

        switch (action) {
            case "1":
                scanAndWrite(folder);
                break;
            case "2": {
                File csv = requireFile(opts.get("csv"), "--csv");
                renameFromCsv(folder, csv, yes);
                break;
            }
            case "3": {
                File csv = requireFile(opts.get("csv"), "--csv");
                writeMetadataFromCsv(folder, csv, yes);
                break;
            }
            case "4":
                listMissingMetadata(folder);
                break;
            case "5":
                exportAllMetadata(folder);
                break;
            case "6": {
                String pattern = opts.getOrDefault("pattern", "{interpret} - {titel}");
                renameFromTags(folder, pattern, yes);
                break;
            }
            case "7": {
                File image = requireFile(opts.get("image"), "--image");
                if (!isImageFile(image)) {
                    System.out.println("Keine unterstuetzte Bilddatei (jpg/png): " + image.getAbsolutePath());
                    System.exit(1);
                    return;
                }
                embedCoverArt(folder, image, yes);
                break;
            }
            default:
                System.out.println("Unbekannte Aktion: " + action);
                printUsage();
                System.exit(1);
        }
    }

    private static File requireFile(String path, String optionName) {
        if (path == null) {
            System.out.println("Fehlender Parameter " + optionName);
            printUsage();
            System.exit(1);
            return null;
        }
        File f = new File(path);
        if (!f.isFile()) {
            System.out.println("Datei nicht gefunden: " + f.getAbsolutePath());
            System.exit(1);
            return null;
        }
        return f;
    }

    // ------------------------------------------------------------------
    // GUI: Drop-Fenster & Menue
    // ------------------------------------------------------------------

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
        System.out.println("3 = Metadaten aus CSV-Datei in die Dateien schreiben");
        System.out.println("4 = Dateien mit fehlenden Metadaten auflisten (fehlende_metadaten.csv)");
        System.out.println("5 = Alle Metadaten exportieren (alle_metadaten.csv)");
        System.out.println("6 = Dateien anhand ihrer Tags umbenennen (Muster, z.B. {interpret} - {titel})");
        System.out.println("7 = Cover-Bild in alle Audiodateien im Ordner einbetten");
        System.out.print("Eingabe: ");
        String choice = scanner.nextLine().trim();

        switch (choice) {
            case "2":
                SwingUtilities.invokeLater(() -> createDropWindow(
                        "CSV-Liste hierher ziehen",
                        "CSV-Datei hier ablegen<br>(alter_dateiname;neuer_dateiname)",
                        f -> f.isFile() && f.getName().toLowerCase().endsWith(".csv"),
                        "Bitte eine CSV-Datei ablegen.",
                        csvFile -> renameFromCsv(folder, csvFile, false)));
                break;
            case "3":
                SwingUtilities.invokeLater(() -> createDropWindow(
                        "Metadaten-CSV hierher ziehen",
                        "CSV-Datei hier ablegen<br>(name;interpret;album;titel;tracknummer;jahr;genre)",
                        f -> f.isFile() && f.getName().toLowerCase().endsWith(".csv"),
                        "Bitte eine CSV-Datei ablegen.",
                        csvFile -> writeMetadataFromCsv(folder, csvFile, false)));
                break;
            case "4":
                listMissingMetadata(folder);
                break;
            case "5":
                exportAllMetadata(folder);
                break;
            case "6": {
                System.out.print("Dateinamen-Muster (Platzhalter: {interpret} {titel} {album} {jahr} " +
                        "{tracknummer} {genre}), Enter fuer Standard \"{interpret} - {titel}\": ");
                String patternInput = scanner.nextLine().trim();
                String pattern = patternInput.isEmpty() ? "{interpret} - {titel}" : patternInput;
                renameFromTags(folder, pattern, false);
                break;
            }
            case "7":
                SwingUtilities.invokeLater(() -> createDropWindow(
                        "Cover-Bild hierher ziehen",
                        "Bilddatei hier ablegen<br>(jpg/png)",
                        f -> f.isFile() && isImageFile(f),
                        "Bitte eine Bilddatei (jpg/png) ablegen.",
                        imgFile -> embedCoverArt(folder, imgFile, false)));
                break;
            default:
                scanAndWrite(folder);
        }
    }

    // ------------------------------------------------------------------
    // Option 1: Dateinamen auflisten
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Option 2: Dateien anhand CSV umbenennen
    // ------------------------------------------------------------------

    private static void renameFromCsv(File folder, File csvFile, boolean autoConfirm) {
        if (!csvFile.isFile()) {
            System.out.println("CSV-Datei nicht gefunden: " + csvFile.getAbsolutePath());
            System.exit(1);
            return;
        }

        System.out.println("Baue Dateiindex fuer: " + folder.getAbsolutePath());
        Map<String, List<Path>> index = buildFileIndex(folder);
        if (index == null) return;

        List<String> lines = readCsvLines(csvFile);
        if (lines == null) return;

        List<String> notFound = new ArrayList<>();
        List<RenamePair> plan = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = stripBomIfFirstLine(lines.get(i), i);
            if (line.trim().isEmpty()) continue;

            String[] parts;
            try {
                parts = parseCsvLine(line);
            } catch (Exception e) {
                System.out.println("FEHLER in Zeile " + (i + 1) + ": " + e.getMessage());
                continue;
            }
            if (parts.length < 2) continue;

            String oldName = parts[0].trim();
            String newName = parts[1].trim();

            if (i == 0 && oldName.equalsIgnoreCase("alter_dateiname")) continue; // Kopfzeile
            if (oldName.isEmpty() || newName.isEmpty()) continue;

            List<Path> matches = index.get(oldName);
            if (matches == null || matches.isEmpty()) {
                notFound.add(oldName);
                continue;
            }

            for (Path oldPath : matches) {
                plan.add(new RenamePair(oldPath, oldPath.resolveSibling(newName)));
            }
        }

        reportNotFound(notFound);

        if (plan.isEmpty()) {
            System.out.println("Keine gueltigen Umbenennungen gefunden.");
            System.exit(0);
            return;
        }

        List<String> previewLines = new ArrayList<>();
        for (RenamePair p : plan) {
            String note = Files.exists(p.newPath) && !p.newPath.equals(p.oldPath)
                    ? "  (ACHTUNG: Ziel existiert bereits und wird ueberschrieben)" : "";
            previewLines.add("  " + p.oldPath.getFileName() + "  -->  " + p.newPath.getFileName() + note);
        }
        System.out.println();
        System.out.println("Vorschau (" + plan.size() + " Umbenennungen):");
        printPreview(previewLines, 30);

        if (!promptConfirm(autoConfirm, "Umbenennung von " + plan.size() + " Dateien durchfuehren?")) {
            System.out.println("Abgebrochen.");
            System.exit(0);
            return;
        }

        writeRenameBackup("rename_backup", folder, plan);

        Progress progress = createProgress("Dateien werden umbenannt...");
        int renamed = 0, failed = 0, total = plan.size(), idx = 0;
        for (RenamePair p : plan) {
            idx++;
            progress.update(idx, total, p.oldPath.getFileName().toString());
            try {
                Files.move(p.oldPath, p.newPath, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("OK: " + p.oldPath.getFileName() + "  -->  " + p.newPath.getFileName());
                renamed++;
            } catch (Exception e) {
                System.out.println("FEHLER bei \"" + p.oldPath.getFileName() + "\": " + e.getMessage());
                failed++;
            }
        }
        progress.close();

        System.out.println();
        System.out.println("Fertig. " + renamed + " Dateien umbenannt, " + notFound.size()
                + " nicht gefunden, " + failed + " Fehler.");
        System.exit(0);
    }

    // ------------------------------------------------------------------
    // Option 3: Metadaten aus CSV schreiben
    // ------------------------------------------------------------------

    private static void writeMetadataFromCsv(File folder, File csvFile, boolean autoConfirm) {
        if (!csvFile.isFile()) {
            System.out.println("CSV-Datei nicht gefunden: " + csvFile.getAbsolutePath());
            System.exit(1);
            return;
        }

        System.out.println("Baue Dateiindex fuer: " + folder.getAbsolutePath());
        Map<String, List<Path>> index = buildFileIndex(folder);
        if (index == null) return;

        List<String> lines = readCsvLines(csvFile);
        if (lines == null) return;

        List<String> notFound = new ArrayList<>();
        List<MetaChange> plan = new ArrayList<>();
        int skippedEmpty = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = stripBomIfFirstLine(lines.get(i), i);
            if (line.trim().isEmpty()) continue;

            String[] rawParts;
            try {
                rawParts = parseCsvLine(line);
            } catch (Exception e) {
                System.out.println("FEHLER in Zeile " + (i + 1) + ": " + e.getMessage());
                continue;
            }
            if (rawParts.length < 1) continue;

            String[] row = padRow(rawParts);
            String fileName = row[0].trim();

            if (i == 0 && fileName.equalsIgnoreCase("name")) continue; // Kopfzeile
            if (fileName.isEmpty()) continue;

            boolean anyValue = false;
            for (int c = 1; c < COLS; c++) {
                row[c] = row[c].trim();
                if (!row[c].isEmpty()) anyValue = true;
            }
            if (!anyValue) {
                skippedEmpty++;
                continue;
            }

            List<Path> matches = index.get(fileName);
            if (matches == null || matches.isEmpty()) {
                notFound.add(fileName);
                continue;
            }

            for (Path path : matches) {
                String[] oldVals;
                try {
                    oldVals = readAllFields(path);
                } catch (Exception e) {
                    System.out.println("FEHLER beim Lesen von \"" + fileName + "\": " + e.getMessage());
                    continue;
                }
                plan.add(new MetaChange(path, oldVals, row));
            }
        }

        reportNotFound(notFound);

        if (plan.isEmpty()) {
            System.out.println("Keine gueltigen Metadaten-Aenderungen gefunden.");
            System.exit(0);
            return;
        }

        List<String> previewLines = new ArrayList<>();
        for (MetaChange c : plan) {
            StringBuilder sb = new StringBuilder("  " + c.path.getFileName() + ":");
            boolean any = false;
            for (int col = 1; col < COLS; col++) {
                if (!c.newVals[col].isEmpty()) {
                    sb.append("  ").append(CSV_HEADER[col]).append("=\"").append(c.oldVals[col])
                            .append("\"->\"").append(c.newVals[col]).append("\"");
                    any = true;
                }
            }
            if (any) previewLines.add(sb.toString());
        }
        System.out.println();
        System.out.println("Vorschau (" + plan.size() + " Dateien):");
        printPreview(previewLines, 30);

        if (!promptConfirm(autoConfirm, "Metadaten von " + plan.size() + " Dateien schreiben?")) {
            System.out.println("Abgebrochen.");
            System.exit(0);
            return;
        }

        writeMetaBackup(folder, plan);

        Progress progress = createProgress("Metadaten werden geschrieben...");
        int updated = 0, failed = 0, total = plan.size(), idx = 0;
        for (MetaChange c : plan) {
            idx++;
            progress.update(idx, total, c.path.getFileName().toString());
            try {
                writeTagsExtended(c.path.toFile(), c.newVals);
                System.out.println("OK: " + c.path.getFileName());
                updated++;
            } catch (Exception e) {
                System.out.println("FEHLER bei \"" + c.path.getFileName() + "\": " + e.getMessage());
                failed++;
            }
        }
        progress.close();

        System.out.println();
        System.out.println("Fertig. " + updated + " Dateien aktualisiert, " + skippedEmpty
                + " ohne Metadaten uebersprungen, " + notFound.size() + " nicht gefunden, " + failed + " Fehler.");
        System.exit(0);
    }

    private static void writeMetaBackup(File folder, List<MetaChange> plan) {
        File backupFile = new File(folder, "metadaten_backup_" + timestamp() + ".csv");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(backupFile), StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write(String.join(";", CSV_HEADER) + "\r\n");
            for (MetaChange c : plan) {
                StringBuilder row = new StringBuilder(csvEscape(c.path.getFileName().toString()));
                for (int col = 1; col < COLS; col++) {
                    row.append(";").append(csvEscape(c.oldVals[col]));
                }
                writer.write(row + "\r\n");
            }
            System.out.println("Backup der alten Metadaten geschrieben nach: " + backupFile.getAbsolutePath());
        } catch (IOException e) {
            System.out.println("Warnung: Backup-Log konnte nicht geschrieben werden: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Option 4: Dateien mit fehlendem Interpret/Album auflisten
    // ------------------------------------------------------------------

    private static void listMissingMetadata(File folder) {
        System.out.println("Durchsuche Ordner nach Audiodateien: " + folder.getAbsolutePath());

        List<Path> audioFiles = collectAudioFiles(folder);
        if (audioFiles == null) return;

        List<String[]> missing = new ArrayList<>();
        int checked = 0, unreadable = 0, total = audioFiles.size(), idx = 0;
        Progress progress = createProgress("Metadaten werden geprueft...");

        for (Path path : audioFiles) {
            idx++;
            checked++;
            String fileName = path.getFileName().toString();
            progress.update(idx, total, fileName);
            try {
                String[] fields = readAllFields(path);
                boolean missingArtistOrAlbum = fields[1].isEmpty() || fields[2].isEmpty();
                if (missingArtistOrAlbum) {
                    String[] guess = guessFromFilename(stripExtension(fileName));
                    if (guess != null) {
                        if (fields[1].isEmpty() && !guess[0].isEmpty()) fields[1] = guess[0];
                        if (fields[3].isEmpty() && !guess[1].isEmpty()) fields[3] = guess[1];
                    }
                    missing.add(fields);
                    System.out.println("FEHLT: " + fileName
                            + "  (Interpret=" + (fields[1].isEmpty() ? "-" : fields[1])
                            + ", Album=" + (fields[2].isEmpty() ? "-" : fields[2]) + ")");
                }
            } catch (Exception e) {
                unreadable++;
                System.out.println("NICHT LESBAR: " + fileName + "  (" + e.getMessage() + ")");
            }
        }
        progress.close();

        File outputFile = new File(folder, "fehlende_metadaten.csv");
        writeMetaCsv(outputFile, missing);

        System.out.println();
        System.out.println("Fertig. " + checked + " Audiodateien geprueft, " + missing.size()
                + " mit fehlendem Interpret/Album, " + unreadable + " nicht lesbar.");
        System.out.println("Ausgabe geschrieben nach: " + outputFile.getAbsolutePath());
        System.out.println("Hinweis: Interpret/Titel wurden dort, wo sie im Tag fehlten, versuchsweise aus dem " +
                "Dateinamen (Muster \"Interpret - Titel\") vorbefuellt und sollten geprueft werden.");
        System.exit(0);
    }

    // ------------------------------------------------------------------
    // Option 5: Alle Metadaten exportieren
    // ------------------------------------------------------------------

    private static void exportAllMetadata(File folder) {
        System.out.println("Durchsuche Ordner nach Audiodateien: " + folder.getAbsolutePath());

        List<Path> audioFiles = collectAudioFiles(folder);
        if (audioFiles == null) return;

        List<String[]> rows = new ArrayList<>();
        int checked = 0, unreadable = 0, total = audioFiles.size(), idx = 0;
        Progress progress = createProgress("Metadaten werden exportiert...");

        for (Path path : audioFiles) {
            idx++;
            checked++;
            String fileName = path.getFileName().toString();
            progress.update(idx, total, fileName);
            try {
                rows.add(readAllFields(path));
            } catch (Exception e) {
                unreadable++;
                System.out.println("NICHT LESBAR: " + fileName + "  (" + e.getMessage() + ")");
            }
        }
        progress.close();

        File outputFile = new File(folder, "alle_metadaten.csv");
        writeMetaCsv(outputFile, rows);

        System.out.println();
        System.out.println("Fertig. " + checked + " Audiodateien geprueft, " + rows.size()
                + " exportiert, " + unreadable + " nicht lesbar.");
        System.out.println("Ausgabe geschrieben nach: " + outputFile.getAbsolutePath());
        System.exit(0);
    }

    // ------------------------------------------------------------------
    // Option 6: Dateien anhand ihrer Tags umbenennen
    // ------------------------------------------------------------------

    private static void renameFromTags(File folder, String pattern, boolean autoConfirm) {
        System.out.println("Durchsuche Ordner nach Audiodateien: " + folder.getAbsolutePath());
        List<Path> audioFiles = collectAudioFiles(folder);
        if (audioFiles == null) return;

        List<RenamePair> plan = new ArrayList<>();
        Set<Path> plannedTargets = new HashSet<>();
        int skippedNoTags = 0, skippedCollision = 0, skippedNoChange = 0;

        for (Path path : audioFiles) {
            String fileName = path.getFileName().toString();
            String[] fields;
            try {
                fields = readAllFields(path);
            } catch (Exception e) {
                System.out.println("NICHT LESBAR: " + fileName + "  (" + e.getMessage() + ")");
                continue;
            }

            String artist = fields[1], album = fields[2], title = fields[3],
                    track = fields[4], year = fields[5], genre = fields[6];

            if (artist.isEmpty() || title.isEmpty()) {
                System.out.println("UEBERSPRUNGEN (kein Interpret/Titel-Tag): " + fileName);
                skippedNoTags++;
                continue;
            }

            String baseName = pattern
                    .replace("{interpret}", artist)
                    .replace("{titel}", title)
                    .replace("{album}", album)
                    .replace("{jahr}", year)
                    .replace("{tracknummer}", track)
                    .replace("{genre}", genre)
                    .trim();
            baseName = sanitizeFileName(baseName);

            if (baseName.isEmpty()) {
                System.out.println("UEBERSPRUNGEN (leerer Zieldateiname): " + fileName);
                skippedNoTags++;
                continue;
            }

            Path newPath = path.resolveSibling(baseName + getExtension(fileName));

            if (newPath.equals(path)) {
                skippedNoChange++;
                continue;
            }
            if (plannedTargets.contains(newPath) || (Files.exists(newPath) && !newPath.equals(path))) {
                System.out.println("UEBERSPRUNGEN (Zielname bereits vergeben): " + fileName
                        + "  ->  " + newPath.getFileName());
                skippedCollision++;
                continue;
            }

            plannedTargets.add(newPath);
            plan.add(new RenamePair(path, newPath));
        }

        if (plan.isEmpty()) {
            System.out.println("Keine Dateien zum Umbenennen gefunden (" + skippedNoTags + " ohne Tags, "
                    + skippedCollision + " Namenskonflikte, " + skippedNoChange + " bereits korrekt benannt).");
            System.exit(0);
            return;
        }

        List<String> previewLines = new ArrayList<>();
        for (RenamePair p : plan) {
            previewLines.add("  " + p.oldPath.getFileName() + "  -->  " + p.newPath.getFileName());
        }
        System.out.println();
        System.out.println("Vorschau (" + plan.size() + " Umbenennungen):");
        printPreview(previewLines, 30);

        if (!promptConfirm(autoConfirm, "Umbenennung von " + plan.size() + " Dateien anhand ihrer Tags durchfuehren?")) {
            System.out.println("Abgebrochen.");
            System.exit(0);
            return;
        }

        writeRenameBackup("rename_tags_backup", folder, plan);

        Progress progress = createProgress("Dateien werden umbenannt...");
        int renamed = 0, failed = 0, total = plan.size(), idx = 0;
        for (RenamePair p : plan) {
            idx++;
            progress.update(idx, total, p.oldPath.getFileName().toString());
            try {
                Files.move(p.oldPath, p.newPath, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("OK: " + p.oldPath.getFileName() + "  -->  " + p.newPath.getFileName());
                renamed++;
            } catch (Exception e) {
                System.out.println("FEHLER bei \"" + p.oldPath.getFileName() + "\": " + e.getMessage());
                failed++;
            }
        }
        progress.close();

        System.out.println();
        System.out.println("Fertig. " + renamed + " Dateien umbenannt, " + skippedNoTags + " ohne Tags uebersprungen, "
                + skippedCollision + " Namenskonflikte, " + skippedNoChange + " bereits korrekt benannt, "
                + failed + " Fehler.");
        System.exit(0);
    }

    // ------------------------------------------------------------------
    // Option 7: Cover-Art einbetten
    // ------------------------------------------------------------------

    private static void embedCoverArt(File folder, File imageFile, boolean autoConfirm) {
        if (!imageFile.isFile()) {
            System.out.println("Bilddatei nicht gefunden: " + imageFile.getAbsolutePath());
            System.exit(1);
            return;
        }
        if (!isImageFile(imageFile)) {
            System.out.println("Keine unterstuetzte Bilddatei (jpg/png): " + imageFile.getAbsolutePath());
            System.exit(1);
            return;
        }

        System.out.println("Durchsuche Ordner nach Audiodateien: " + folder.getAbsolutePath());
        List<Path> audioFiles = collectAudioFiles(folder);
        if (audioFiles == null) return;

        if (audioFiles.isEmpty()) {
            System.out.println("Keine Audiodateien gefunden.");
            System.exit(0);
            return;
        }

        System.out.println();
        System.out.println("Cover \"" + imageFile.getName() + "\" wird in " + audioFiles.size()
                + " Audiodateien eingebettet. Vorhandene Cover werden ersetzt.");
        System.out.println("Hinweis: Fuer eingebettete Cover-Bilder wird kein automatisches Backup erstellt.");

        if (!promptConfirm(autoConfirm, "Fortfahren?")) {
            System.out.println("Abgebrochen.");
            System.exit(0);
            return;
        }

        Progress progress = createProgress("Cover wird eingebettet...");
        int updated = 0, failed = 0, total = audioFiles.size(), idx = 0;
        for (Path path : audioFiles) {
            idx++;
            progress.update(idx, total, path.getFileName().toString());
            try {
                AudioFile f = AudioFileIO.read(path.toFile());
                Tag tag = f.getTagOrCreateAndSetDefault();
                Artwork artwork = ArtworkFactory.createArtworkFromFile(imageFile);
                tag.deleteArtworkField();
                tag.setField(artwork);
                f.commit();
                System.out.println("OK: " + path.getFileName());
                updated++;
            } catch (Exception e) {
                System.out.println("FEHLER bei \"" + path.getFileName() + "\": " + e.getMessage());
                failed++;
            }
        }
        progress.close();

        System.out.println();
        System.out.println("Fertig. " + updated + " Dateien aktualisiert, " + failed + " Fehler.");
        System.exit(0);
    }

    // ------------------------------------------------------------------
    // Gemeinsame Helfer: Index, Audiodateien, Guessing, Sanitizing
    // ------------------------------------------------------------------

    /**
     * Baut einen Index Dateiname -> Pfade fuer alle regulaeren Dateien im Ordner (rekursiv)
     * und warnt bei Namen, die mehrfach vorkommen (z.B. gleicher Dateiname in verschiedenen
     * Unterordnern), damit nicht versehentlich die falsche Datei getroffen wird.
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

        for (Map.Entry<String, List<Path>> entry : index.entrySet()) {
            if (entry.getValue().size() > 1) {
                System.out.println("MEHRDEUTIG: \"" + entry.getKey() + "\" kommt " + entry.getValue().size() + "x vor:");
                for (Path p : entry.getValue()) {
                    System.out.println("    " + p.toAbsolutePath());
                }
            }
        }

        return index;
    }

    private static List<Path> collectAudioFiles(File folder) {
        try (Stream<Path> paths = Files.walk(folder.toPath())) {
            return paths.filter(Files::isRegularFile)
                    .filter(Main::hasAudioExtension)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            System.out.println("Fehler beim Durchsuchen des Ordners: " + e.getMessage());
            System.exit(1);
            return null;
        }
    }

    private static boolean hasAudioExtension(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        for (String ext : AUDIO_EXTENSIONS) if (name.endsWith(ext)) return true;
        return false;
    }

    private static boolean isImageFile(File f) {
        String name = f.getName().toLowerCase();
        for (String ext : IMAGE_EXTENSIONS) if (name.endsWith(ext)) return true;
        return false;
    }

    private static void reportNotFound(List<String> notFound) {
        if (notFound.isEmpty()) return;
        System.out.println();
        System.out.println("Nicht gefunden (" + notFound.size() + "):");
        for (String n : notFound) System.out.println("  NICHT GEFUNDEN: " + n);
    }

    /** Ersetzt fuer Dateisysteme ungueltige Zeichen und normalisiert Leerzeichen. */
    private static String sanitizeFileName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned;
    }

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : "";
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** Versucht Interpret/Titel aus einem Dateinamen nach dem Muster "Interpret - Titel" zu erraten. */
    private static String[] guessFromFilename(String baseName) {
        int idx = baseName.indexOf(" - ");
        if (idx <= 0) return null;
        String artist = baseName.substring(0, idx).trim();
        String title = baseName.substring(idx + 3).trim();
        if (artist.isEmpty() || title.isEmpty()) return null;
        return new String[]{artist, title};
    }

    // ------------------------------------------------------------------
    // Metadaten lesen/schreiben (jaudiotagger) & CSV
    // ------------------------------------------------------------------

    private static String[] padRow(String[] parts) {
        String[] out = new String[COLS];
        Arrays.fill(out, "");
        for (int i = 0; i < Math.min(parts.length, COLS); i++) {
            out[i] = parts[i] == null ? "" : parts[i];
        }
        return out;
    }

    private static String[] readAllFields(Path path) throws Exception {
        String[] out = new String[COLS];
        out[0] = path.getFileName().toString();
        AudioFile f = AudioFileIO.read(path.toFile());
        Tag tag = f.getTag();
        for (int i = 1; i < COLS; i++) {
            out[i] = tag != null ? safeGetField(tag, CSV_FIELDKEYS[i]) : "";
        }
        return out;
    }

    private static void writeTagsExtended(File audioFile, String[] newVals) throws Exception {
        AudioFile f = AudioFileIO.read(audioFile);
        Tag tag = f.getTagOrCreateAndSetDefault();
        for (int i = 1; i < COLS; i++) {
            if (newVals[i] != null && !newVals[i].isEmpty()) {
                tag.setField(CSV_FIELDKEYS[i], newVals[i]);
            }
        }
        f.commit();
    }

    private static String safeGetField(Tag tag, FieldKey key) {
        try {
            String value = tag.getFirst(key);
            return value == null ? "" : value.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeMetaCsv(File outputFile, List<String[]> rows) {
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write(String.join(";", CSV_HEADER) + "\r\n");
            for (String[] row : rows) {
                StringBuilder sb = new StringBuilder();
                for (int c = 0; c < COLS; c++) {
                    if (c > 0) sb.append(";");
                    sb.append(csvEscape(row[c]));
                }
                writer.write(sb + "\r\n");
            }
        } catch (IOException e) {
            System.out.println("Fehler beim Schreiben der Ausgabedatei: " + e.getMessage());
        }
    }

    private static void writeRenameBackup(String prefix, File folder, List<RenamePair> plan) {
        File backupFile = new File(folder, prefix + "_" + timestamp() + ".csv");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(backupFile), StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write("alter_pfad;neuer_pfad\r\n");
            for (RenamePair p : plan) {
                writer.write(csvEscape(p.oldPath.toString()) + ";" + csvEscape(p.newPath.toString()) + "\r\n");
            }
            System.out.println("Backup-Log geschrieben nach: " + backupFile.getAbsolutePath());
        } catch (IOException e) {
            System.out.println("Warnung: Backup-Log konnte nicht geschrieben werden: " + e.getMessage());
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
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

    /**
     * Setzt ein CSV-Feld in Anfuehrungszeichen, falls es das Trennzeichen,
     * Anfuehrungszeichen oder Zeilenumbrueche enthaelt (RFC-4180-artig).
     */
    private static String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(";") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ------------------------------------------------------------------
    // Vorschau / Bestaetigung / Fortschritt
    // ------------------------------------------------------------------

    private static void printPreview(List<String> lines, int maxLines) {
        int shown = Math.min(lines.size(), maxLines);
        for (int i = 0; i < shown; i++) System.out.println(lines.get(i));
        if (lines.size() > maxLines) {
            System.out.println("  ... und " + (lines.size() - maxLines) + " weitere");
        }
    }

    private static boolean promptConfirm(boolean autoConfirm, String message) {
        if (autoConfirm) {
            System.out.println(message + " -> automatisch bestaetigt (--yes)");
            return true;
        }
        Scanner scanner = new Scanner(System.in);
        System.out.print(message + " (j/n): ");
        String answer = scanner.nextLine().trim().toLowerCase();
        return answer.equals("j") || answer.equals("ja") || answer.equals("y") || answer.equals("yes");
    }

    private interface Progress {
        void update(int current, int total, String status);
        void close();
    }

    /** Liefert eine GUI-Fortschrittsanzeige, oder im Headless-Betrieb eine Konsolen-Prozentanzeige. */
    private static Progress createProgress(String title) {
        if (GraphicsEnvironment.isHeadless()) {
            return new Progress() {
                int lastPercent = -1;
                @Override
                public void update(int current, int total, String status) {
                    int percent = total > 0 ? (int) ((current * 100L) / total) : 0;
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        System.out.println("[" + percent + "%] " + status);
                    }
                }
                @Override
                public void close() { }
            };
        }

        JDialog dialog = new JDialog((Frame) null, title, false);
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        JLabel statusLabel = new JLabel(" ");
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(bar, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);
        dialog.add(panel);
        dialog.setSize(420, 100);
        dialog.setLocationRelativeTo(null);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setVisible(true);

        return new Progress() {
            @Override
            public void update(int current, int total, String status) {
                int percent = total > 0 ? (int) ((current * 100L) / total) : 0;
                SwingUtilities.invokeLater(() -> {
                    bar.setValue(Math.min(percent, 100));
                    statusLabel.setText(status);
                });
            }
            @Override
            public void close() {
                SwingUtilities.invokeLater(dialog::dispose);
            }
        };
    }

    // ------------------------------------------------------------------
    // Datenklassen
    // ------------------------------------------------------------------

    private static class RenamePair {
        final Path oldPath;
        final Path newPath;
        RenamePair(Path oldPath, Path newPath) {
            this.oldPath = oldPath;
            this.newPath = newPath;
        }
    }

    private static class MetaChange {
        final Path path;
        final String[] oldVals;
        final String[] newVals;
        MetaChange(Path path, String[] oldVals, String[] newVals) {
            this.path = path;
            this.oldVals = oldVals;
            this.newVals = newVals;
        }
    }
}