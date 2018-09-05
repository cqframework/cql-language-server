package org.cqframework.cql;

class VersionedContent {
    final String content;
    final int version;

    VersionedContent(String content, int version) {
        this.content = content;
        this.version = version;
    }
}
