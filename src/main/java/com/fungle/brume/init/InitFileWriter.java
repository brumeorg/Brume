package com.fungle.brume.init;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class InitFileWriter {

    private final Path targetDir;

    public InitFileWriter(Path targetDir) {
        this.targetDir = targetDir;
    }

    public enum Outcome { CREATED, OVERWRITTEN, SKIPPED }

    public Outcome writeFromClasspath(String resource, String targetFilename,
                                      boolean overwriteExisting) throws IOException {
        Path target = targetDir.resolve(targetFilename);
        boolean exists = Files.exists(target);
        if (exists && !overwriteExisting) return Outcome.SKIPPED;
        try (InputStream in = InitFileWriter.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Classpath resource not found: " + resource);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return exists ? Outcome.OVERWRITTEN : Outcome.CREATED;
    }

    public boolean exists(String targetFilename) {
        return Files.exists(targetDir.resolve(targetFilename));
    }

    public Path pathOf(String targetFilename) {
        return targetDir.resolve(targetFilename);
    }
}
