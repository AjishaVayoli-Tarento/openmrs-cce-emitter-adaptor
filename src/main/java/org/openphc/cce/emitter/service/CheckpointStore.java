package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.model.PollCheckpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(CheckpointStore.class);

    private final EmitterProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, PollCheckpoint> checkpoints = new ConcurrentHashMap<>();
    private Path filePath;

    public CheckpointStore(EmitterProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public synchronized void init() {
        this.filePath = Path.of(properties.getCheckpoint().getFilePath());
        try {
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            if (Files.exists(filePath)) {
                load();
            } else {
                log.info("No checkpoint file at {} — will use sliding window seed on first poll", filePath);
            }
        } catch (IOException e) {
            log.error("Failed to initialize checkpoint store at {}: {}", filePath, e.getMessage());
        }
    }

    private void load() {
        try {
            JsonNode root = mapper.readTree(filePath.toFile());
            JsonNode cps = root.path("checkpoints");
            if (cps.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = cps.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> e = fields.next();
                    String resourceType = e.getKey();
                    String lastUpdated = e.getValue().path("lastUpdated").asText(null);
                    String lastPollTime = e.getValue().path("lastPollTime").asText(null);
                    checkpoints.put(resourceType, new PollCheckpoint(resourceType, lastUpdated, lastPollTime));
                }
            }
            log.info("Loaded {} checkpoint(s) from {}", checkpoints.size(), filePath);
        } catch (IOException e) {
            log.error("Failed to read checkpoint file {} — treating as empty: {}", filePath, e.getMessage());
            checkpoints.clear();
        }
    }

    public PollCheckpoint getCheckpoint(String resourceType) {
        return checkpoints.get(resourceType);
    }

    public synchronized void saveCheckpoint(String resourceType, String lastUpdated) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        // OpenMRS fhir2 truncates _lastUpdated to seconds and treats `gt` inclusively at the
        // second boundary. Persist (maxLastUpdated + 1 second) so the next poll's `gt` filter
        // does not re-detect the watermark row on every cycle. The configured overlap window
        // still protects against rows arriving within the same second.
        String advanced = advanceOneSecond(lastUpdated);
        PollCheckpoint cp = new PollCheckpoint(resourceType, advanced, now);
        checkpoints.put(resourceType, cp);
        persist();
        log.debug("Saved checkpoint {} -> {} (raw maxLastUpdated={})", resourceType, advanced, lastUpdated);
    }

    private static String advanceOneSecond(String iso) {
        try {
            return DateTimeFormatter.ISO_INSTANT.format(Instant.parse(iso).plusSeconds(1));
        } catch (Exception e) {
            return iso;
        }
    }

    public synchronized void deleteCheckpoint(String resourceType) {
        if (checkpoints.remove(resourceType) != null) {
            persist();
        }
    }

    public Map<String, PollCheckpoint> getAllCheckpoints() {
        return Collections.unmodifiableMap(checkpoints);
    }

    /**
     * Hybrid resolve — checkpoint-based when available; sliding window seed otherwise.
     */
    public String resolveQueryTime(String resourceType, int intervalSeconds, int overlapSeconds) {
        PollCheckpoint cp = checkpoints.get(resourceType);
        Instant base;
        if (cp != null && cp.lastUpdated() != null) {
            try {
                base = Instant.parse(cp.lastUpdated()).minusSeconds(overlapSeconds);
            } catch (Exception e) {
                log.warn("Invalid checkpoint timestamp '{}' for {} — falling back to sliding window seed",
                        cp.lastUpdated(), resourceType);
                base = Instant.now().minusSeconds((long) intervalSeconds + overlapSeconds);
            }
        } else {
            base = Instant.now().minusSeconds((long) intervalSeconds + overlapSeconds);
        }
        return DateTimeFormatter.ISO_INSTANT.format(base);
    }

    private void persist() {
        try {
            ObjectNode root = mapper.createObjectNode();
            ObjectNode cps = root.putObject("checkpoints");
            for (PollCheckpoint cp : checkpoints.values()) {
                ObjectNode entry = cps.putObject(cp.resourceType());
                entry.put("lastUpdated", cp.lastUpdated());
                entry.put("lastPollTime", cp.lastPollTime());
            }
            Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
            Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Failed to persist checkpoints to {}: {}", filePath, e.getMessage());
        }
    }
}
