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
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

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

        if (!isFfmpegAvailable() || !isFfprobeAvailable()) {
            System.out.println("FEHLER: 'ffmpeg'/'ffprobe' wurde nicht gefunden (Aufruf '(ffmpeg|ffprobe) -version' fehlgeschlagen).");
            System.out.println("Bitte ffmpeg installieren und sicherstellen, dass es im PATH liegt: https://ffmpeg.org/download.html");
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
     * Schreibt Interpret und/oder Album in die Metadaten einer Audiodatei mittels ffmpeg.
     * ffmpeg remuxt Container wie MP4/M4A sauber neu (anders als jaudiotagger, das bei
     * vielen "unsauberen" M4A-Dateien - z.B. aus yt-dlp/ffmpeg-Remuxes - an falsch
     * berechneten Byte-Offsets scheitert). Audio-/Videodaten werden dabei unveraendert
     * kopiert (-codec copy), es wird also nicht neu enkodiert.
     * <p>
     * Die Metadaten werden ueber eine separate UTF-8-FFMETADATA-Datei uebergeben statt als
     * Kommandozeilenargument, damit nicht-lateinische Zeichen (Kyrillisch, Japanisch, ...)
     * nicht durch Encoding-Probleme der Kommandozeile (insbesondere unter Windows) beschaedigt werden.
     * <p>
     * Manche Dateien tragen eine "falsche" Dateiendung (z.B. eine als .mp3 gespeicherte
     * Datei, die tatsaechlich AAC/M4A-Inhalt enthaelt - typisch bei manchen "YouTube zu
     * MP3"-Downloadern). Der zum jeweiligen Container passende Muxer wird von ffmpeg anhand
     * der Zieldateiendung gewaehlt; passt die Endung nicht zum tatsaechlichen Audio-Codec,
     * schlaegt das Schreiben fehl ("Could not write header"). Deshalb wird der tatsaechliche
     * Audio-Codec vorab per ffprobe ermittelt; bei einer Diskrepanz wird die Datei automatisch
     * auf die passende Endung umbenannt.
     */
    private static void writeTags(File audioFile, String interpret, String album) throws IOException, InterruptedException {
        String fileName = audioFile.getName();
        int dotIdx = fileName.lastIndexOf('.');
        String originalExtension = dotIdx >= 0 ? fileName.substring(dotIdx) : "";
        String baseName = dotIdx >= 0 ? fileName.substring(0, dotIdx) : fileName;
        File parentDir = audioFile.getParentFile();

        String codec = detectAudioCodec(audioFile);
        String codecExtension = extensionForCodec(codec);
        String outputExtension = (codecExtension != null) ? codecExtension : originalExtension;

        File tempOutput = File.createTempFile("meta_out_", outputExtension, parentDir);
        File metaFile = File.createTempFile("ffmetadata_", ".txt", parentDir);

        try {
            StringBuilder meta = new StringBuilder(";FFMETADATA1\n");
            if (!interpret.isEmpty()) {
                meta.append("artist=").append(escapeFfmetadata(interpret)).append("\n");
            }
            if (!album.isEmpty()) {
                meta.append("album=").append(escapeFfmetadata(album)).append("\n");
            }
            Files.write(metaFile.toPath(), meta.toString().getBytes(StandardCharsets.UTF_8));

            // "-map 0:a" statt "-map 0": nur den/die Audio-Stream(s) uebernehmen.
            // Manche Dateien enthalten als ID3-Cover ein eingebettetes Bild, das ffmpeg als
            // eigenen Video-Stream sieht. Bei "-map 0" wird dieser Stream mitkopiert, und der
            // MP3-Muxer bricht dabei teils mit "Could not write header (incorrect codec
            // parameters?)" ab. Da es hier nur um Text-Metadaten (Interpret/Album) geht, wird
            // das Cover bewusst nicht mit uebernommen.
            List<String> command = List.of(
                    "ffmpeg", "-y",
                    "-i", audioFile.getAbsolutePath(),
                    "-f", "ffmetadata",
                    "-i", metaFile.getAbsolutePath(),
                    "-map", "0:a",
                    "-map_metadata", "1",
                    "-codec", "copy",
                    tempOutput.getAbsolutePath()
            );

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = readAll(process.getInputStream());
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("ffmpeg-Timeout (>60s)");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0 || !tempOutput.exists() || tempOutput.length() == 0) {
                throw new IOException("ffmpeg-Exitcode " + exitCode + ": " + lastLines(output, 2));
            }

            File targetFile = audioFile;
            if (!outputExtension.equalsIgnoreCase(originalExtension)) {
                File renamed = new File(parentDir, baseName + outputExtension);
                if (renamed.exists()) {
                    // Kollision vermeiden statt versehentlich eine andere, gleichnamige Datei zu ueberschreiben
                    renamed = new File(parentDir, baseName + "_fixed" + outputExtension);
                }
                System.out.println("HINWEIS: \"" + fileName + "\" enthaelt tatsaechlich " + codec
                        + "-Audio (kein MP3) und wird nach \"" + renamed.getName() + "\" umbenannt.");
                targetFile = renamed;
            }

            Files.move(tempOutput.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (!targetFile.equals(audioFile)) {
                Files.deleteIfExists(audioFile.toPath());
            }
        } finally {
            Files.deleteIfExists(metaFile.toPath());
            Files.deleteIfExists(tempOutput.toPath()); // no-op, falls bereits verschoben
        }
    }

    /**
     * Ermittelt per ffprobe den tatsaechlichen Codec des ersten Audio-Streams einer Datei.
     * Gibt null zurueck, wenn ffprobe fehlschlaegt oder kein Audio-Stream gefunden wird.
     */
    private static String detectAudioCodec(File audioFile) throws InterruptedException {
        try {
            List<String> command = List.of(
                    "ffprobe", "-v", "error",
                    "-select_streams", "a:0",
                    "-show_entries", "stream=codec_name",
                    "-of", "csv=p=0",
                    audioFile.getAbsolutePath()
            );
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = readAll(process.getInputStream());
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) return null;

            String codec = output.strip();
            return codec.isEmpty() ? null : codec;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Ordnet einem von ffprobe gemeldeten Audio-Codec die passende Container-Dateiendung zu,
     * damit ffmpeg beim Schreiben den korrekten Muxer waehlt. Gibt null zurueck, wenn der
     * Codec unbekannt/nicht abgedeckt ist (dann wird die urspruengliche Endung beibehalten).
     */
    private static String extensionForCodec(String codecName) {
        if (codecName == null) return null;
        return switch (codecName) {
            case "mp3" -> ".mp3";
            case "aac", "alac" -> ".m4a";
            case "vorbis" -> ".ogg";
            case "opus" -> ".opus";
            case "flac" -> ".flac";
            case "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_u8", "pcm_s16be", "pcm_s24be" -> ".wav";
            case "wmav1", "wmav2" -> ".wma";
            case "ac3" -> ".ac3";
            case "eac3" -> ".eac3";
            default -> null;
        };
    }

    /**
     * Escaped Sonderzeichen im FFMETADATA1-Format (siehe ffmpeg-Dokumentation):
     * Backslash, Gleichheitszeichen, Semikolon, Raute und Zeilenumbruch muessen
     * mit einem Backslash maskiert werden.
     */
    private static String escapeFfmetadata(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '=' || c == ';' || c == '#' || c == '\n') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String lastLines(String text, int n) {
        String[] lines = text.strip().split("\\R");
        int start = Math.max(0, lines.length - n);
        return String.join(" | ", Arrays.copyOfRange(lines, start, lines.length)).strip();
    }

    /**
     * Prueft, ob 'ffmpeg' aufrufbar ist (im PATH liegt).
     */
    private static boolean isFfmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes(); // Puffer leeren, sonst kann der Prozess haengen
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Prueft, ob 'ffprobe' aufrufbar ist (im PATH liegt). Wird benoetigt, um den
     * tatsaechlichen Audio-Codec einer Datei zu ermitteln (siehe detectAudioCodec).
     */
    private static boolean isFfprobeAvailable() {
        try {
            Process p = new ProcessBuilder("ffprobe", "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
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