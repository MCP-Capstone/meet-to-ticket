package com.mcpcapstone.meettoticket.analysis;

public enum PublishBatchStatus {
    RUNNING,
    SUCCEEDED,
    PARTIAL_FAILED,
    FAILED;

    public boolean terminal() {
        return this != RUNNING;
    }
}
