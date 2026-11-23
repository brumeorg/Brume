package com.fungle.brume.command;

import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class BrumeVersionProvider implements CommandLine.IVersionProvider {

    static final String POM_PROPERTIES =
            "/META-INF/maven/com.fungle/brume/pom.properties";

    /**
     * Written by Maven resource filtering ({@code src/main/resources-filtered}), so present in
     * {@code target/classes} — the native image is compiled from there, without the jar
     * MANIFEST nor {@code META-INF/maven} : the native binary printed {@code brume dev} (#127g).
     */
    public static final String VERSION_RESOURCE = "brume-version.properties";

    @Override
    public String[] getVersion() {
        return new String[]{"brume " + resolveVersion()};
    }

    /**
     * The version of this build, for every place that prints it ({@code --version}, {@code diag},
     * the reports) : reading the MANIFEST directly gives nothing in the native binary (GitHub #1).
     */
    public static String resolveVersion() {
        String v = readVersion("/" + VERSION_RESOURCE);
        if (v == null) {
            v = BrumeVersionProvider.class.getPackage().getImplementationVersion();
        }
        if (v == null) {
            v = readFromPomProperties();
        }
        return v != null ? v : "dev";
    }

    static String readFromPomProperties() {
        return readVersion(POM_PROPERTIES);
    }

    private static String readVersion(String resource) {
        try (InputStream in = BrumeVersionProvider.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            Properties props = new Properties();
            props.load(in);
            String v = props.getProperty("version");
            return (v == null || v.isBlank() || v.startsWith("@")) ? null : v; // unfiltered resource
        } catch (IOException e) {
            return null;
        }
    }
}
