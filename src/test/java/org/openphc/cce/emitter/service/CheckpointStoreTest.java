package org.openphc.cce.emitter.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.model.PollCheckpoint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CheckpointStore} — the file-backed poll watermark store. Uses a
 * {@link TempDir} so persistence and reload are exercised without touching the real data dir.
 */
class CheckpointStoreTest {

    @TempDir
    Path tempDir;

    private CheckpointStore store;
    private Path checkpointFile;

    private CheckpointStore newStore() {
        EmitterProperties props = new EmitterProperties();
        props.getCheckpoint().setFilePath(checkpointFile.toString());
        CheckpointStore s = new CheckpointStore(props);
        s.init();
        return s;
    }

    @BeforeEach
    void setUp() {
        checkpointFile = tempDir.resolve("checkpoints.json");
        store = newStore();
    }

    @Test
    void init_withNoFile_startsEmpty() {
        assertNull(store.getCheckpoint("Patient"));
        assertTrue(store.getAllCheckpoints().isEmpty());
    }

    @Test
    void saveCheckpoint_advancesLastUpdatedByOneSecondAndPersists() {
        store.saveCheckpoint("Patient", "2026-06-30T10:00:00Z");

        PollCheckpoint cp = store.getCheckpoint("Patient");
        assertNotNull(cp);
        // The watermark is advanced by one second to avoid re-detecting the boundary row.
        assertEquals("2026-06-30T10:00:01Z", cp.lastUpdated());
        assertTrue(Files.exists(checkpointFile));
    }

    @Test
    void saveCheckpoint_isReloadedByAFreshStore() {
        store.saveCheckpoint("Encounter", "2026-06-30T12:00:00Z");

        CheckpointStore reloaded = newStore();
        PollCheckpoint cp = reloaded.getCheckpoint("Encounter");
        assertNotNull(cp);
        assertEquals("2026-06-30T12:00:01Z", cp.lastUpdated());
    }

    @Test
    void deleteCheckpoint_removesAndPersists() {
        store.saveCheckpoint("Patient", "2026-06-30T10:00:00Z");
        store.deleteCheckpoint("Patient");

        assertNull(store.getCheckpoint("Patient"));
        assertNull(newStore().getCheckpoint("Patient"));
    }

    @Test
    void resolveQueryTime_withCheckpoint_usesLastUpdatedMinusOverlap() {
        store.saveCheckpoint("Patient", "2026-06-30T10:00:00Z"); // stored as ...10:00:01Z

        String queryTime = store.resolveQueryTime("Patient", 60, 10);

        // 10:00:01Z minus 10s overlap.
        assertEquals("2026-06-30T09:59:51Z", queryTime);
    }

    @Test
    void resolveQueryTime_withoutCheckpoint_usesSlidingWindowSeed() {
        Instant before = Instant.now().minusSeconds(70 + 5 + 1);

        String queryTime = store.resolveQueryTime("Observation", 70, 5);
        Instant seed = Instant.parse(queryTime);

        // Seed ~ now - (interval + overlap); allow generous slack for execution time.
        assertTrue(seed.isAfter(before), "seed should be recent");
        assertTrue(seed.isBefore(Instant.now()), "seed should be in the past");
    }

    @Test
    void resolveQueryTime_withInvalidCheckpointTimestamp_fallsBackToSlidingWindow() {
        store.saveCheckpoint("Patient", "not-a-timestamp"); // advanceOneSecond leaves it unchanged

        String queryTime = store.resolveQueryTime("Patient", 60, 10);

        // Falls back to a parseable sliding-window instant rather than throwing.
        assertDoesNotThrow(() -> Instant.parse(queryTime));
    }
}
