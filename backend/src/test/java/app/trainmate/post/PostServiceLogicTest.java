package app.trainmate.post;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PostServiceLogicTest {

    @Test
    void coachAdjacencyRules() {
        // Same coach
        assertTrue(PostService.coachesAdjacent("B3", "B3"));

        // Adjacent in same class
        assertTrue(PostService.coachesAdjacent("B3", "B4"));
        assertTrue(PostService.coachesAdjacent("B3", "B2"));
        assertTrue(PostService.coachesAdjacent("S1", "S2"));

        // Not adjacent (distance > 1)
        assertFalse(PostService.coachesAdjacent("B3", "B5"));
        assertFalse(PostService.coachesAdjacent("B3", "B1"));

        // Different class
        assertFalse(PostService.coachesAdjacent("B3", "S3"));
        assertFalse(PostService.coachesAdjacent("A1", "B1"));

        // Malformed / null
        assertFalse(PostService.coachesAdjacent(null, "B1"));
        assertFalse(PostService.coachesAdjacent("invalid", "B1"));
    }

    @Test
    void allowedScopesPerKind() {
        assertTrue(PostService.ALLOWED_SCOPES.get("SWAP").contains("COACH"));
        assertTrue(PostService.ALLOWED_SCOPES.get("SWAP").contains("NEARBY"));
        assertFalse(PostService.ALLOWED_SCOPES.get("SWAP").contains("TRAIN"));

        assertTrue(PostService.ALLOWED_SCOPES.get("ALERT").contains("COACH"));
        assertTrue(PostService.ALLOWED_SCOPES.get("ALERT").contains("TRAIN"));
        assertFalse(PostService.ALLOWED_SCOPES.get("ALERT").contains("NEARBY"));

        assertTrue(PostService.ALLOWED_SCOPES.get("SHARE").contains("COACH"));
        assertFalse(PostService.ALLOWED_SCOPES.get("SHARE").contains("TRAIN"));
    }

    @Test
    void cursorRoundtrip() {
        long now = 1793812345000L;
        String id = "c5b2ef62-1111-4567-89ab-cdef01234567";

        String encoded = PostService.encodeCursor(now, id);
        assertNotNull(encoded);

        // Decode
        var decoded = PostService.decodeCursor(encoded);
        assertNotNull(decoded);
        assertEquals(now, decoded.t());
        assertEquals(id, decoded.id());

        // Invalid cursor returns null
        assertNull(PostService.decodeCursor(null));
        assertNull(PostService.decodeCursor(""));
        assertNull(PostService.decodeCursor("not-base64!"));
    }
}
