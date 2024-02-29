package org.opencds.cqf.cql.ls.server.event;

abstract class BaseEvent<T> {

    private T params;

    protected BaseEvent(T params) {
        this.params = params;
    }

    public T params() {
        return this.params;
    }
}
