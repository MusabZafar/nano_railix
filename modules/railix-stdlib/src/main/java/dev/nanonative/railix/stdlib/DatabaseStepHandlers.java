package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * Pure-JDK local document collection persistence Step implementation ({@code railix.database}).
 *
 * <h2>Durability and Recovery Guarantees</h2>
 * <ul>
 *   <li>Every committed {@code upsert} (single document or batch array) and {@code delete} appends
 *       CRC32-checksummed binary frames to {@code journal.wal} and flushes file content via
 *       {@link FileChannel#force(boolean) journal.force(false)} before returning {@code ok}.</li>
 *   <li>On startup, {@link #replayJournal()} distinguishes an interrupted trailing write at EOF
 *       (a partial header matching the valid header prefix or a valid header whose declared payload
 *       extends past EOF) from data corruption (invalid header magic/bounds, CRC32 mismatch on a
 *       complete frame, invalid UTF-8 key, bucket mismatch, or corrupted JSON payload). Incomplete
 *       trailing writes at EOF are truncated cleanly; corruption throws {@link IllegalStateException}
 *       with {@code DATABASE_JOURNAL_CORRUPTED} while preserving the damaged {@code journal.wal}
 *       file intact on disk without truncating subsequent records.</li>
 *   <li>When superseded and tombstoned bytes reach {@link #COMPACT_MIN_DEAD_BYTES} and exceed live
 *       payload bytes, compaction rewrites live records sequentially to {@code journal.wal.compact},
 *       forces it to disk, and atomically replaces {@code journal.wal}.</li>
 * </ul>
 *
 * <h2>Concurrency and Scope</h2>
 * <ul>
 *   <li>Cross-process ownership is enforced via an exclusive OS {@link FileLock} on
 *       {@code collection.lock}, which also records the canonical collection name to detect
 *       filesystem path or case-insensitivity collisions.</li>
 *   <li>Within one running application, placements referencing the same collection directory share
 *       one store instance via {@code context.resource} and coordinate concurrent virtual-thread
 *       reads and serialized writes through a {@link ReentrantReadWriteLock}.</li>
 *   <li>Multi-node replication and cross-document/multi-collection transactions are out of scope.</li>
 * </ul>
 *
 * <h2>Storage Location and Safety Bounds</h2>
 * <ul>
 *   <li>Absolute {@code directory} paths are used directly; relative paths resolve against the
 *       {@code railix.data.dir} system property, the {@code RAILIX_DATA_DIR} environment variable,
 *       the Creator project root (when launched from {@code .railix/build/<entry>/application.jar}),
 *       or the process working directory.</li>
 *   <li>{@link #MAX_PAYLOAD_BYTES} (1 MiB) matches {@link RailixData#DEFAULT_MAX_SOURCE_BYTES} to
 *       bound per-document memory during concurrent reads and compaction; {@link #MAX_FIELD_NAMES}
 *       (32) bounds per-placement secondary index maintenance and projection fan-out; {@code find}
 *       returns pages of up to {@link #MAX_FIND_LIMIT} (1,000) documents with keyset continuation
 *       via the optional {@code after} input.</li>
 * </ul>
 */
public final class DatabaseStepHandlers {
    static final int BUCKET_COUNT = 1_024;
    static final int BUCKET_MASK = BUCKET_COUNT - 1;
    static final int MAGIC = 0x5244_4231;
    static final int HEADER_BYTES = 20;

    private static final byte OP_UPSERT = 1;
    private static final byte OP_DELETE = 2;
    private static final byte KEY_STRING = 1;
    private static final byte KEY_NUMBER = 2;

    private static final int MAX_KEY_BYTES = 1_024;
    /** Matches {@link RailixData#DEFAULT_MAX_SOURCE_BYTES} (1 MiB) to bound single-document heap allocations. */
    static final int MAX_PAYLOAD_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES;
    /** Matches {@link RailixData#MAX_SOURCE_BYTES} (16 MiB) to bound total buffered bytes in one batch write. */
    static final int MAX_BULK_FRAME_BYTES = RailixData.MAX_SOURCE_BYTES;
    private static final long COMPACT_MIN_DEAD_BYTES = 65_536L;

    /** Caps per-placement field lists so secondary-index maintenance and projection fan-out remain bounded. */
    static final int MAX_FIELD_NAMES = 32;
    static final int DEFAULT_FIND_LIMIT = 100;
    static final int MAX_FIND_LIMIT = 1_000;
    static final int MAX_FILTER_DEPTH = 16;
    static final int MAX_FILTER_CLAUSES = 128;
    static final int MAX_OPERATOR_ARRAY_SIZE = 1_000;
    static final int MAX_FILTER_PATH_SEGMENTS = 16;
    static final int MAX_FIELD_PATH_BYTES = 512;

    private static final Set<String> RESERVED_DEVICE_NAMES = Set.of(
            "con", "prn", "aux", "nul",
            "com0", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt0", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    );

    private static final ValueRefinement DOCUMENT_REFINEMENT = ValueRefinement.canonical()
            .withMaxJsonBytes(MAX_PAYLOAD_BYTES);
    private static final RailixValue.ObjectValue NO_DEFAULTS = RailixValue.object(Map.of());
    private static final FilterNode MATCH_ALL_FILTER = new AndNode(List.of());

    private enum FilterOp {
        EQ, NE, GT, GTE, LT, LTE, IN, NIN, EXISTS
    }

    private enum WriteOutcome {
        OK, NOT_FOUND, INVALID
    }

    private record FieldPredicate(
            FilterOp op,
            RailixValue scalar,
            Set<RailixValue> set,
            boolean boolValue
    ) {}

    private sealed interface FilterNode permits AndNode, OrNode, FieldNode {}

    private record AndNode(List<FilterNode> children) implements FilterNode {}

    private record OrNode(List<FilterNode> children) implements FilterNode {}

    private record FieldNode(
            String fieldPath,
            List<String> segments,
            List<FieldPredicate> predicates
    ) implements FilterNode {}

    private record RangeBounds(
            char targetType,
            String loKey,
            boolean loInclusive,
            String hiKey,
            boolean hiInclusive,
            boolean empty
    ) {
        static final RangeBounds EMPTY = new RangeBounds('\0', null, false, null, false, true);
    }

    private record ParsedCondition(boolean valid, FilterNode filter) {
        static final ParsedCondition NONE = new ParsedCondition(true, null);
        static final ParsedCondition INVALID = new ParsedCondition(false, null);
    }

    private record RecordLocation(long payloadOffset, int keyLength, int payloadLength) {
        long frameOffset() {
            return payloadOffset - HEADER_BYTES - keyLength;
        }
    }

    private record PreparedWrite(
            String indexKey,
            byte keyType,
            short bucket,
            byte[] keyBytes,
            byte[] payloadBytes,
            RailixValue.ObjectValue document
    ) {
        long frameBytes() {
            return (long) HEADER_BYTES + keyBytes.length + payloadBytes.length;
        }
    }

    private final String collectionName;
    private final Path directory;
    private final Path lockPath;
    private final Path journalPath;
    private final Path compactPath;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Map<String, RecordLocation> index = new HashMap<>();
    private final NavigableSet<String> sortedKeys = new TreeSet<>(DatabaseStepHandlers::compareTaggedValues);
    /** Secondary index field -> primary index key -> encoded scalar value; mutated only under the write lock. */
    private final Map<String, Map<String, String>> indexValueByKey = new HashMap<>();
    /** Secondary index field -> ordered encoded scalar value -> primary keys; mutated only under write lock. */
    private final Map<String, NavigableMap<String, Set<String>>> indexKeysByValue = new HashMap<>();
    /** Immutable snapshots replaced only during PRE, so request preparation can read them without locking. */
    private volatile Set<String> uniqueFields = Set.of();
    private volatile List<String> indexedFields = List.of();
    private volatile Map<String, List<String>> fieldPathCache = Map.of();

    private FileChannel lockChannel;
    private FileLock fileLock;
    private FileChannel journal;
    private long writeOffset;
    private long livePayloadBytes;
    private long deadBytes;
    private volatile long lastCompactionPauseNanos;
    private String configuredKeyField;
    private boolean initialized;
    private boolean closed;

    private DatabaseStepHandlers(final String collectionName, final Path collectionDirectory) {
        this.collectionName = collectionName;
        this.directory = collectionDirectory;
        this.lockPath = collectionDirectory.resolve("collection.lock");
        this.journalPath = collectionDirectory.resolve("journal.wal");
        this.compactPath = collectionDirectory.resolve("journal.wal.compact");
    }

    /**
     * Opens or reuses the configured local collection store during application startup and ensures the
     * placement's declared secondary indexes. Indexes are collection-wide: the store maintains the union
     * of every placement's {@code unique_fields} and {@code index_fields}.
     *
     * @param configuration compiled collection, directory, key_field, index, and operation configuration
     * @param context lifecycle context supplying placement-owned resource management
     * @return primary outcome once the collection journal is replayed, locked, and indexed
     * @throws IllegalStateException when existing records violate a declared unique index or journal is corrupted
     */
    public static StepResult pre(final StepInput configuration, final StepInput context) {
        final String collection = configuration.string("collection");
        final Path collectionDirectory = resolveCollectionDirectory(
                configuration.string("directory"),
                collection
        );
        final DatabaseStepHandlers store = context.resource(
                "store",
                DatabaseStepHandlers.class,
                collectionDirectory,
                () -> openStore(collection, collectionDirectory),
                DatabaseStepHandlers::closeStore
        );
        store.ensureIndexes(
                collection,
                configuration.string("key_field"),
                fieldNames(configuration.optionalValue("unique_fields")),
                fieldNames(configuration.optionalValue("index_fields"))
        );
        return StepResult.outcome(context.primaryOutcome());
    }

    /**
     * Executes the configured collection operation ({@code upsert}, {@code get}, {@code find}, or {@code delete}).
     * {@code upsert} accepts either a single document ({@code OBJECT}) or a batch list of documents ({@code ARRAY}).
     *
     * @param configuration compiled Step settings
     * @param input resolved runtime invocation values
     * @return {@code ok} (with {@code target} write), {@code not_found}, or {@code invalid}
     */
    public static StepResult process(final StepInput configuration, final StepInput input) {
        final DatabaseStepHandlers store = input.resource("store", DatabaseStepHandlers.class);
        final String keyField = configuration.string("key_field");
        final String operation = input.option("operation");
        final StepInput selected = input.selected("operation");
        return switch (operation) {
            case "upsert" -> store.upsert(
                    keyField,
                    booleanOption(selected, "auto_key"),
                    fieldNames(selected.optionalValue("fields")),
                    selected.optionalValue("document")
                            .or(() -> selected.optionalValue("documents"))
                            .orElse(null),
                    condition(configuration, selected)
            );
            case "bulk_upsert" -> store.upsertBatch(
                    keyField,
                    booleanOption(selected, "auto_key"),
                    fieldNames(selected.optionalValue("fields")),
                    selected.optionalValue("documents")
                            .or(() -> selected.optionalValue("document"))
                            .orElse(null),
                    condition(configuration, selected)
            );
            case "get" -> store.get(
                    keyField,
                    fieldNames(selected.optionalValue("fields")),
                    defaults(selected),
                    selected.optionalValue("key").orElse(null)
            );
            case "find" -> store.find(
                    keyField,
                    fieldNames(selected.optionalValue("fields")),
                    defaults(selected),
                    limit(selected),
                    selected.optionalValue("filter").orElse(null),
                    selected.optionalValue("after").orElse(null)
            );
            case "delete" -> store.delete(
                    keyField,
                    selected.optionalValue("key").orElse(null),
                    condition(configuration, selected)
            );
            default -> throw new IllegalArgumentException("Unsupported database operation: " + operation);
        };
    }

    private static ParsedCondition condition(final StepInput configuration, final StepInput selected) {
        final boolean authored = configuration.options().containsKey("operation")
                && configuration.selected("operation").optionalValue("condition").isPresent();
        final RailixValue runtimeValue = selected.optionalValue("condition").orElse(null);
        if (!authored && runtimeValue == null) {
            return ParsedCondition.NONE;
        }
        final FilterNode filter = parseFilter(runtimeValue, false);
        return filter == null ? ParsedCondition.INVALID : new ParsedCondition(true, filter);
    }

    /** Computes the deterministic 1,024-bucket partition ID for a valid string or number key. */
    static int bucketForKey(final RailixValue key) {
        final String indexKey = encodeIndexKey(key);
        if (indexKey == null) {
            throw new IllegalArgumentException("Database key must be a valid non-empty string or canonical number.");
        }
        return bucketOf(indexKey);
    }

    long journalSizeBytes() {
        rwLock.readLock().lock();
        try {
            return writeOffset;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    long lastCompactionPauseNanos() {
        return lastCompactionPauseNanos;
    }

    private static boolean booleanOption(final StepInput selected, final String name) {
        return ((RailixValue.BooleanValue) selected.value(name)).value();
    }

    /** Reads a validated field-name list; an absent input means "no fields" (whole document / no index). */
    private static List<String> fieldNames(final Optional<RailixValue> value) {
        if (value.isEmpty() || !(value.get() instanceof RailixValue.ArrayValue array) || array.values().isEmpty()) {
            return List.of();
        }
        final List<String> names = new ArrayList<>(array.values().size());
        for (final RailixValue item : array.values()) {
            if (item instanceof RailixValue.StringValue string) {
                names.add(string.value());
            }
        }
        return List.copyOf(names);
    }

    private static RailixValue.ObjectValue defaults(final StepInput selected) {
        return selected.optionalValue("defaults").orElse(NO_DEFAULTS) instanceof RailixValue.ObjectValue object
                ? object
                : NO_DEFAULTS;
    }

    private static int limit(final StepInput selected) {
        if (selected.optionalValue("limit").orElse(null) instanceof RailixValue.NumberValue number) {
            final BigDecimal value = number.value();
            if (value.compareTo(BigDecimal.ONE) >= 0 && value.compareTo(BigDecimal.valueOf(MAX_FIND_LIMIT)) <= 0) {
                return value.intValue();
            }
        }
        return DEFAULT_FIND_LIMIT;
    }

    /**
     * Validates a configured top-level field-name list: at most {@value #MAX_FIELD_NAMES} distinct,
     * non-blank names up to 128 UTF-8 bytes that do not start with {@code $}.
     *
     * @param value authored array
     * @return empty when valid, otherwise a user-facing rejection
     */
    static Optional<String> fieldListRejection(final RailixValue.ArrayValue value) {
        return fieldListRejection(value, false);
    }

    /**
     * Validates configured secondary/unique index field paths: at most {@value #MAX_FIELD_NAMES} distinct,
     * valid field names or dot-separated nested paths.
     *
     * @param value authored array
     * @return empty when valid, otherwise a user-facing rejection
     */
    static Optional<String> indexFieldListRejection(final RailixValue.ArrayValue value) {
        return fieldListRejection(value, true);
    }

    private static Optional<String> fieldListRejection(
            final RailixValue.ArrayValue value,
            final boolean allowDotPaths
    ) {
        if (value.values().size() > MAX_FIELD_NAMES) {
            return Optional.of("Use at most " + MAX_FIELD_NAMES + " field names.");
        }
        final Set<String> seen = new HashSet<>();
        for (final RailixValue item : value.values()) {
            if (!(item instanceof RailixValue.StringValue string)
                    || !(allowDotPaths ? validFieldPath(string.value()) : validFieldName(string.value()))) {
                return Optional.of(allowDotPaths
                        ? "Index field paths must have valid non-blank segments without leading $, dots at "
                                + "boundaries, or '..'."
                        : "Field names must be non-blank text up to 128 bytes and must not start with $.");
            }
            if (!seen.add(string.value())) {
                return Optional.of("Field name is listed more than once: " + string.value() + ".");
            }
        }
        return Optional.empty();
    }

    /**
     * Validates configured read defaults: at most {@value #MAX_FIELD_NAMES} top-level fields with valid names.
     *
     * @param value authored defaults object
     * @return empty when valid, otherwise a user-facing rejection
     */
    static Optional<String> defaultsRejection(final RailixValue.ObjectValue value) {
        if (value.values().size() > MAX_FIELD_NAMES) {
            return Optional.of("Use at most " + MAX_FIELD_NAMES + " default fields.");
        }
        for (final String name : value.values().keySet()) {
            if (!validFieldName(name)) {
                return Optional.of(
                        "Default field names must be non-blank text up to 128 bytes and must not start with $."
                );
            }
        }
        return DOCUMENT_REFINEMENT.rejection(value).isPresent()
                ? Optional.of("Defaults exceed the supported JSON value bounds.")
                : Optional.empty();
    }

    private static boolean validFieldName(final String value) {
        return validKeyField(value) && value.charAt(0) != '$';
    }

    private static boolean validFieldPath(final String value) {
        if (value == null || value.isBlank() || invalidSurrogate(value)) {
            return false;
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_FIELD_PATH_BYTES) {
            return false;
        }
        int segmentCount = 0;
        int start = 0;
        while (start <= value.length()) {
            final int dot = value.indexOf('.', start);
            final String segment = dot < 0 ? value.substring(start) : value.substring(start, dot);
            if (!validFieldName(segment)) {
                return false;
            }
            segmentCount++;
            if (segmentCount > MAX_FILTER_PATH_SEGMENTS) {
                return false;
            }
            if (dot < 0) {
                break;
            }
            start = dot + 1;
        }
        return true;
    }

    private static List<String> splitFieldPath(final String path) {
        if (path.indexOf('.') < 0) {
            return List.of(path);
        }
        final List<String> segments = new ArrayList<>(4);
        int start = 0;
        while (start <= path.length()) {
            final int dot = path.indexOf('.', start);
            if (dot < 0) {
                segments.add(path.substring(start));
                break;
            }
            segments.add(path.substring(start, dot));
            start = dot + 1;
        }
        return List.copyOf(segments);
    }

    /** Returns Java null when any path step is absent; returns {@link RailixValue.NullValue} for JSON null. */
    private static RailixValue readPathValue(
            final RailixValue.ObjectValue document,
            final List<String> segments
    ) {
        if (segments.size() == 1) {
            return document.values().get(segments.getFirst());
        }
        RailixValue current = document;
        for (int i = 0; i < segments.size(); i++) {
            final String segment = segments.get(i);
            if (current instanceof RailixValue.ObjectValue object) {
                current = object.values().get(segment);
                if (current == null) {
                    return null;
                }
            } else if (current instanceof RailixValue.ArrayValue array) {
                final int index = parseArrayIndex(segment);
                if (index < 0 || index >= array.values().size()) {
                    return null;
                }
                current = array.values().get(index);
            } else {
                return null;
            }
        }
        return current;
    }

    private static int parseArrayIndex(final String segment) {
        final int len = segment.length();
        if (len == 0 || len > 7) {
            return -1;
        }
        if (len == 1) {
            final char c = segment.charAt(0);
            return (c >= '0' && c <= '9') ? c - '0' : -1;
        }
        if (segment.charAt(0) < '1' || segment.charAt(0) > '9') {
            return -1;
        }
        int parsed = 0;
        for (int i = 0; i < len; i++) {
            final char c = segment.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            parsed = parsed * 10 + (c - '0');
        }
        return parsed;
    }

    private List<String> cachedFieldPath(final String field) {
        final List<String> cached = fieldPathCache.get(field);
        return cached != null ? cached : splitFieldPath(field);
    }

    /**
     * Builds collection-wide secondary indexes requested by one placement during PRE.
     * Existing indexes are kept; newly requested fields are built from stored documents, and newly
     * unique fields are verified before any writes are admitted.
     */
    private void ensureIndexes(
            final String requestedCollection,
            final String keyField,
            final List<String> unique,
            final List<String> indexed
    ) {
        rwLock.writeLock().lock();
        final List<String> added = new ArrayList<>();
        try {
            ensureOpen();
            if (!collectionName.equals(requestedCollection)) {
                throw new IllegalStateException("DATABASE_COLLECTION_COLLISION: Directory " + directory
                        + " belongs to collection '" + collectionName + "', not '" + requestedCollection + "'.");
            }
            if (configuredKeyField == null) {
                configuredKeyField = keyField;
            } else if (!configuredKeyField.equals(keyField)) {
                throw new IllegalStateException("DATABASE_KEY_FIELD_MISMATCH: Collection '" + collectionName
                        + "' in " + directory + " uses key_field '" + configuredKeyField
                        + "', not '" + keyField + "'.");
            }
            final Set<String> nextUnique = new LinkedHashSet<>(uniqueFields);
            final Set<String> nextIndexed = new LinkedHashSet<>(indexedFields);
            for (final String field : unique) {
                if (!field.equals(keyField)) {
                    nextUnique.add(field);
                    nextIndexed.add(field);
                }
            }
            for (final String field : indexed) {
                if (!field.equals(keyField)) {
                    nextIndexed.add(field);
                }
            }
            for (final String field : nextIndexed) {
                if (!indexValueByKey.containsKey(field)) {
                    added.add(field);
                }
            }
            final boolean newlyUnique = !uniqueFields.containsAll(nextUnique);
            if (added.isEmpty() && !newlyUnique) {
                return;
            }
            final Map<String, List<String>> nextPathCache = new HashMap<>(fieldPathCache);
            for (final String field : nextIndexed) {
                nextPathCache.computeIfAbsent(field, DatabaseStepHandlers::splitFieldPath);
            }
            for (final String field : added) {
                indexValueByKey.put(field, new HashMap<>());
                indexKeysByValue.put(field, new TreeMap<>(DatabaseStepHandlers::compareTaggedValues));
            }
            for (final Map.Entry<String, RecordLocation> entry : index.entrySet()) {
                final RailixValue.ObjectValue document = parseStoredObject(readPayloadBytes(journal, entry.getValue()));
                for (final String field : nextUnique) {
                    if (!uniqueCompatible(readPathValue(document, nextPathCache.get(field)))) {
                        throw new IllegalStateException("DATABASE_UNIQUE_INDEX_VIOLATION: Unique field " + field
                                + " holds an array or object value in collection " + directory + ".");
                    }
                }
                for (final String field : added) {
                    addIndexEntry(field, entry.getKey(), readPathValue(document, nextPathCache.get(field)));
                }
            }
            for (final String field : nextUnique) {
                for (final Set<String> holders : indexKeysByValue.get(field).values()) {
                    if (holders.size() > 1) {
                        throw new IllegalStateException("DATABASE_UNIQUE_INDEX_VIOLATION: Unique field " + field
                                + " has duplicate stored values in collection " + directory + ".");
                    }
                }
            }
            fieldPathCache = Map.copyOf(nextPathCache);
            uniqueFields = Set.copyOf(nextUnique);
            indexedFields = List.copyOf(nextIndexed);
            added.clear();
        } catch (final IOException exception) {
            throw new UncheckedIOException("Cannot build database indexes in collection " + directory + ".", exception);
        } finally {
            for (final String field : added) {
                indexValueByKey.remove(field);
                indexKeysByValue.remove(field);
            }
            rwLock.writeLock().unlock();
        }
    }

    private void addIndexEntry(final String field, final String indexKey, final RailixValue value) {
        final String encoded = indexValue(value);
        if (encoded != null) {
            indexValueByKey.get(field).put(indexKey, encoded);
            indexKeysByValue.get(field).computeIfAbsent(encoded, ignored -> new LinkedHashSet<>()).add(indexKey);
        }
    }

    /** Replaces (or, for a Java-null document, removes) every secondary index entry of one record. */
    private void reindex(final String indexKey, final RailixValue.ObjectValue document) {
        for (final String field : indexedFields) {
            final String previous = indexValueByKey.get(field).remove(indexKey);
            if (previous != null) {
                final Map<String, Set<String>> byValue = indexKeysByValue.get(field);
                final Set<String> holders = byValue.get(previous);
                holders.remove(indexKey);
                if (holders.isEmpty()) {
                    byValue.remove(previous);
                }
            }
            if (document != null) {
                addIndexEntry(field, indexKey, readPathValue(document, cachedFieldPath(field)));
            }
        }
    }

    /**
     * Returns whether committing the final documents of one write would duplicate a unique value,
     * considering records rewritten by the same write as released. Call under the write lock.
     */
    private boolean uniqueConflict(final Map<String, RailixValue.ObjectValue> finalDocuments) {
        for (final String field : uniqueFields) {
            final List<String> segments = cachedFieldPath(field);
            final Map<String, String> claimed = new HashMap<>();
            for (final Map.Entry<String, RailixValue.ObjectValue> entry : finalDocuments.entrySet()) {
                final String encoded = indexValue(readPathValue(entry.getValue(), segments));
                if (encoded != null && claimed.putIfAbsent(encoded, entry.getKey()) != null) {
                    return true;
                }
            }
            final Map<String, Set<String>> byValue = indexKeysByValue.get(field);
            for (final Map.Entry<String, String> claim : claimed.entrySet()) {
                for (final String holder : byValue.getOrDefault(claim.getKey(), Set.of())) {
                    if (!holder.equals(claim.getValue()) && !finalDocuments.containsKey(holder)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean uniqueCompatible(final RailixValue value) {
        return !(value instanceof RailixValue.ArrayValue) && !(value instanceof RailixValue.ObjectValue);
    }

    private boolean uniqueFieldsCompatible(final Set<String> unique, final RailixValue.ObjectValue document) {
        for (final String field : unique) {
            if (!uniqueCompatible(readPathValue(document, cachedFieldPath(field)))) {
                return false;
            }
        }
        return true;
    }

    /** Encodes an indexable scalar; null, missing, arrays and objects are not indexed (sparse). */
    private static String indexValue(final RailixValue value) {
        return switch (value) {
            case null -> null;
            case RailixValue.StringValue string -> "s:" + string.value();
            case RailixValue.NumberValue number -> {
                final BigDecimal raw = number.value();
                yield "n:" + (raw.signum() == 0 ? BigDecimal.ZERO : raw.stripTrailingZeros()).toString();
            }
            case RailixValue.BooleanValue bool -> bool.value() ? "b:true" : "b:false";
            default -> null;
        };
    }

    /** Keeps only the listed top-level fields plus the key field; an empty list keeps the whole document. */
    private static RailixValue.ObjectValue project(
            final RailixValue.ObjectValue document,
            final String keyField,
            final List<String> fields
    ) {
        if (fields.isEmpty()) {
            return document;
        }
        final Map<String, RailixValue> projected = new TreeMap<>();
        final RailixValue key = document.values().get(keyField);
        if (key != null) {
            projected.put(keyField, key);
        }
        for (final String field : fields) {
            final RailixValue value = document.values().get(field);
            if (value != null) {
                projected.put(field, value);
            }
        }
        return RailixValue.object(projected);
    }

    /** Applies a read projection, then fills each configured default where the field is missing or JSON null. */
    private static RailixValue.ObjectValue readView(
            final RailixValue.ObjectValue document,
            final String keyField,
            final List<String> fields,
            final RailixValue.ObjectValue defaults
    ) {
        final RailixValue.ObjectValue projected = project(document, keyField, fields);
        if (defaults.values().isEmpty()) {
            return projected;
        }
        final Map<String, RailixValue> filled = new TreeMap<>(projected.values());
        for (final Map.Entry<String, RailixValue> entry : defaults.values().entrySet()) {
            final RailixValue current = filled.get(entry.getKey());
            if (current == null || current instanceof RailixValue.NullValue) {
                filled.put(entry.getKey(), entry.getValue());
            }
        }
        return RailixValue.object(filled);
    }

    static Path resolveCollectionDirectory(final String configuredDirectory, final String collection) {
        if (!validCollectionName(collection)) {
            throw new IllegalArgumentException("Invalid collection name: " + collection);
        }
        if (!validStorageDirectory(configuredDirectory)) {
            throw new IllegalArgumentException("Invalid storage directory: " + configuredDirectory);
        }
        final Path configured = Path.of(configuredDirectory);
        final Path base = configured.isAbsolute()
                ? configured.toAbsolutePath().normalize()
                : defaultBaseDirectory().resolve(configured).toAbsolutePath().normalize();
        return base.resolve(collection).normalize();
    }

    /**
     * Enforces portable, OS-independent collection naming: 1 to 64 characters starting and ending
     * with a lowercase ASCII letter or digit, containing only {@code a-z}, {@code 0-9}, {@code .},
     * {@code _}, or {@code -} without consecutive dots or Windows-reserved device names.
     */
    static boolean validCollectionName(final String value) {
        if (value == null || value.isEmpty() || value.length() > 64) {
            return false;
        }
        if (!lowercaseAlphanumeric(value.charAt(0)) || !lowercaseAlphanumeric(value.charAt(value.length() - 1))) {
            return false;
        }
        char previous = 0;
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (!lowercaseAlphanumeric(c) && c != '.' && c != '_' && c != '-') {
                return false;
            }
            if (c == '.' && previous == '.') {
                return false;
            }
            previous = c;
        }
        final int dot = value.indexOf('.');
        final String stem = (dot >= 0 ? value.substring(0, dot) : value).toLowerCase(Locale.ROOT);
        return !RESERVED_DEVICE_NAMES.contains(stem);
    }

    static boolean validStorageDirectory(final String value) {
        if (value == null || value.isBlank() || value.length() > 512) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c < 32 || c == '<' || c == '>' || c == '"' || c == '|' || c == '?' || c == '*') {
                return false;
            }
            if (c == ':' && !(i == 1 && asciiLetter(value.charAt(0)))) {
                return false;
            }
        }
        final Path path;
        try {
            path = Path.of(value);
        } catch (final InvalidPathException invalid) {
            return false;
        }
        for (final Path segment : path) {
            final String part = segment.toString();
            if ("..".equals(part) || ".".equals(part) || part.endsWith(" ") || part.endsWith(".")) {
                return false;
            }
        }
        return true;
    }

    static boolean validKeyField(final String value) {
        if (value == null || value.isBlank() || invalidSurrogate(value)) {
            return false;
        }
        return value.getBytes(StandardCharsets.UTF_8).length <= 128;
    }

    private static boolean asciiLetter(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean lowercaseAlphanumeric(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static Path defaultBaseDirectory() {
        final String prop = System.getProperty("railix.data.dir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop).toAbsolutePath().normalize();
        }
        final String env = System.getenv("RAILIX_DATA_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        final var source = DatabaseStepHandlers.class.getProtectionDomain().getCodeSource();
        if (source != null && source.getLocation() != null) {
            try {
                final Path location = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
                final Path buildEntry = location.getParent();
                final Path buildDir = buildEntry == null ? null : buildEntry.getParent();
                final Path railixDir = buildDir == null ? null : buildDir.getParent();
                final Path projectRoot = railixDir == null ? null : railixDir.getParent();
                if (projectRoot != null
                        && Path.of("application.jar").equals(location.getFileName())
                        && Path.of("build").equals(buildDir.getFileName())
                        && Path.of(".railix").equals(railixDir.getFileName())) {
                    return projectRoot;
                }
            } catch (final URISyntaxException | IllegalArgumentException ignored) {
                // Fall back to the process working directory when code source is not a file path.
            }
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    private static DatabaseStepHandlers openStore(final String collectionName, final Path collectionDirectory) {
        final DatabaseStepHandlers store = new DatabaseStepHandlers(collectionName, collectionDirectory);
        try {
            store.initialize();
            return store;
        } catch (final IOException exception) {
            store.closeQuietly();
            throw new UncheckedIOException(
                    "Cannot initialize database collection at " + collectionDirectory + ".",
                    exception
            );
        } catch (final RuntimeException | Error failure) {
            store.closeQuietly();
            throw failure;
        }
    }

    private void initialize() throws IOException {
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        );
        try {
            fileLock = lockChannel.tryLock();
        } catch (final OverlappingFileLockException lockedInJvm) {
            fileLock = null;
        }
        if (fileLock == null) {
            throw new IllegalStateException(
                    "DATABASE_COLLECTION_LOCKED: Collection is already locked by another writer: " + directory
            );
        }
        verifyOrRecordCollectionOwner();
        if (!Files.exists(journalPath) && Files.exists(compactPath)) {
            moveJournal(compactPath, journalPath);
        } else {
            Files.deleteIfExists(compactPath);
        }
        journal = FileChannel.open(
                journalPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        );
        replayJournal();
        if (shouldCompact()) {
            compactUnderWriteLock();
        }
        initialized = true;
    }

    private void verifyOrRecordCollectionOwner() throws IOException {
        final long lockSize = lockChannel.size();
        if (lockSize > 0L) {
            final int length = (int) Math.min(lockSize, 256L);
            final byte[] bytes = new byte[length];
            readFully(lockChannel, ByteBuffer.wrap(bytes), 0L);
            final String recorded = decodeUtf8(bytes, 0, length);
            final String existing = recorded == null ? "" : recorded.strip();
            if (!existing.isEmpty() && !existing.equals(collectionName)) {
                throw new IllegalStateException("DATABASE_COLLECTION_COLLISION: Directory " + directory
                        + " belongs to collection '" + existing + "', not '" + collectionName + "'.");
            }
            if (!existing.isEmpty()) {
                return;
            }
        }
        final byte[] ownerBytes = (collectionName + "\n").getBytes(StandardCharsets.UTF_8);
        writeFully(lockChannel, ByteBuffer.wrap(ownerBytes), 0L);
        lockChannel.force(false);
    }

    /**
     * Replays committed frames from {@code journal.wal}.
     *
     * <p>Only an incomplete trailing write at EOF (a partial frame header matching the valid header
     * prefix or a valid header whose declared payload extends beyond EOF) is truncated. Any invalid
     * magic, invalid header field, CRC32 mismatch on a complete frame, invalid UTF-8 key, bucket
     * mismatch, or corrupted JSON payload throws {@link IllegalStateException} without truncating
     * {@code journal.wal} so damaged files are preserved intact.</p>
     */
    private void replayJournal() throws IOException {
        final long fileSize = journal.size();
        final ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        long offset = 0L;
        boolean incompleteTrailingWrite = false;

        while (offset < fileSize) {
            final long remaining = fileSize - offset;
            if (remaining < HEADER_BYTES) {
                final byte[] partial = new byte[(int) remaining];
                if (!readFully(journal, ByteBuffer.wrap(partial), offset)) {
                    incompleteTrailingWrite = true;
                    break;
                }
                if (!validPartialHeader(partial)) {
                    throw corruptedJournal(offset, "Invalid partial frame header");
                }
                incompleteTrailingWrite = true;
                break;
            }
            header.clear();
            if (!readFully(journal, header, offset)) {
                incompleteTrailingWrite = true;
                break;
            }
            header.flip();
            final int magic = header.getInt();
            final byte op = header.get();
            final byte keyType = header.get();
            final int bucket = Short.toUnsignedInt(header.getShort());
            final int keyLength = header.getInt();
            final int payloadLength = header.getInt();
            final int expectedCrc = header.getInt();
            if (!validFrameHeader(magic, op, keyType, bucket, keyLength, payloadLength)) {
                throw corruptedJournal(offset, "Invalid frame header");
            }
            final long frameBytes = (long) HEADER_BYTES + keyLength + payloadLength;
            if (offset + frameBytes > fileSize) {
                incompleteTrailingWrite = true;
                break;
            }
            final byte[] body = new byte[keyLength + payloadLength];
            if (!readFully(journal, ByteBuffer.wrap(body), offset + HEADER_BYTES)) {
                incompleteTrailingWrite = true;
                break;
            }
            if (frameChecksum(op, keyType, (short) bucket, keyLength, payloadLength, body) != expectedCrc) {
                throw corruptedJournal(offset, "Frame CRC32 checksum mismatch");
            }
            final String rawKey = decodeUtf8(body, 0, keyLength);
            if (rawKey == null) {
                throw corruptedJournal(offset, "Invalid UTF-8 key");
            }
            final String indexKey = (keyType == KEY_STRING ? "s:" : "n:") + rawKey;
            if (bucketOf(indexKey) != bucket) {
                throw corruptedJournal(offset, "Key partition bucket mismatch");
            }
            if (op == OP_UPSERT) {
                if (!validJsonObjectDelimiters(body, keyLength, payloadLength)) {
                    throw corruptedJournal(offset, "Corrupted JSON document payload");
                }
                final long payloadOffset = offset + HEADER_BYTES + keyLength;
                final RecordLocation previous = index.put(
                        indexKey,
                        new RecordLocation(payloadOffset, keyLength, payloadLength)
                );
                if (previous != null) {
                    livePayloadBytes -= previous.payloadLength();
                    deadBytes += (long) HEADER_BYTES + previous.keyLength() + previous.payloadLength();
                }
                livePayloadBytes += payloadLength;
            } else {
                final RecordLocation previous = index.remove(indexKey);
                if (previous != null) {
                    livePayloadBytes -= previous.payloadLength();
                    deadBytes += (long) HEADER_BYTES + previous.keyLength() + previous.payloadLength()
                            + HEADER_BYTES + keyLength;
                } else {
                    deadBytes += (long) HEADER_BYTES + keyLength;
                }
            }
            offset += frameBytes;
        }
        for (final RecordLocation location : index.values()) {
            parseStoredObject(readPayloadBytes(journal, location));
        }
        if (incompleteTrailingWrite && offset < fileSize) {
            journal.truncate(offset);
            journal.force(false);
        }
        sortedKeys.clear();
        sortedKeys.addAll(index.keySet());
        writeOffset = offset;
    }

    private IllegalStateException corruptedJournal(final long offset, final String detail) {
        return new IllegalStateException("DATABASE_JOURNAL_CORRUPTED: " + detail + " at byte offset " + offset
                + " in " + journalPath + "; damaged journal preserved without truncation.");
    }

    private static boolean validPartialHeader(final byte[] partial) {
        final int len = partial.length;
        for (int i = 0; i < Math.min(len, 4); i++) {
            final byte expectedMagicByte = (byte) ((MAGIC >>> (24 - i * 8)) & 0xff);
            if (partial[i] != expectedMagicByte) {
                return false;
            }
        }
        final ByteBuffer buf = ByteBuffer.wrap(partial);
        if (len >= 5) {
            final byte op = buf.get(4);
            if (op != OP_UPSERT && op != OP_DELETE) {
                return false;
            }
            if (len >= 6) {
                final byte keyType = buf.get(5);
                if (keyType != KEY_STRING && keyType != KEY_NUMBER) {
                    return false;
                }
            }
            if (len >= 8) {
                final int bucket = Short.toUnsignedInt(buf.getShort(6));
                if (bucket >= BUCKET_COUNT) {
                    return false;
                }
            }
            if (len >= 12) {
                final int keyLength = buf.getInt(8);
                if (keyLength < 1 || keyLength > MAX_KEY_BYTES) {
                    return false;
                }
            }
            if (len >= 16) {
                final int payloadLength = buf.getInt(12);
                final boolean validPayload = op == OP_UPSERT
                        ? payloadLength >= 2 && payloadLength <= MAX_PAYLOAD_BYTES
                        : payloadLength == 0;
                if (!validPayload) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean validFrameHeader(
            final int magic,
            final byte op,
            final byte keyType,
            final int bucket,
            final int keyLength,
            final int payloadLength
    ) {
        if (magic != MAGIC) {
            return false;
        }
        if (op != OP_UPSERT && op != OP_DELETE) {
            return false;
        }
        if (keyType != KEY_STRING && keyType != KEY_NUMBER) {
            return false;
        }
        if (bucket < 0 || bucket >= BUCKET_COUNT) {
            return false;
        }
        if (keyLength < 1 || keyLength > MAX_KEY_BYTES) {
            return false;
        }
        return op == OP_UPSERT
                ? payloadLength >= 2 && payloadLength <= MAX_PAYLOAD_BYTES
                : payloadLength == 0;
    }

    private StepResult upsert(
            final String keyField,
            final boolean autoKey,
            final List<String> fields,
            final RailixValue candidate,
            final ParsedCondition condition
    ) {
        if (!condition.valid()) {
            return StepResult.outcome("invalid");
        }
        if (candidate instanceof RailixValue.ArrayValue array) {
            return upsertBatch(keyField, autoKey, fields, array, condition);
        }
        if (!(candidate instanceof RailixValue.ObjectValue rawObject)) {
            return StepResult.outcome("invalid");
        }
        final PreparedWrite prepared = prepareWrite(rawObject, keyField, autoKey, fields);
        if (prepared == null) {
            return StepResult.outcome("invalid");
        }
        final WriteOutcome outcome = commitPreparedWrites(
                List.of(prepared),
                Map.of(prepared.indexKey(), prepared.document()),
                keyField,
                condition.filter(),
                "upsert"
        );
        return switch (outcome) {
            case OK -> StepResult.outcome("ok").write("target", prepared.document());
            case NOT_FOUND -> StepResult.outcome("not_found");
            case INVALID -> StepResult.outcome("invalid");
        };
    }

    private StepResult upsertBatch(
            final String keyField,
            final boolean autoKey,
            final List<String> fields,
            final RailixValue candidate,
            final ParsedCondition condition
    ) {
        if (!condition.valid()) {
            return StepResult.outcome("invalid");
        }
        if (!(candidate instanceof RailixValue.ArrayValue array)) {
            return StepResult.outcome("invalid");
        }
        final List<RailixValue> items = array.values();
        if (items.isEmpty()) {
            return StepResult.outcome("ok").write("target", RailixValue.array(List.of()));
        }
        final int count = items.size();
        final List<PreparedWrite> preparedWrites = new ArrayList<>(count);
        final List<RailixValue> savedDocuments = new ArrayList<>(count);
        final Map<String, RailixValue.ObjectValue> finalDocuments = new LinkedHashMap<>();
        long totalFrameBytes = 0L;

        for (int i = 0; i < count; i++) {
            if (!(items.get(i) instanceof RailixValue.ObjectValue rawObject)) {
                return StepResult.outcome("invalid");
            }
            final PreparedWrite prepared = prepareWrite(rawObject, keyField, autoKey, fields);
            if (prepared == null) {
                return StepResult.outcome("invalid");
            }
            totalFrameBytes += prepared.frameBytes();
            if (totalFrameBytes > MAX_BULK_FRAME_BYTES) {
                return StepResult.outcome("invalid");
            }
            preparedWrites.add(prepared);
            savedDocuments.add(prepared.document());
            finalDocuments.put(prepared.indexKey(), prepared.document());
        }
        final WriteOutcome outcome = commitPreparedWrites(
                preparedWrites,
                finalDocuments,
                keyField,
                condition.filter(),
                "upsert"
        );
        return switch (outcome) {
            case OK -> StepResult.outcome("ok").write("target", RailixValue.array(savedDocuments));
            case NOT_FOUND -> StepResult.outcome("not_found");
            case INVALID -> StepResult.outcome("invalid");
        };
    }

    private PreparedWrite prepareWrite(
            final RailixValue.ObjectValue rawObject,
            final String keyField,
            final boolean autoKey,
            final List<String> fields
    ) {
        final RailixValue.ObjectValue document = prepareDocumentKey(rawObject, keyField, autoKey);
        if (document == null) {
            return null;
        }
        final String indexKey = encodeIndexKey(document.values().get(keyField));
        if (indexKey == null || DOCUMENT_REFINEMENT.rejection(document).isPresent()) {
            return null;
        }
        final RailixValue.ObjectValue canonicalDocument = canonicalObject(project(document, keyField, fields));
        if (!uniqueFieldsCompatible(uniqueFields, canonicalDocument)) {
            return null;
        }
        final Optional<String> serialized = RailixJson.write(canonicalDocument, MAX_PAYLOAD_BYTES);
        if (serialized.isEmpty()) {
            return null;
        }
        final byte keyType = indexKey.charAt(0) == 's' ? KEY_STRING : KEY_NUMBER;
        final byte[] keyBytes = indexKey.substring(2).getBytes(StandardCharsets.UTF_8);
        final byte[] payloadBytes = serialized.get().getBytes(StandardCharsets.UTF_8);
        return new PreparedWrite(
                indexKey,
                keyType,
                (short) bucketOf(indexKey),
                keyBytes,
                payloadBytes,
                canonicalDocument
        );
    }

    private WriteOutcome commitPreparedWrites(
            final List<PreparedWrite> writes,
            final Map<String, RailixValue.ObjectValue> finalDocuments,
            final String keyField,
            final FilterNode condition,
            final String operationLabel
    ) {
        long totalBytes = 0L;
        for (final PreparedWrite write : writes) {
            totalBytes += write.frameBytes();
        }
        final ByteBuffer batch = ByteBuffer.allocate((int) totalBytes);
        for (final PreparedWrite write : writes) {
            appendFrame(batch, OP_UPSERT, write.keyType(), write.bucket(), write.keyBytes(), write.payloadBytes());
        }
        batch.flip();

        rwLock.writeLock().lock();
        try {
            ensureOpen();
            if (condition != null && !conditionsSatisfiedUnderLock(writes, keyField, condition)) {
                return WriteOutcome.NOT_FOUND;
            }
            if (!uniqueFields.isEmpty() && uniqueConflict(finalDocuments)) {
                return WriteOutcome.INVALID;
            }
            final long startOffset = writeOffset;
            writeFully(journal, batch, startOffset);
            journal.force(false);
            long cursor = startOffset;
            for (final PreparedWrite write : writes) {
                final int keyLen = write.keyBytes().length;
                final int payloadLen = write.payloadBytes().length;
                final long payloadOffset = cursor + HEADER_BYTES + keyLen;
                final RecordLocation previous = index.put(
                        write.indexKey(),
                        new RecordLocation(payloadOffset, keyLen, payloadLen)
                );
                if (previous != null) {
                    livePayloadBytes -= previous.payloadLength();
                    deadBytes += (long) HEADER_BYTES + previous.keyLength() + previous.payloadLength();
                } else {
                    sortedKeys.add(write.indexKey());
                }
                livePayloadBytes += payloadLen;
                cursor += write.frameBytes();
            }
            writeOffset = cursor;
            for (final Map.Entry<String, RailixValue.ObjectValue> entry : finalDocuments.entrySet()) {
                reindex(entry.getKey(), entry.getValue());
            }
            if (shouldCompact()) {
                compactUnderWriteLock();
            }
            return WriteOutcome.OK;
        } catch (final IOException exception) {
            rollbackJournalTail(exception);
            throw new UncheckedIOException(
                    "Database " + operationLabel + " failed in collection " + directory + ".",
                    exception
            );
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private boolean conditionsSatisfiedUnderLock(
            final List<PreparedWrite> writes,
            final String keyField,
            final FilterNode condition
    ) throws IOException {
        final Map<String, RailixValue.ObjectValue> staged = new HashMap<>();
        for (final PreparedWrite write : writes) {
            final String indexKey = write.indexKey();
            final RailixValue.ObjectValue current;
            if (staged.containsKey(indexKey)) {
                current = staged.get(indexKey);
            } else {
                final RecordLocation existingLoc = index.get(indexKey);
                current = existingLoc == null ? null : parseStoredObject(readPayloadBytes(journal, existingLoc));
            }
            if (!matchesCondition(current, condition, keyField)) {
                return false;
            }
            staged.put(indexKey, write.document());
        }
        return true;
    }

    private StepResult get(
            final String keyField,
            final List<String> fields,
            final RailixValue.ObjectValue defaults,
            final RailixValue keyCandidate
    ) {
        final String indexKey = encodeIndexKey(keyCandidate);
        if (indexKey == null) {
            return StepResult.outcome("invalid");
        }
        final byte[] payloadBytes;
        rwLock.readLock().lock();
        try {
            ensureOpen();
            final RecordLocation location = index.get(indexKey);
            if (location == null) {
                return StepResult.outcome("not_found");
            }
            payloadBytes = readPayloadBytes(journal, location);
        } catch (final IOException exception) {
            throw new UncheckedIOException("Database get failed in collection " + directory + ".", exception);
        } finally {
            rwLock.readLock().unlock();
        }
        final RailixValue.ObjectValue stored = parseStoredObject(payloadBytes);
        return StepResult.outcome("ok").write("target", readView(stored, keyField, fields, defaults));
    }

    private StepResult find(
            final String keyField,
            final List<String> fields,
            final RailixValue.ObjectValue defaults,
            final int limit,
            final RailixValue filterCandidate,
            final RailixValue afterCandidate
    ) {
        final FilterNode filter = parseFilter(filterCandidate, true);
        if (filter == null) {
            return StepResult.outcome("invalid");
        }
        final String afterIndexKey;
        if (afterCandidate == null || afterCandidate instanceof RailixValue.NullValue) {
            afterIndexKey = null;
        } else {
            afterIndexKey = encodeIndexKey(afterCandidate);
            if (afterIndexKey == null) {
                return StepResult.outcome("invalid");
            }
        }

        final List<RailixValue> results = new ArrayList<>();
        rwLock.readLock().lock();
        try {
            ensureOpen();
            final Set<String> candidateKeys = resolveCandidateKeys(keyField, filter);
            if (candidateKeys != null && candidateKeys.isEmpty()) {
                return StepResult.outcome("ok").write("target", RailixValue.array(List.of()));
            }

            final Collection<String> orderedKeys;
            if (candidateKeys != null) {
                final List<String> sortedCandidates = new ArrayList<>(candidateKeys);
                sortedCandidates.sort(DatabaseStepHandlers::compareTaggedValues);
                orderedKeys = sortedCandidates;
            } else {
                orderedKeys = afterIndexKey != null ? sortedKeys.tailSet(afterIndexKey, false) : sortedKeys;
            }

            for (final String indexKey : orderedKeys) {
                if (candidateKeys != null && afterIndexKey != null
                        && compareTaggedValues(indexKey, afterIndexKey) <= 0) {
                    continue;
                }
                final RecordLocation location = index.get(indexKey);
                if (location == null) {
                    continue;
                }
                final byte[] payloadBytes = readPayloadBytes(journal, location);
                final RailixValue.ObjectValue stored = parseStoredObject(payloadBytes);
                if (matchesFilterNode(stored, filter)) {
                    results.add(readView(stored, keyField, fields, defaults));
                    if (results.size() >= limit) {
                        break;
                    }
                }
            }
        } catch (final IOException exception) {
            throw new UncheckedIOException("Database find failed in collection " + directory + ".", exception);
        } finally {
            rwLock.readLock().unlock();
        }
        return StepResult.outcome("ok").write("target", RailixValue.array(results));
    }

    private static int compareTaggedValues(final String left, final String right) {
        if (left.equals(right)) {
            return 0;
        }
        final char leftType = left.charAt(0);
        final char rightType = right.charAt(0);
        if (leftType != rightType) {
            return Character.compare(leftType, rightType);
        }
        return switch (leftType) {
            case 'b' -> left.compareTo(right);
            case 'n' -> new BigDecimal(left.substring(2)).compareTo(new BigDecimal(right.substring(2)));
            default -> left.substring(2).compareTo(right.substring(2));
        };
    }

    private static FilterNode parseFilter(final RailixValue candidate, final boolean allowEmpty) {
        if (candidate == null || candidate instanceof RailixValue.NullValue) {
            return allowEmpty ? MATCH_ALL_FILTER : null;
        }
        if (!(candidate instanceof RailixValue.ObjectValue object)
                || DOCUMENT_REFINEMENT.rejection(object).isPresent()) {
            return null;
        }
        return parseFilterObject(object, 0, new int[]{MAX_FILTER_CLAUSES});
    }

    private static FilterNode parseFilterObject(
            final RailixValue.ObjectValue object,
            final int depth,
            final int[] budget
    ) {
        if (depth > MAX_FILTER_DEPTH) {
            return null;
        }
        final Map<String, RailixValue> entries = object.values();
        if (entries.isEmpty()) {
            return MATCH_ALL_FILTER;
        }
        final List<FilterNode> children = new ArrayList<>(entries.size());
        for (final Map.Entry<String, RailixValue> entry : entries.entrySet()) {
            if (--budget[0] < 0) {
                return null;
            }
            final String key = entry.getKey();
            final RailixValue value = entry.getValue();
            if ("$and".equals(key) || "$or".equals(key)) {
                if (!(value instanceof RailixValue.ArrayValue array) || array.values().isEmpty()) {
                    return null;
                }
                final List<FilterNode> subNodes = new ArrayList<>(array.values().size());
                for (final RailixValue item : array.values()) {
                    if (!(item instanceof RailixValue.ObjectValue subObject)) {
                        return null;
                    }
                    final FilterNode parsed = parseFilterObject(subObject, depth + 1, budget);
                    if (parsed == null) {
                        return null;
                    }
                    subNodes.add(parsed);
                }
                children.add("$and".equals(key) ? new AndNode(subNodes) : new OrNode(subNodes));
            } else if (key.startsWith("$") || !validFieldPath(key)) {
                return null;
            } else {
                final List<FieldPredicate> predicates = parseFieldPredicates(value, budget);
                if (predicates == null) {
                    return null;
                }
                children.add(new FieldNode(key, splitFieldPath(key), predicates));
            }
        }
        return children.size() == 1 ? children.getFirst() : new AndNode(List.copyOf(children));
    }

    private static List<FieldPredicate> parseFieldPredicates(final RailixValue value, final int[] budget) {
        if (!(value instanceof RailixValue.ObjectValue object) || !hasOperatorKey(object)) {
            return List.of(new FieldPredicate(FilterOp.EQ, canonicalValue(value), Set.of(), false));
        }
        final List<FieldPredicate> predicates = new ArrayList<>(object.values().size());
        for (final Map.Entry<String, RailixValue> entry : object.values().entrySet()) {
            if (--budget[0] < 0) {
                return null;
            }
            final String opName = entry.getKey();
            if (!opName.startsWith("$")) {
                return null;
            }
            final FieldPredicate parsed = parseSingleOperator(opName, entry.getValue());
            if (parsed == null) {
                return null;
            }
            predicates.add(parsed);
        }
        return List.copyOf(predicates);
    }

    private static FieldPredicate parseSingleOperator(final String opName, final RailixValue operand) {
        return switch (opName) {
            case "$eq" -> new FieldPredicate(FilterOp.EQ, canonicalValue(operand), Set.of(), false);
            case "$ne" -> new FieldPredicate(FilterOp.NE, canonicalValue(operand), Set.of(), false);
            case "$gt", "$gte", "$lt", "$lte" -> {
                if (!(operand instanceof RailixValue.NumberValue)
                        && !(operand instanceof RailixValue.StringValue)) {
                    yield null;
                }
                final FilterOp op = switch (opName) {
                    case "$gt" -> FilterOp.GT;
                    case "$gte" -> FilterOp.GTE;
                    case "$lt" -> FilterOp.LT;
                    default -> FilterOp.LTE;
                };
                yield new FieldPredicate(op, canonicalValue(operand), Set.of(), false);
            }
            case "$in", "$nin" -> {
                if (!(operand instanceof RailixValue.ArrayValue array)
                        || array.values().size() > MAX_OPERATOR_ARRAY_SIZE) {
                    yield null;
                }
                final Set<RailixValue> canonicalSet = new HashSet<>(Math.max(4, array.values().size() * 2));
                for (final RailixValue item : array.values()) {
                    canonicalSet.add(canonicalValue(item));
                }
                yield new FieldPredicate(
                        "$in".equals(opName) ? FilterOp.IN : FilterOp.NIN,
                        null,
                        Set.copyOf(canonicalSet),
                        false
                );
            }
            case "$exists" -> operand instanceof RailixValue.BooleanValue bool
                    ? new FieldPredicate(FilterOp.EXISTS, null, Set.of(), bool.value())
                    : null;
            default -> null;
        };
    }

    private static boolean hasOperatorKey(final RailixValue.ObjectValue object) {
        for (final String key : object.values().keySet()) {
            if (key.startsWith("$")) {
                return true;
            }
        }
        return false;
    }

    private Set<String> resolveCandidateKeys(final String keyField, final FilterNode node) {
        return switch (node) {
            case AndNode and -> intersectClauseCandidates(keyField, and.children());
            case OrNode or -> unionBranchCandidates(keyField, or.children());
            case FieldNode field -> resolveFieldCandidates(keyField, field);
        };
    }

    private Set<String> intersectClauseCandidates(final String keyField, final List<FilterNode> children) {
        if (children.isEmpty()) {
            return null;
        }
        List<Set<String>> matchedSets = null;
        for (final FilterNode child : children) {
            final Set<String> candidates = resolveCandidateKeys(keyField, child);
            if (candidates == null) {
                continue;
            }
            if (candidates.isEmpty()) {
                return Set.of();
            }
            if (matchedSets == null) {
                matchedSets = new ArrayList<>(2);
            }
            matchedSets.add(candidates);
        }
        return intersectMatchedSets(matchedSets);
    }

    private Set<String> unionBranchCandidates(final String keyField, final List<FilterNode> branches) {
        final Set<String> union = new LinkedHashSet<>();
        for (final FilterNode branch : branches) {
            final Set<String> candidates = resolveCandidateKeys(keyField, branch);
            if (candidates == null) {
                return null;
            }
            union.addAll(candidates);
        }
        return union;
    }

    private Set<String> resolveFieldCandidates(final String keyField, final FieldNode field) {
        if (field.fieldPath().equals(keyField)) {
            return resolvePrimaryKeyCandidates(field.predicates());
        }
        final NavigableMap<String, Set<String>> byValue = indexKeysByValue.get(field.fieldPath());
        if (byValue == null) {
            return null;
        }
        List<Set<String>> matchedSets = null;
        for (final FieldPredicate predicate : field.predicates()) {
            final Set<String> set = switch (predicate.op()) {
                case EQ -> {
                    final String enc = indexValue(predicate.scalar());
                    yield enc == null ? null : byValue.getOrDefault(enc, Set.of());
                }
                case IN -> secondaryInCandidates(byValue, predicate.set());
                default -> null;
            };
            if (set != null) {
                if (set.isEmpty()) {
                    return Set.of();
                }
                if (matchedSets == null) {
                    matchedSets = new ArrayList<>(2);
                }
                matchedSets.add(set);
            }
        }
        final RangeBounds bounds = rangeBounds(field.predicates());
        if (bounds != null) {
            if (bounds.empty()) {
                return Set.of();
            }
            final Set<String> rangeMatches = new LinkedHashSet<>();
            collectKeysInRange(
                    byValue.navigableKeySet(),
                    bounds,
                    valueKey -> rangeMatches.addAll(byValue.get(valueKey))
            );
            if (rangeMatches.isEmpty()) {
                return Set.of();
            }
            if (matchedSets == null) {
                matchedSets = new ArrayList<>(1);
            }
            matchedSets.add(rangeMatches);
        }
        return intersectMatchedSets(matchedSets);
    }

    private static Set<String> secondaryInCandidates(
            final NavigableMap<String, Set<String>> byValue,
            final Set<RailixValue> items
    ) {
        final Set<String> union = new LinkedHashSet<>();
        for (final RailixValue item : items) {
            final String enc = indexValue(item);
            if (enc == null) {
                return null;
            }
            union.addAll(byValue.getOrDefault(enc, Set.of()));
        }
        return union;
    }

    private Set<String> resolvePrimaryKeyCandidates(final List<FieldPredicate> predicates) {
        List<Set<String>> matchedSets = null;
        for (final FieldPredicate predicate : predicates) {
            final Set<String> set = switch (predicate.op()) {
                case EQ -> {
                    final String key = encodeIndexKey(predicate.scalar());
                    yield key != null && index.containsKey(key) ? Set.of(key) : Set.of();
                }
                case IN -> primaryInCandidates(predicate.set());
                case EXISTS -> predicate.boolValue() ? null : Set.of();
                default -> null;
            };
            if (set != null) {
                if (set.isEmpty()) {
                    return Set.of();
                }
                if (matchedSets == null) {
                    matchedSets = new ArrayList<>(2);
                }
                matchedSets.add(set);
            }
        }
        final RangeBounds bounds = rangeBounds(predicates);
        if (bounds != null) {
            if (bounds.empty()) {
                return Set.of();
            }
            final Set<String> rangeMatches = new LinkedHashSet<>();
            collectKeysInRange(sortedKeys, bounds, rangeMatches::add);
            if (rangeMatches.isEmpty()) {
                return Set.of();
            }
            if (matchedSets == null) {
                matchedSets = new ArrayList<>(1);
            }
            matchedSets.add(rangeMatches);
        }
        return intersectMatchedSets(matchedSets);
    }

    private Set<String> primaryInCandidates(final Set<RailixValue> items) {
        final Set<String> matches = new LinkedHashSet<>();
        for (final RailixValue item : items) {
            final String key = encodeIndexKey(item);
            if (key != null && index.containsKey(key)) {
                matches.add(key);
            }
        }
        return matches;
    }

    private static Set<String> intersectMatchedSets(final List<Set<String>> matchedSets) {
        if (matchedSets == null) {
            return null;
        }
        if (matchedSets.size() == 1) {
            return matchedSets.getFirst();
        }
        matchedSets.sort(Comparator.comparingInt(Set::size));
        final Set<String> intersection = new LinkedHashSet<>(matchedSets.getFirst());
        for (int i = 1; i < matchedSets.size(); i++) {
            intersection.retainAll(matchedSets.get(i));
            if (intersection.isEmpty()) {
                return Set.of();
            }
        }
        return intersection;
    }

    private static RangeBounds rangeBounds(final List<FieldPredicate> predicates) {
        char targetType = 0;
        String loKey = null;
        boolean loInc = false;
        String hiKey = null;
        boolean hiInc = false;
        boolean hasRange = false;

        for (final FieldPredicate predicate : predicates) {
            final FilterOp op = predicate.op();
            if (op != FilterOp.GT && op != FilterOp.GTE && op != FilterOp.LT && op != FilterOp.LTE) {
                continue;
            }
            hasRange = true;
            final String encoded = indexValue(predicate.scalar());
            if (encoded == null) {
                return RangeBounds.EMPTY;
            }
            final char type = encoded.charAt(0);
            if (targetType == 0) {
                targetType = type;
            } else if (targetType != type) {
                return RangeBounds.EMPTY;
            }
            final boolean inclusive = op == FilterOp.GTE || op == FilterOp.LTE;
            if (op == FilterOp.GT || op == FilterOp.GTE) {
                final int cmp = loKey == null ? 1 : compareTaggedValues(encoded, loKey);
                if (loKey == null || cmp > 0 || (cmp == 0 && !inclusive)) {
                    loKey = encoded;
                    loInc = inclusive;
                }
            } else {
                final int cmp = hiKey == null ? -1 : compareTaggedValues(encoded, hiKey);
                if (hiKey == null || cmp < 0 || (cmp == 0 && !inclusive)) {
                    hiKey = encoded;
                    hiInc = inclusive;
                }
            }
        }
        if (!hasRange) {
            return null;
        }
        if (loKey != null && hiKey != null) {
            final int cmp = compareTaggedValues(loKey, hiKey);
            if (cmp > 0 || (cmp == 0 && (!loInc || !hiInc))) {
                return RangeBounds.EMPTY;
            }
        }
        return new RangeBounds(targetType, loKey, loInc, hiKey, hiInc, false);
    }

    private static void collectKeysInRange(
            final NavigableSet<String> keySet,
            final RangeBounds bounds,
            final Consumer<String> consumer
    ) {
        if (bounds.empty()) {
            return;
        }
        final NavigableSet<String> slice;
        if (bounds.loKey() != null && bounds.hiKey() != null) {
            slice = keySet.subSet(bounds.loKey(), bounds.loInclusive(), bounds.hiKey(), bounds.hiInclusive());
        } else if (bounds.loKey() != null) {
            slice = keySet.tailSet(bounds.loKey(), bounds.loInclusive());
        } else {
            slice = keySet.headSet(bounds.hiKey(), bounds.hiInclusive()).descendingSet();
        }
        for (final String key : slice) {
            if (key.charAt(0) != bounds.targetType()) {
                break;
            }
            consumer.accept(key);
        }
    }

    private static boolean matchesCondition(
            final RailixValue.ObjectValue existing,
            final FilterNode condition,
            final String keyField
    ) {
        return existing == null
                ? conditionAllowsAbsentDocument(condition, keyField)
                : matchesFilterNode(existing, condition);
    }

    private static boolean conditionAllowsAbsentDocument(final FilterNode node, final String keyField) {
        return switch (node) {
            case AndNode and -> {
                if (and.children().isEmpty()) {
                    yield false;
                }
                for (final FilterNode child : and.children()) {
                    if (!conditionAllowsAbsentDocument(child, keyField)) {
                        yield false;
                    }
                }
                yield true;
            }
            case OrNode or -> {
                for (final FilterNode child : or.children()) {
                    if (conditionAllowsAbsentDocument(child, keyField)) {
                        yield true;
                    }
                }
                yield false;
            }
            case FieldNode field -> {
                if (!field.fieldPath().equals(keyField)) {
                    yield false;
                }
                boolean explicitAbsent = false;
                for (final FieldPredicate predicate : field.predicates()) {
                    if (predicate.op() == FilterOp.EXISTS && !predicate.boolValue()) {
                        explicitAbsent = true;
                    } else if (!matchesFieldPredicate(null, predicate)) {
                        yield false;
                    }
                }
                yield explicitAbsent;
            }
        };
    }

    private static boolean matchesFilterNode(final RailixValue.ObjectValue stored, final FilterNode node) {
        return switch (node) {
            case AndNode and -> {
                for (final FilterNode child : and.children()) {
                    if (!matchesFilterNode(stored, child)) {
                        yield false;
                    }
                }
                yield true;
            }
            case OrNode or -> {
                for (final FilterNode child : or.children()) {
                    if (matchesFilterNode(stored, child)) {
                        yield true;
                    }
                }
                yield false;
            }
            case FieldNode field -> {
                final RailixValue actual = readPathValue(stored, field.segments());
                for (final FieldPredicate predicate : field.predicates()) {
                    if (!matchesFieldPredicate(actual, predicate)) {
                        yield false;
                    }
                }
                yield true;
            }
        };
    }

    private static boolean matchesFieldPredicate(final RailixValue actual, final FieldPredicate predicate) {
        return switch (predicate.op()) {
            case EXISTS -> (actual != null) == predicate.boolValue();
            case EQ -> eqMatch(actual, predicate.scalar());
            case NE -> !eqMatch(actual, predicate.scalar());
            case IN -> inMatch(actual, predicate.set());
            case NIN -> !inMatch(actual, predicate.set());
            case GT, GTE, LT, LTE -> compareMatch(actual, predicate.op(), predicate.scalar());
        };
    }

    private static boolean eqMatch(final RailixValue actual, final RailixValue expected) {
        if (expected instanceof RailixValue.NullValue) {
            return actual == null || actual instanceof RailixValue.NullValue;
        }
        if (actual == null) {
            return false;
        }
        return canonicalValueEquals(actual, expected);
    }

    private static boolean inMatch(final RailixValue actual, final Set<RailixValue> expectedSet) {
        if (actual == null || actual instanceof RailixValue.NullValue) {
            return expectedSet.contains(RailixValue.nullValue());
        }
        return expectedSet.contains(canonicalValue(actual));
    }

    private static boolean compareMatch(
            final RailixValue actual,
            final FilterOp op,
            final RailixValue expected
    ) {
        if (actual == null) {
            return false;
        }
        final int cmp;
        if (expected instanceof RailixValue.NumberValue expectedNum) {
            if (!(actual instanceof RailixValue.NumberValue actualNum)) {
                return false;
            }
            cmp = actualNum.value().compareTo(expectedNum.value());
        } else if (expected instanceof RailixValue.StringValue expectedStr) {
            if (!(actual instanceof RailixValue.StringValue actualStr)) {
                return false;
            }
            cmp = actualStr.value().compareTo(expectedStr.value());
        } else {
            return false;
        }
        return switch (op) {
            case GT -> cmp > 0;
            case GTE -> cmp >= 0;
            case LT -> cmp < 0;
            case LTE -> cmp <= 0;
            default -> false;
        };
    }

    private static boolean canonicalValueEquals(final RailixValue actual, final RailixValue expectedCanonical) {
        if (expectedCanonical instanceof RailixValue.NumberValue expectedNum) {
            return actual instanceof RailixValue.NumberValue actualNum
                    && actualNum.value().compareTo(expectedNum.value()) == 0;
        }
        if (expectedCanonical instanceof RailixValue.StringValue
                || expectedCanonical instanceof RailixValue.BooleanValue) {
            return actual.equals(expectedCanonical);
        }
        return canonicalValue(actual).equals(expectedCanonical);
    }

    private StepResult delete(
            final String keyField,
            final RailixValue keyCandidate,
            final ParsedCondition condition
    ) {
        if (!condition.valid()) {
            return StepResult.outcome("invalid");
        }
        final String indexKey = encodeIndexKey(keyCandidate);
        if (indexKey == null) {
            return StepResult.outcome("invalid");
        }
        final byte keyType = indexKey.charAt(0) == 's' ? KEY_STRING : KEY_NUMBER;
        final byte[] keyBytes = indexKey.substring(2).getBytes(StandardCharsets.UTF_8);
        final ByteBuffer tombstone = buildFrame(OP_DELETE, keyType, (short) bucketOf(indexKey), keyBytes, new byte[0]);
        final byte[] removedBytes;
        RailixValue.ObjectValue deletedDocument = null;

        rwLock.writeLock().lock();
        try {
            ensureOpen();
            final RecordLocation existing = index.get(indexKey);
            if (existing == null) {
                return StepResult.outcome("not_found");
            }
            removedBytes = readPayloadBytes(journal, existing);
            if (condition.filter() != null) {
                deletedDocument = parseStoredObject(removedBytes);
                if (!matchesCondition(deletedDocument, condition.filter(), keyField)) {
                    return StepResult.outcome("not_found");
                }
            }
            final long frameOffset = writeOffset;
            writeFully(journal, tombstone, frameOffset);
            journal.force(false);
            writeOffset = frameOffset + HEADER_BYTES + keyBytes.length;
            index.remove(indexKey);
            sortedKeys.remove(indexKey);
            reindex(indexKey, null);
            livePayloadBytes -= existing.payloadLength();
            deadBytes += (long) HEADER_BYTES + existing.keyLength() + existing.payloadLength()
                    + HEADER_BYTES + keyBytes.length;
            if (shouldCompact()) {
                compactUnderWriteLock();
            }
        } catch (final IOException exception) {
            rollbackJournalTail(exception);
            throw new UncheckedIOException("Database delete failed in collection " + directory + ".", exception);
        } finally {
            rwLock.writeLock().unlock();
        }
        final RailixValue.ObjectValue returned = deletedDocument != null
                ? deletedDocument
                : parseStoredObject(removedBytes);
        return StepResult.outcome("ok").write("target", returned);
    }

    private boolean shouldCompact() {
        return deadBytes >= COMPACT_MIN_DEAD_BYTES && deadBytes > livePayloadBytes;
    }

    private void compactUnderWriteLock() throws IOException {
        final long pauseStart = System.nanoTime();
        final List<Map.Entry<String, RecordLocation>> ordered = new ArrayList<>(index.entrySet());
        ordered.sort(Comparator.comparingLong(entry -> entry.getValue().frameOffset()));

        final Map<String, RecordLocation> compactedIndex = new HashMap<>(Math.max(16, ordered.size() * 2));
        long compactedOffset = 0L;
        long compactedLiveBytes = 0L;
        Files.deleteIfExists(compactPath);
        try (FileChannel target = FileChannel.open(
                compactPath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        )) {
            for (final Map.Entry<String, RecordLocation> entry : ordered) {
                final String indexKey = entry.getKey();
                final byte keyType = indexKey.charAt(0) == 's' ? KEY_STRING : KEY_NUMBER;
                final byte[] keyBytes = indexKey.substring(2).getBytes(StandardCharsets.UTF_8);
                final byte[] payloadBytes = readPayloadBytes(journal, entry.getValue());
                final ByteBuffer frame = buildFrame(
                        OP_UPSERT,
                        keyType,
                        (short) bucketOf(indexKey),
                        keyBytes,
                        payloadBytes
                );
                writeFully(target, frame, compactedOffset);
                final long payloadOffset = compactedOffset + HEADER_BYTES + keyBytes.length;
                compactedIndex.put(indexKey, new RecordLocation(payloadOffset, keyBytes.length, payloadBytes.length));
                compactedOffset += (long) HEADER_BYTES + keyBytes.length + payloadBytes.length;
                compactedLiveBytes += payloadBytes.length;
            }
            target.force(true);
        }
        final FileChannel previousJournal = journal;
        journal = null;
        previousJournal.close();
        moveJournal(compactPath, journalPath);
        syncDirectory();
        journal = FileChannel.open(
                journalPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        );
        index.clear();
        index.putAll(compactedIndex);
        writeOffset = compactedOffset;
        livePayloadBytes = compactedLiveBytes;
        deadBytes = 0L;
        lastCompactionPauseNanos = System.nanoTime() - pauseStart;
    }

    private static void moveJournal(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void syncDirectory() {
        try (FileChannel dirChannel = FileChannel.open(directory, StandardOpenOption.READ)) {
            dirChannel.force(true);
        } catch (final IOException | UnsupportedOperationException ignored) {
            // Directory fsync is best-effort on filesystems/OSes that reject directory channels.
        }
    }

    private void rollbackJournalTail(final IOException primaryFailure) {
        if (journal != null && journal.isOpen()) {
            try {
                if (journal.size() > writeOffset) {
                    journal.truncate(writeOffset);
                }
            } catch (final IOException suppressed) {
                primaryFailure.addSuppressed(suppressed);
            }
        }
    }

    private void ensureOpen() {
        if (closed || journal == null || !journal.isOpen()) {
            throw new IllegalStateException("Database collection store is already closed: " + directory);
        }
    }

    private void closeStore() {
        rwLock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            IOException firstFailure = null;
            if (journal != null) {
                try {
                    if (journal.isOpen()) {
                        if (initialized) {
                            journal.force(true);
                        }
                        journal.close();
                    }
                } catch (final IOException failure) {
                    firstFailure = failure;
                } finally {
                    journal = null;
                }
            }
            if (fileLock != null) {
                try {
                    if (fileLock.isValid()) {
                        fileLock.release();
                    }
                } catch (final IOException failure) {
                    if (firstFailure == null) {
                        firstFailure = failure;
                    }
                } finally {
                    fileLock = null;
                }
            }
            if (lockChannel != null) {
                try {
                    lockChannel.close();
                } catch (final IOException failure) {
                    if (firstFailure == null) {
                        firstFailure = failure;
                    }
                } finally {
                    lockChannel = null;
                }
            }
            index.clear();
            sortedKeys.clear();
            indexValueByKey.clear();
            indexKeysByValue.clear();
            if (firstFailure != null) {
                throw new UncheckedIOException("Failed to close database collection " + directory + ".", firstFailure);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void closeQuietly() {
        try {
            closeStore();
        } catch (final RuntimeException ignored) {
            // Preserve the primary startup failure.
        }
    }

    private static RailixValue.ObjectValue prepareDocumentKey(
            final RailixValue.ObjectValue source,
            final String keyField,
            final boolean autoKey
    ) {
        final RailixValue existing = source.values().get(keyField);
        if (existing == null || existing instanceof RailixValue.NullValue) {
            if (!autoKey) {
                return null;
            }
            final Map<String, RailixValue> copy = new TreeMap<>(source.values());
            copy.put(keyField, RailixValue.string(UUID.randomUUID().toString()));
            return RailixValue.object(copy);
        }
        return source;
    }

    private static String encodeIndexKey(final RailixValue key) {
        if (key instanceof RailixValue.StringValue string) {
            final String raw = string.value();
            if (raw.isEmpty() || invalidSurrogate(raw)) {
                return null;
            }
            if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
                return null;
            }
            return "s:" + raw;
        }
        if (key instanceof RailixValue.NumberValue number) {
            final BigDecimal raw = number.value();
            if (!RailixData.fitsCanonicalNumber(raw)) {
                return null;
            }
            final BigDecimal canonical = raw.signum() == 0 ? BigDecimal.ZERO : raw.stripTrailingZeros();
            final String encoded = canonical.toString();
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
                return null;
            }
            return "n:" + encoded;
        }
        return null;
    }

    private static int bucketOf(final String indexKey) {
        final CRC32 crc = new CRC32();
        final byte[] bytes = indexKey.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return ((int) crc.getValue()) & BUCKET_MASK;
    }

    private static ByteBuffer buildFrame(
            final byte op,
            final byte keyType,
            final short bucket,
            final byte[] keyBytes,
            final byte[] payloadBytes
    ) {
        final ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + keyBytes.length + payloadBytes.length);
        appendFrame(buffer, op, keyType, bucket, keyBytes, payloadBytes);
        buffer.flip();
        return buffer;
    }

    private static void appendFrame(
            final ByteBuffer target,
            final byte op,
            final byte keyType,
            final short bucket,
            final byte[] keyBytes,
            final byte[] payloadBytes
    ) {
        final CRC32 crc = new CRC32();
        crc.update(op & 0xff);
        crc.update(keyType & 0xff);
        crc.update((bucket >>> 8) & 0xff);
        crc.update(bucket & 0xff);
        updateInt(crc, keyBytes.length);
        updateInt(crc, payloadBytes.length);
        crc.update(keyBytes, 0, keyBytes.length);
        crc.update(payloadBytes, 0, payloadBytes.length);

        target.putInt(MAGIC);
        target.put(op);
        target.put(keyType);
        target.putShort(bucket);
        target.putInt(keyBytes.length);
        target.putInt(payloadBytes.length);
        target.putInt((int) crc.getValue());
        target.put(keyBytes);
        target.put(payloadBytes);
    }

    private static int frameChecksum(
            final byte op,
            final byte keyType,
            final short bucket,
            final int keyLength,
            final int payloadLength,
            final byte[] body
    ) {
        final CRC32 crc = new CRC32();
        crc.update(op & 0xff);
        crc.update(keyType & 0xff);
        crc.update((bucket >>> 8) & 0xff);
        crc.update(bucket & 0xff);
        updateInt(crc, keyLength);
        updateInt(crc, payloadLength);
        crc.update(body, 0, body.length);
        return (int) crc.getValue();
    }

    private static void updateInt(final CRC32 crc, final int value) {
        crc.update((value >>> 24) & 0xff);
        crc.update((value >>> 16) & 0xff);
        crc.update((value >>> 8) & 0xff);
        crc.update(value & 0xff);
    }

    private static byte[] readPayloadBytes(
            final FileChannel channel,
            final RecordLocation location
    ) throws IOException {
        final byte[] bytes = new byte[location.payloadLength()];
        if (!readFully(channel, ByteBuffer.wrap(bytes), location.payloadOffset())) {
            throw new IOException("Truncated journal payload at offset " + location.payloadOffset()
                    + " (" + location.payloadLength() + " bytes).");
        }
        return bytes;
    }

    private static boolean readFully(
            final FileChannel channel,
            final ByteBuffer target,
            final long startOffset
    ) throws IOException {
        long position = startOffset;
        while (target.hasRemaining()) {
            final int read = channel.read(target, position);
            if (read < 0) {
                return false;
            }
            position += read;
        }
        return true;
    }

    private static void writeFully(
            final FileChannel channel,
            final ByteBuffer source,
            final long startOffset
    ) throws IOException {
        long position = startOffset;
        while (source.hasRemaining()) {
            position += channel.write(source, position);
        }
    }

    private static String decodeUtf8(final byte[] bytes, final int offset, final int length) {
        try {
            final String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length))
                    .toString();
            return invalidSurrogate(decoded) ? null : decoded;
        } catch (final CharacterCodingException invalidUtf8) {
            return null;
        }
    }

    private static boolean validJsonObjectDelimiters(final byte[] bytes, final int offset, final int length) {
        return length >= 2 && bytes[offset] == '{' && bytes[offset + length - 1] == '}';
    }

    private static RailixValue.ObjectValue parseStoredObject(final byte[] payloadBytes) {
        final String text = decodeUtf8(payloadBytes, 0, payloadBytes.length);
        if (text != null && RailixJson.parse(text) instanceof RailixJson.Parsed(RailixValue.ObjectValue object)) {
            return object;
        }
        throw new IllegalStateException(
                "DATABASE_JOURNAL_CORRUPTED: Corrupted stored JSON document in database journal."
        );
    }

    private static RailixValue.ObjectValue canonicalObject(final RailixValue.ObjectValue object) {
        return (RailixValue.ObjectValue) canonicalValue(object);
    }

    private static RailixValue canonicalValue(final RailixValue value) {
        return switch (value) {
            case RailixValue.NullValue ignored -> RailixValue.nullValue();
            case RailixValue.BooleanValue bool -> bool;
            case RailixValue.StringValue string -> string;
            case RailixValue.NumberValue number -> {
                final BigDecimal raw = number.value();
                final BigDecimal stripped = raw.signum() == 0 ? BigDecimal.ZERO : raw.stripTrailingZeros();
                final BigDecimal canonical = stripped.scale() < 0 ? stripped.setScale(0) : stripped;
                yield canonical.equals(raw) ? number : RailixValue.number(canonical);
            }
            case RailixValue.ArrayValue array -> {
                final List<RailixValue> items = new ArrayList<>(array.values().size());
                for (final RailixValue item : array.values()) {
                    items.add(canonicalValue(item));
                }
                yield RailixValue.array(items);
            }
            case RailixValue.ObjectValue object -> {
                final Map<String, RailixValue> fields = new TreeMap<>();
                for (final Map.Entry<String, RailixValue> entry : object.values().entrySet()) {
                    fields.put(entry.getKey(), canonicalValue(entry.getValue()));
                }
                yield RailixValue.object(fields);
            }
        };
    }

    private static boolean invalidSurrogate(final String value) {
        for (int index = 0; index < value.length(); index++) {
            final char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
