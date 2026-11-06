package app.trainmate.train;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RapidApiProviderAdapterTest {

    @Test
    void missingKeyThrowsProviderException() {
        RapidApiProviderAdapter adapter = new RapidApiProviderAdapter("");
        assertEquals("rapidapi", adapter.name());
        assertThrows(ProviderException.class, () ->
            adapter.fetch("12345", LocalDate.of(2026, 10, 25))
        );
    }

    @Test
    void nullKeyThrowsProviderException() {
        RapidApiProviderAdapter adapter = new RapidApiProviderAdapter(null);
        assertThrows(ProviderException.class, () ->
            adapter.fetch("12345", LocalDate.of(2026, 10, 25))
        );
    }
}
