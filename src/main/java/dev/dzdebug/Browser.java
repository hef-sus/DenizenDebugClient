package dev.dzdebug;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/** Opens an http(s) link in the system browser without depending on Minecraft's own helpers. */
final class Browser {
    private Browser() {}

    static void open(String url) {
        URI uri = URI.create(url); // throws IllegalArgumentException on a bad link
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return; // never open anything else

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        ProcessBuilder pb;
        if (os.contains("win")) {
            pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toString());
        } else if (os.contains("mac")) {
            pb = new ProcessBuilder("open", uri.toString());
        } else {
            pb = new ProcessBuilder("xdg-open", uri.toString());
        }
        try {
            pb.start();
        } catch (IOException ignored) {
            // the link is already copied to the clipboard
        }
    }
}
