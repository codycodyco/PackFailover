package dev.cody.packfailover.artifact;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory {@code sha1 -> verified artifact} index. Snapshots are swapped
 * atomically so HTTP requests never observe a half-built index.
 */
public final class VerifiedArtifactRegistry {

    private final AtomicReference<Map<String, VerifiedArtifact>> bySha1 =
            new AtomicReference<>(Map.of());

    public void put(VerifiedArtifact artifact) {
        bySha1.updateAndGet(current -> {
            Map<String, VerifiedArtifact> next = new HashMap<>(current);
            next.put(artifact.sha1(), artifact);
            return Map.copyOf(next);
        });
    }

    public void replaceAll(Collection<VerifiedArtifact> artifacts) {
        Map<String, VerifiedArtifact> next = new HashMap<>();
        for (VerifiedArtifact artifact : artifacts) {
            next.put(artifact.sha1(), artifact);
        }
        bySha1.set(Map.copyOf(next));
    }

    public boolean contains(String sha1) {
        return sha1 != null && bySha1.get().containsKey(sha1);
    }

    public Optional<VerifiedArtifact> get(String sha1) {
        return sha1 == null ? Optional.empty() : Optional.ofNullable(bySha1.get().get(sha1));
    }

    public Collection<VerifiedArtifact> all() {
        return List.copyOf(bySha1.get().values().stream()
                .sorted(Comparator.comparing(VerifiedArtifact::verifiedAt))
                .toList());
    }

    public int size() {
        return bySha1.get().size();
    }
}
