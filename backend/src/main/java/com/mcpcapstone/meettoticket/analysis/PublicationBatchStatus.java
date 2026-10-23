package com.mcpcapstone.meettoticket.analysis;

public enum PublicationBatchStatus {
    RUNNING,
    ACCEPTANCE_UNCERTAIN,
    SUCCEEDED,
    PARTIAL_FAILED,
    FAILED;

    public boolean terminal() {
        return this == SUCCEEDED || this == PARTIAL_FAILED || this == FAILED;
    }
}
