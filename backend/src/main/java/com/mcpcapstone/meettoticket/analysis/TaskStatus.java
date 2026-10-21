package com.mcpcapstone.meettoticket.analysis;

public enum TaskStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    PARTIALLY_FAILED,
    FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == PARTIALLY_FAILED || this == FAILED;
    }
}
