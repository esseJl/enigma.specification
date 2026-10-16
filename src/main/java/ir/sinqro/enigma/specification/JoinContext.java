package ir.sinqro.enigma.specification;

import ir.sinqro.enigma.exception.QueryRuleException;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.PluralAttribute;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;


public final class JoinContext {

    private final Map<String, From<?, ?>> joins = new HashMap<>();
    private boolean hasCollectionJoin = false;
    private final int maxJoins;


    public JoinContext(int maxJoins) {
        if (maxJoins <= 0) {
            throw new IllegalArgumentException("maxJoins must be > 0");
        }
        this.maxJoins = maxJoins;
    }

    public Path<?> resolve(From<?, ?> root, String entityPath) {
        Objects.requireNonNull(root, "root must not be null");
        if (entityPath == null || entityPath.isBlank()) {
            throw new IllegalArgumentException("entityPath must not be blank");
        }

        String[] parts = entityPath.split("\\.");
        From<?, ?> current = root;
        StringBuilder pathKey = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part == null || part.isBlank()) {
                throw new IllegalArgumentException("Empty path segment in: '" + entityPath + "'");
            }
            if (!pathKey.isEmpty()) {
                pathKey.append('.');
            }
            pathKey.append(part);

            boolean isLast = (i == parts.length - 1);

            if (!isLast) {
                String key = pathKey.toString();
                From<?, ?> existing = joins.get(key);
                if (existing != null) {
                    current = existing;
                } else {
                    if (joins.size() >= maxJoins) {
                        throw new QueryRuleException(
                                "Query exceeds maximum allowed number of joins per query: " + maxJoins);
                    }
                    Join<?, ?> join = current.join(part, JoinType.LEFT);
                    markIfCollection(join);
                    joins.put(key, join);
                    current = join;
                }
            } else {
                return current.get(part);
            }
        }
        return current;
    }

    /**
     * Joins <em>every</em> segment of {@code entityPath} (including the last) and returns the
     * resulting {@link From}. Used to reach a to-many association or an element collection.
     * <p>
     * Not thread-safe, like the rest of this class: one instance per query, per calling thread.
     * Joins are cached by path regardless of join type, so on overlapping prefixes use
     * {@link JoinType#LEFT} (what {@link #resolve} uses); only then can the two be mixed safely
     * on the same instance.
     */
    public From<?, ?> resolveFrom(From<?, ?> root, String entityPath, JoinType joinType) {
        Objects.requireNonNull(root, "root must not be null");
        Objects.requireNonNull(joinType, "joinType must not be null");
        if (entityPath == null || entityPath.isBlank()) {
            throw new IllegalArgumentException("entityPath must not be blank");
        }
        String[] parts = entityPath.split("\\.");
        From<?, ?> current = root;
        StringBuilder pathKey = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                throw new IllegalArgumentException("Empty path segment in: '" + entityPath + "'");
            }
            if (!pathKey.isEmpty()) {
                pathKey.append('.');
            }
            pathKey.append(part);
            String key = pathKey.toString();
            From<?, ?> existing = joins.get(key);
            if (existing != null) {
                current = existing;
                continue;
            }
            if (joins.size() >= maxJoins) {
                throw new QueryRuleException(
                        "Query exceeds maximum allowed number of joins per query: " + maxJoins);
            }
            Join<?, ?> join = current.join(part, joinType);
            markIfCollection(join);
            joins.put(key, join);
            current = join;
        }
        return current;
    }

    private void markIfCollection(Join<?, ?> join) {
        try {
            Attribute<?, ?> attr = join.getAttribute();
            if (attr instanceof PluralAttribute) {
                hasCollectionJoin = true;
            }
        } catch (Exception ignored) {
            // some JPA providers may not expose the attribute
        }
    }

    public boolean hasCollectionJoin() {
        return hasCollectionJoin;
    }

    public int joinCount() {
        return joins.size();
    }

    public void clear() {
        joins.clear();
        hasCollectionJoin = false;
    }
}