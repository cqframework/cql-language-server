package org.opencds.cqf.cql.ls.core.utility;

import java.net.URI;

public class Uris {
    private Uris() {}

    public static URI getHead(URI uri) {
        String path = uri.getPath();
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
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), path,
                    uri.getFragment(), uri.getQuery());
        } catch (Exception e) {
            return null;
        }
    }

    public static URI addPath(URI uri, String path) {
        return withPath(uri, uri.getPath() + path);
    }

    public static URI parseOrNull(String uriString) {
        try {
            return new URI(uriString);
        } catch (Exception e) {
            return null;
        }
    }

}
