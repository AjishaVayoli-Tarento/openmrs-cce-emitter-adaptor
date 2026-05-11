package org.openphc.cce.emitter.service;

/**
 * Outcome of a forwarding attempt to OpenHIM.
 */
public record ForwardResult(String status, int statusCode, String responseBody) {

    public static ForwardResult success(int statusCode) {
        return new ForwardResult("forwarded", statusCode, null);
    }

    public static ForwardResult failure(int statusCode, String responseBody) {
        return new ForwardResult("failed: HTTP " + statusCode, statusCode, responseBody);
    }

    public static ForwardResult unreachable(int attempts) {
        return new ForwardResult("unreachable after " + attempts + " attempt(s)", 0, null);
    }

    public static ForwardResult skipped(String reason) {
        return new ForwardResult("skipped: " + reason, 0, null);
    }

    public boolean isSuccess() {
        return "forwarded".equals(status);
    }
}
