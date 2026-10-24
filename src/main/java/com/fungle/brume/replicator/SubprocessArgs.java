package com.fungle.brume.replicator;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Command lines whose non-ASCII arguments reach the child process intact (#26d).
 *
 * <p>Java encodes the arguments of a subprocess with the platform encoding
 * ({@code sun.jnu.encoding}). Under a non-UTF-8 locale — a CI container, a server with
 * {@code LANG=C}, the native binary in GitLab CI — it is ASCII, and the schema name
 * {@code "E2E Édition"} reaches {@code pg_dump} as {@code "E2E ?dition"} : no schema matches.
 *
 * <p>A command made of ASCII arguments is returned unchanged. Otherwise it runs through
 * {@code /bin/sh -c} with a script that is pure ASCII : every non-ASCII argument is rebuilt by
 * {@code printf} from octal escapes of its UTF-8 bytes, and {@code exec} replaces the shell with
 * the command (same process, so cancellation still reaches it). An argument ending with a line
 * break loses it (command substitution) — never the case for a name, a host or an option.
 */
final class SubprocessArgs {

    private SubprocessArgs() {
    }

    static List<String> portable(List<String> command) {
        if (command.stream().allMatch(SubprocessArgs::isAscii)) {
            return command;
        }
        StringBuilder script = new StringBuilder("exec");
        for (String arg : command) {
            script.append(' ');
            if (isAscii(arg)) {
                script.append('\'').append(arg.replace("'", "'\\''")).append('\'');
            } else {
                script.append("\"$(printf '").append(printfEscaped(arg)).append("')\"");
            }
        }
        return List.of("/bin/sh", "-c", script.toString());
    }

    private static boolean isAscii(String s) {
        return s.chars().allMatch(c -> c < 0x80);
    }

    /** A printf format, inside single quotes, that prints exactly the UTF-8 bytes of {@code arg}. */
    private static String printfEscaped(String arg) {
        StringBuilder out = new StringBuilder();
        for (byte b : arg.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c >= 0x80) {
                out.append('\\').append(String.format("%03o", c));
            } else if (c == '%') {
                out.append("%%");
            } else if (c == '\\') {
                out.append("\\\\");
            } else if (c == '\'') {
                out.append("'\\''");
            } else {
                out.append((char) c);
            }
        }
        return out.toString();
    }
}
