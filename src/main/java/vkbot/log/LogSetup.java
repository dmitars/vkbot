package vkbot.log;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

/**
 * Настройка логирования: всё пишется в окно консоли (UTF-8) и, опционально, в файл.
 */
public final class LogSetup {

    private LogSetup() {
    }

    /** Переключает System.out/err на нужную кодировку, чтобы кириллица в консоли Windows не ломалась. */
    public static void setupConsoleEncoding(String encoding) {
        try {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, encoding));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, encoding));
        } catch (UnsupportedEncodingException e) {
            System.err.println("Неизвестная кодировка консоли: " + encoding);
        }
    }

    public static void init(Level level, Path logFile) {
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) {
            root.removeHandler(h);
        }
        root.setLevel(level);

        Handler console = new StreamHandler(System.out, new ConsoleFormatter()) {
            @Override
            public synchronized void publish(LogRecord record) {
                super.publish(record);
                flush();
            }
        };
        console.setLevel(level);
        root.addHandler(console);

        if (logFile != null) {
            try {
                if (logFile.getParent() != null) {
                    Files.createDirectories(logFile.getParent());
                }
                FileHandler fh = new FileHandler(logFile.toString(), true);
                fh.setEncoding("UTF-8");
                fh.setFormatter(new FileFormatter());
                fh.setLevel(Level.ALL);
                root.addHandler(fh);
            } catch (IOException e) {
                Logger.getLogger(LogSetup.class.getName())
                        .warning("Не удалось открыть файл лога " + logFile + ": " + e.getMessage());
            }
        }
    }

    public static Level parseLevel(String s) {
        if (s == null) {
            return Level.INFO;
        }
        switch (s.trim().toUpperCase()) {
            case "DEBUG":
                return Level.FINE;
            case "TRACE":
                return Level.FINEST;
            case "WARN":
            case "WARNING":
                return Level.WARNING;
            case "ERROR":
                return Level.SEVERE;
            default:
                try {
                    return Level.parse(s.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    return Level.INFO;
                }
        }
    }

    private static String levelName(Level l) {
        if (l.intValue() >= Level.SEVERE.intValue()) {
            return "ОШИБКА";
        }
        if (l.intValue() >= Level.WARNING.intValue()) {
            return "ВНИМАНИЕ";
        }
        if (l.intValue() >= Level.INFO.intValue()) {
            return "INFO";
        }
        return "DEBUG";
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /** Короткий формат для консоли; стектрейс показывается только при уровне DEBUG. */
    static final class ConsoleFormatter extends Formatter {
        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

        @Override
        public String format(LogRecord r) {
            StringBuilder sb = new StringBuilder();
            sb.append(LocalDateTime.now().format(TIME)).append(' ')
                    .append(String.format("%-8s", levelName(r.getLevel()))).append(' ')
                    .append(formatMessage(r)).append(System.lineSeparator());
            if (r.getThrown() != null) {
                Logger root = Logger.getLogger("");
                if (root.getLevel() != null && root.getLevel().intValue() <= Level.FINE.intValue()) {
                    sb.append(stackTrace(r.getThrown()));
                } else {
                    sb.append("         Причина: ").append(r.getThrown()).append(System.lineSeparator());
                }
            }
            return sb.toString();
        }
    }

    /** Подробный формат для файла — всегда со стектрейсом. */
    static final class FileFormatter extends Formatter {
        private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        @Override
        public String format(LogRecord r) {
            StringBuilder sb = new StringBuilder();
            sb.append(LocalDateTime.now().format(TS)).append(' ')
                    .append(String.format("%-8s", levelName(r.getLevel()))).append(' ')
                    .append(r.getLoggerName()).append(" - ")
                    .append(formatMessage(r)).append(System.lineSeparator());
            if (r.getThrown() != null) {
                sb.append(stackTrace(r.getThrown()));
            }
            return sb.toString();
        }
    }
}
