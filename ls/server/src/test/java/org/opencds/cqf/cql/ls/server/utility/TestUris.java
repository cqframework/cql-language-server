package org.opencds.cqf.cql.ls.server.utility;

import java.net.URI;
import org.opencds.cqf.cql.ls.core.utility.Uris;

public class TestUris {

    private TestUris() {}

    private static String prependPath(String resourcePath) {
        return "file:/org/opencds/cqf/cql/ls/server/" + resourcePath;
    }

    public static URI forPath(String resourcePath) {
        return Uris.parseOrNull(prependPath(resourcePath));
    }
}
