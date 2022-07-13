package org.opencds.cqf.cql.ls.server;

public class VersionedContent {
    public final String content;
    public final int version;

    public VersionedContent(String content, int version) {
        this.content = content;
        this.version = version;
    }
}
