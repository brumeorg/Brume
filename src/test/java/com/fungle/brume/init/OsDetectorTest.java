package com.fungle.brume.init;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OsDetectorTest {

    private final OsDetector detector = new OsDetector();

    @Test
    @DisplayName("detects Debian family from /etc/os-release with ID=debian")
    void detectsDebian(@TempDir Path tmp) throws IOException {
        Path osRelease = writeOsRelease(tmp, """
                NAME="Debian GNU/Linux"
                ID=debian
                VERSION_ID="12"
                """);
        assertThat(OsDetector.detectLinuxFamily(osRelease)).isEqualTo(OsDetector.Family.DEBIAN);
    }

    @Test
    @DisplayName("detects Debian family from ID_LIKE=debian on Ubuntu")
    void detectsUbuntuViaIdLike(@TempDir Path tmp) throws IOException {
        Path osRelease = writeOsRelease(tmp, """
                NAME="Ubuntu"
                ID=ubuntu
                ID_LIKE=debian
                """);
        assertThat(OsDetector.detectLinuxFamily(osRelease)).isEqualTo(OsDetector.Family.DEBIAN);
    }

    @Test
    @DisplayName("detects RHEL family from ID=fedora")
    void detectsFedora(@TempDir Path tmp) throws IOException {
        Path osRelease = writeOsRelease(tmp, """
                NAME=Fedora
                ID=fedora
                """);
        assertThat(OsDetector.detectLinuxFamily(osRelease)).isEqualTo(OsDetector.Family.RHEL);
    }

    @Test
    @DisplayName("detects RHEL family from ID=rocky and ID_LIKE containing rhel")
    void detectsRocky(@TempDir Path tmp) throws IOException {
        Path osRelease = writeOsRelease(tmp, """
                NAME="Rocky Linux"
                ID="rocky"
                ID_LIKE="rhel centos fedora"
                """);
        assertThat(OsDetector.detectLinuxFamily(osRelease)).isEqualTo(OsDetector.Family.RHEL);
    }

    @Test
    @DisplayName("detects Alpine family")
    void detectsAlpine(@TempDir Path tmp) throws IOException {
        Path osRelease = writeOsRelease(tmp, """
                NAME="Alpine Linux"
                ID=alpine
                """);
        assertThat(OsDetector.detectLinuxFamily(osRelease)).isEqualTo(OsDetector.Family.ALPINE);
    }

    @Test
    @DisplayName("detects Arch family")
    void detectsArch(@TempDir Path tmp) throws IOException {
        Path osRelease = writeOsRelease(tmp, """
                NAME="Arch Linux"
                ID=arch
                """);
        assertThat(OsDetector.detectLinuxFamily(osRelease)).isEqualTo(OsDetector.Family.ARCH);
    }

    @Test
    @DisplayName("returns UNKNOWN when /etc/os-release is missing")
    void unknownWhenMissing(@TempDir Path tmp) {
        Path missing = tmp.resolve("no-such-file");
        assertThat(OsDetector.detectLinuxFamily(missing)).isEqualTo(OsDetector.Family.UNKNOWN);
    }

    @Test
    @DisplayName("returns UNKNOWN on an unrecognized distro")
    void unknownOnUnrecognized(@TempDir Path tmp) throws IOException {
        Path osRelease = writeOsRelease(tmp, """
                NAME=Exotica
                ID=exotica
                """);
        assertThat(OsDetector.detectLinuxFamily(osRelease)).isEqualTo(OsDetector.Family.UNKNOWN);
    }

    @Test
    @DisplayName("install commands are family-specific and mention PG 17")
    void installCommandsMentionPg17() {
        assertThat(detector.installCommandFor(OsDetector.Family.MACOS)).contains("brew").contains("17");
        assertThat(detector.installCommandFor(OsDetector.Family.DEBIAN)).contains("apt-get").contains("17");
        assertThat(detector.installCommandFor(OsDetector.Family.RHEL)).contains("dnf").contains("17");
        assertThat(detector.installCommandFor(OsDetector.Family.ALPINE)).contains("apk").contains("17");
        assertThat(detector.installCommandFor(OsDetector.Family.ARCH)).contains("pacman");
        assertThat(detector.installCommandFor(OsDetector.Family.WINDOWS)).contains("postgresql.org");
        assertThat(detector.installCommandFor(OsDetector.Family.UNKNOWN)).contains("postgresql.org");
    }

    private static Path writeOsRelease(Path dir, String content) throws IOException {
        Path p = dir.resolve("os-release");
        Files.writeString(p, content);
        return p;
    }
}
