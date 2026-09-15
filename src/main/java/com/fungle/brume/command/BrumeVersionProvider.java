package com.fungle.brume.command;

import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class BrumeVersionProvider implements CommandLine.IVersionProvider {

    static final String POM_PROPERTIES =
            "/META-INF/maven/com.fungle/brume/pom.properties";

    @Override
    public String[] getVersion() {
        return new String[]{"brume " + resolveVersion()};
    }

    static String resolveVersion() {
        String v = BrumeVersionProvider.class.getPackage().getImplementationVersion();
        if (v == null) {
            v = readFromPomProperties();
        }
        return v != null ? v : "dev";
    }

    static String readFromPomProperties() {
        try (InputStream in = BrumeVersionProvider.class.getResourceAsStream(POM_PROPERTIES)) {
            if (in == null) return null;
            Properties props = new Properties();
            props.load(in);
            String v = props.getProperty("version");
            return (v == null || v.isBlank()) ? null : v;
        } catch (IOException e) {
            return null;
        }
    }
}
