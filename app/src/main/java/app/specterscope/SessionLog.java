package app.specterscope;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Investigation log: kept in memory and appended to a text file so it survives restarts. */
final class SessionLog {
    private final List<String> lines = new ArrayList<String>();
    private final File file;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    SessionLog(File dir) {
        file = new File(dir, "session_log.txt");
        if (file.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                String s;
                while ((s = r.readLine()) != null) lines.add(s);
            } catch (IOException ignored) {
            }
            while (lines.size() > 400) lines.remove(0);
        }
    }

    synchronized void add(String type, String msg) {
        String line = fmt.format(new Date()) + "  [" + type + "] " + msg;
        lines.add(line);
        if (lines.size() > 400) lines.remove(0);
        try (FileWriter w = new FileWriter(file, true)) {
            w.write(line + "\n");
        } catch (IOException ignored) {
        }
    }

    synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        return sb.toString();
    }

    synchronized int size() {
        return lines.size();
    }

    synchronized void clear() {
        lines.clear();
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
