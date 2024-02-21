package org.opencds.cqf.cql.ls.server.event;

abstract class Event<T> {

    private T params;

    protected Event(T params) {
        this.params = params;
    }

    public T params() {
        return this.params;
    }
}
