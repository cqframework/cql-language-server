package org.opencds.cqf.cql.ls.server.event

abstract class BaseEvent<T>(private val params: T) {
    fun params(): T = params
}
