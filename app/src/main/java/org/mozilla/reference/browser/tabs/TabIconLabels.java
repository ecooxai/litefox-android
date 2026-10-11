package org.mozilla.reference.browser.tabs;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;

/** Labels never expose a URL's user-info, query, or fragment. */
public final class TabIconLabels {
    private TabIconLabels() { }

    public static String host(String url) {
        if (url == null || url.trim().isEmpty()) return "New tab";
        String raw = url.trim();
        if (raw.equals("about:blank") || raw.equals("about:privatebrowsing")) return "New tab";
        try {
            URI uri = URI.create(raw.contains(":") ? raw : "https://" + raw);
            String host = uri.getHost();
            if (host == null && uri.getRawAuthority() != null) {
                host = uri.getRawAuthority();
                host = host.substring(host.lastIndexOf('@') + 1);
                if (!host.startsWith("[")) host = host.split(":", 2)[0];
            }
            if (host != null && !host.trim().isEmpty()) {
                host = IDN.toUnicode(host).toLowerCase(Locale.ROOT);
                return host.startsWith("www.") ? host.substring(4) : host;
            }
        } catch (IllegalArgumentException ignored) {
            // Malformed or non-web URLs get a safe, non-sensitive label.
        }
        return "Local page";
    }

    public static String initial(String host) {
        if (host == null || host.trim().isEmpty()) return "?";
        for (int offset = 0; offset < host.length();) {
            int cp = host.codePointAt(offset);
            if (Character.isLetterOrDigit(cp)) {
                return new String(Character.toChars(cp)).toUpperCase(Locale.ROOT);
            }
            offset += Character.charCount(cp);
        }
        return "?";
    }
}
