package org.opencds.cqf.cql.ls.server;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("serial")
public class ActiveContent extends ConcurrentHashMap<URI, VersionedContent> {
}
