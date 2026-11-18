package ru.nsu.newspaper;

import ru.nsu.newspaper.server.HttpIssueServer;
import java.nio.file.Path;
import java.util.Arrays;

public final class Launcher {
    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--server")) {
            Path folder = Path.of(option(args, "--data-dir", defaultDataDirectory()));
            int port = Integer.parseInt(option(args, "--port", "7070"));
            var server = new HttpIssueServer(folder, option(args, "--host", "0.0.0.0"), port);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { server.close(); } catch (Exception e) { System.err.println(e.getMessage()); }
            }, "server-shutdown"));
            System.out.println("Newspaper server listening on port " + server.port());
            System.out.println("Issue files: " + folder.toAbsolutePath().resolve("issues"));
        } else {
            ClientLauncher.start(args);
        }
    }
    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(name)) {
                if (i + 1 == args.length) throw new IllegalArgumentException("Missing value for " + name);
                return args[i + 1];
            }
        }
        return fallback;
    }
    private static String defaultDataDirectory() {
        String local = System.getenv("LOCALAPPDATA");
        return local != null ? Path.of(local, "PeriodicalPublishing").toString()
                : Path.of(System.getProperty("user.home"), ".local", "share", "periodical-publishing").toString();
    }
}
