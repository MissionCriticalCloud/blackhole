package com.cloud.legacymodel.exceptions;

public class InsufficientNetworkCapacityException extends InsufficientCapacityException {
    protected InsufficientNetworkCapacityException() {
        super();
    }

    public InsufficientNetworkCapacityException(final String msg, final Class<?> scope, final Long id) {
        super(msg, scope, id);
    }
}
