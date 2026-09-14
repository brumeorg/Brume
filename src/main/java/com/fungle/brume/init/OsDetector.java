package com.fungle.brume.init;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class OsDetector {

    public enum Family {
        MACOS, DEBIAN, RHEL, ALPINE, ARCH, WINDOWS, UNKNOWN
    }

    private static final Path OS_RELEASE = Path.of("/etc/os-release");

    public Family detect() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac") || osName.contains("darwin")) return Family.MACOS;
        if (osName.contains("windows")) return Family.WINDOWS;
        if (osName.contains("linux")) return detectLinuxFamily(OS_RELEASE);
        return Family.UNKNOWN;
    }

    static Family detectLinuxFamily(Path osReleasePath) {
        if (!Files.isReadable(osReleasePath)) return Family.UNKNOWN;
        try {
            Map<String, String> entries = Files.readAllLines(osReleasePath).stream()
                    .filter(line -> line.contains("="))
                    .map(line -> line.split("=", 2))
                    .collect(Collectors.toMap(
                            parts -> parts[0].trim(),
                            parts -> stripQuotes(parts[1].trim()),
                            (a, b) -> a));
            List<String> ids = Arrays.asList(
                    entries.getOrDefault("ID", "").toLowerCase(Locale.ROOT),
                    entries.getOrDefault("ID_LIKE", "").toLowerCase(Locale.ROOT));
            String joined = String.join(" ", ids);
            if (joined.contains("debian") || joined.contains("ubuntu")) return Family.DEBIAN;
            if (joined.contains("rhel") || joined.contains("fedora") || joined.contains("centos")
                    || joined.contains("rocky") || joined.contains("alma")) return Family.RHEL;
            if (joined.contains("alpine")) return Family.ALPINE;
            if (joined.contains("arch")) return Family.ARCH;
            return Family.UNKNOWN;
        } catch (IOException e) {
            return Family.UNKNOWN;
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    public String installCommandFor(Family family) {
        int v = PgDumpChecker.REQUIRED_MAJOR_VERSION;
        return switch (family) {
            case MACOS -> "brew install postgresql@" + v;
            case DEBIAN -> "sudo apt-get install -y postgresql-client-" + v;
            case RHEL -> "sudo dnf install -y postgresql" + v;
            case ALPINE -> "sudo apk add postgresql" + v + "-client";
            case ARCH -> "sudo pacman -S postgresql";
            case WINDOWS -> "Download the PostgreSQL " + v + " installer from https://www.postgresql.org/download/windows/";
            case UNKNOWN -> "Install pg_dump " + v + " via your OS package manager (see https://www.postgresql.org/download/)";
        };
    }
}
