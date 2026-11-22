package com.lab.camel.remediation;

public record AiRemediationDecision(
        boolean approved,
        String status,
        String reason,
        String runbookRef,
        String correctedPayload,
        int nextRetryCount
) {
    public static AiRemediationDecision approved(String runbookRef, String correctedPayload, int nextRetryCount) {
        return new AiRemediationDecision(true, "APPROVED", "Validation politique et contrat Apicurio OK", runbookRef, correctedPayload, nextRetryCount);
    }

    public static AiRemediationDecision rejected(String status, String reason, String runbookRef, int currentRetryCount) {
        return new AiRemediationDecision(false, status, reason, runbookRef != null ? runbookRef : "NONE", null, currentRetryCount);
    }
}