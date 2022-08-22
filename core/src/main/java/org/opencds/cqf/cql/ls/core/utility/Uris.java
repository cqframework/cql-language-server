package org.opencds.cqf.cql.ls.core.utility;

import java.io.File;
import java.net.URI;

public class Uris {
    private Uris() {}

    public static URI getHead(URI uri) {
        String path = uri.getRawPath();
        if (path != null) {
            int index = path.lastIndexOf("/");
            if (index > -1) {
                return withPath(uri, path.substring(0, index));
            }

            return uri;
        }

        return uri;
    }

    public static URI withPath(URI uri, String path) {
        try {
            return URI.create((uri.getScheme() != null ? uri.getScheme() + ":" : "") + "//"
                    + createAuthority(uri.getRawAuthority()) + createPath(path)
                    + createQuery(uri.getRawQuery()) + createFragment(uri.getRawFragment()));
        } catch (Exception e) {
            return null;
        }
    }

    public static URI addPath(URI uri, String path) {
        return withPath(uri, stripTrailingSlash(uri.getRawPath()) + createPath(path));
    }

    public static URI parseOrNull(String uriString) {
        try {
            return new URI(uriString);
        } catch (Exception e) {
            return null;
        }
    }

    public static String fixUri(String uri) {
        // When running on Windows, URIs seem to come back with colons encoded in the path.
        // This appears to be an incorrect encoding for URIs:
        // https://www.rfc-editor.org/rfc/rfc3986#section-3.3
        // And this seems to be something that other language servers are accounting for as well:
        // https://github.com/eclipse/eclipse.jdt.ls/blob/a996b57495a150f31c8c2e61110ce8032086f2a1/org.eclipse.jdt.ls.core/src/org/eclipse/jdt/ls/core/internal/JDTUtils.java#L1088
        // This is a super simple and naive "fix" for this, but it seems preferable to trying to
        // "decode" the URI, because it's supposed to be a valid URI in the first place.
        if (uri.startsWith("file:///")) {
            return uri.replace("%3A", ":").replace("%3a", ":");
        }

        return uri;
    }


    public static URI fixUri(URI uri) {
        return parseOrNull(fixUri(uri.toString()));
    }

    public static URI normalizeUri(URI uri) {
        return new File(uri).toURI();
    }

    private static String createAuthority(String rawAuthority) {
        return rawAuthority != null ? rawAuthority : "";
    }

    private static String stripTrailingSlash(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }

        if (path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }

        return path;
    }

    private static String createPath(String pathValue) {
        return ensurePrefix("/", pathValue);
    }

    private static String createQuery(String queryValue) {
        return ensurePrefix("?", queryValue);
    }

    private static String createFragment(String fragmentValue) {
        return ensurePrefix("#", fragmentValue);
    }

    private static String ensurePrefix(String prefix, String value) {
        if (value == null || value.isEmpty()) {
            return "";
        } else if (value.startsWith(prefix)) {
            return value;
        } else {
            return prefix + value;
        }
    }

}
