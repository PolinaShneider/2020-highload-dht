package ru.mail.polis.service;

import one.nio.http.Response;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Comparator;

public final class Value {
    private static final String TIMESTAMP_HEADER = "Timestamp: ";
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

    private final boolean isValueDeleted;
    private final long timestamp;
    private final ByteBuffer buffer;

    private Value(
            final boolean isValueDeleted,
            final long timestamp,
            final ByteBuffer buffer
    ) {
        this.isValueDeleted = isValueDeleted;
        this.timestamp = timestamp;
        this.buffer = buffer;
    }

    /**
     * This synchronizes received values.
     * @param values - values list
     * @return - synchronized value
     */
    static Value mergeValues(final Collection<Value> values) {
        return values.stream()
                .filter(value -> !value.isValueMissing())
                .max(Comparator.comparingLong(Value::getTimestamp))
                .orElseGet(Value::resolveMissingValue);
    }

    public static Value resolveExistingValue(final ByteBuffer buffer, final long timestamp) {
        return new Value(false, timestamp, buffer);
    }

    public static Value resolveDeletedValue(final long timestamp) {
        return new Value(true, timestamp, EMPTY_BUFFER);
    }

    public static Value resolveMissingValue() {
        return new Value(false, -1, EMPTY_BUFFER);
    }

    private boolean isValueDeleted() {
        return isValueDeleted;
    }

    boolean isValueMissing() {
        return timestamp == -1;
    }

    long getTimestamp() {
        return timestamp;
    }

    private ByteBuffer getValue() throws IOException {
        if (isValueDeleted) {
            throw new IOException("Record was removed");
        } else {
            return buffer;
        }
    }

    byte[] getBytes() throws IOException {
        final ByteBuffer buf = getValue().duplicate();
        final byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return bytes;
    }

    /**
     * Creates new Value from bytes.
     * @param bytes - from what to create Value
     * @return Value
     */
    public static Value composeFromBytes(final byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        final short isValueDeleted = buffer.getShort();
        final long timestamp = buffer.getLong();

        return new Value(isValueDeleted == 1, timestamp, buffer);
    }

    /**
     * Retrieves value written to byte[] with specific timestamp attribute.
     *
     * @return timestamp-exposing value written to byte array
     */
    public byte[] getValueBytes() {
        final short isDeleted = isValueDeleted ? (short) 1 : (short) -1;

        return ByteBuffer.allocate(Short.BYTES + Long.BYTES + buffer.remaining())
                .putShort(isDeleted)
                .putLong(timestamp)
                .put(buffer.duplicate()).array();
    }

    /**
     * This converts value to one-nio response.
     * @param value - value to convert
     * @return - one-nio response
     */
    static Response toResponse(
            final Value value
    ) {
        // Value is present
        Response response;
        if (!value.isValueMissing() && !value.isValueDeleted()) {
            try {
                response = new Response(Response.OK, value.getBytes());
            } catch (IOException e) {
                return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
            }
            response.addHeader(TIMESTAMP_HEADER + value.getTimestamp());
            return response;
        }

        // Value is deleted
        if (value.isValueDeleted()) {
            response = new Response(Response.NOT_FOUND, Response.EMPTY);
            response.addHeader(TIMESTAMP_HEADER + value.getTimestamp());
            return response;
        }

        // Value is missing
        return new Response(Response.NOT_FOUND, Response.EMPTY);
    }
}
