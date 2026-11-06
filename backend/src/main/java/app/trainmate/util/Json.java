package app.trainmate.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One shared Jackson mapper. DTOs are records whose timestamps are already strings, so no date modules are needed. */
public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {}

    public static byte[] write(Object o) {
        try {
            return MAPPER.writeValueAsBytes(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise " + o.getClass().getSimpleName(), e);
        }
    }

    public static String writeString(Object o) {
        return new String(write(o), java.nio.charset.StandardCharsets.UTF_8);
    }

    public static JsonNode tree(byte[] bytes) throws java.io.IOException {
        return MAPPER.readTree(bytes);
    }

    public static JsonNode tree(String s) throws JsonProcessingException {
        return MAPPER.readTree(s);
    }

    public static <T> T read(String s, Class<T> type) throws JsonProcessingException {
        return MAPPER.readValue(s, type);
    }
}
