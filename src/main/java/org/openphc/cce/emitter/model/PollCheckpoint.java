package org.openphc.cce.emitter.model;

/**
 * Persisted poll checkpoint for a single FHIR resource type.
 *
 * @param resourceType FHIR resource type (e.g. "Patient")
 * @param lastUpdated  ISO 8601 timestamp — max(meta.lastUpdated) from the last poll
 * @param lastPollTime ISO 8601 timestamp — wall-clock time when the checkpoint was saved
 */
public record PollCheckpoint(String resourceType, String lastUpdated, String lastPollTime) {
}
