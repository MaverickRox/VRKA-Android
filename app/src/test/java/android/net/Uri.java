package android.net;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JVM test shadow for android.net.Uri.
 * Provides working URI parsing and field getters for Media3 manifest parsers.
 */
public class Uri implements Comparable<Uri> {
    public static final Uri EMPTY = new Uri("");

    private final String uriString;
    private final URI javaUri;

    public Uri(String uriString) {
        this.uriString = uriString == null ? "" : uriString;
        URI parsed;
        try {
            parsed = URI.create(this.uriString);
        } catch (Exception e) {
            try {
                parsed = new URI("http", "localhost", "/", null);
            } catch (Exception ignored) {
                parsed = null;
            }
        }
        this.javaUri = parsed;
    }

    public static Uri parse(String uriString) {
        return new Uri(uriString);
    }

    @Override
    public String toString() {
        return uriString;
    }

    public String getScheme() {
        return javaUri != null ? javaUri.getScheme() : null;
    }

    public String getAuthority() {
        return javaUri != null ? javaUri.getAuthority() : null;
    }

    public String getEncodedAuthority() {
        return javaUri != null ? javaUri.getRawAuthority() : null;
    }

    public String getPath() {
        return javaUri != null ? javaUri.getPath() : null;
    }

    public String getEncodedPath() {
        return javaUri != null ? javaUri.getRawPath() : null;
    }

    public List<String> getPathSegments() {
        String p = getPath();
        if (p == null || p.isEmpty()) return Collections.emptyList();
        String[] parts = p.split("/");
        List<String> list = new ArrayList<>();
        for (String s : parts) {
            if (!s.isEmpty()) list.add(s);
        }
        return list;
    }

    public String getLastPathSegment() {
        List<String> segments = getPathSegments();
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }

    public String getQuery() {
        return javaUri != null ? javaUri.getQuery() : null;
    }

    public String getEncodedQuery() {
        return javaUri != null ? javaUri.getRawQuery() : null;
    }

    public String getFragment() {
        return javaUri != null ? javaUri.getFragment() : null;
    }

    public String getEncodedFragment() {
        return javaUri != null ? javaUri.getRawFragment() : null;
    }

    public String getHost() {
        return javaUri != null ? javaUri.getHost() : null;
    }

    public int getPort() {
        return javaUri != null ? javaUri.getPort() : -1;
    }

    public boolean isAbsolute() {
        return javaUri != null && javaUri.isAbsolute();
    }

    public boolean isHierarchical() {
        return true;
    }

    public boolean isRelative() {
        return !isAbsolute();
    }

    @Override
    public int compareTo(Uri o) {
        return uriString.compareTo(o.uriString);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Uri)) return false;
        return uriString.equals(((Uri) o).uriString);
    }

    @Override
    public int hashCode() {
        return uriString.hashCode();
    }
}
