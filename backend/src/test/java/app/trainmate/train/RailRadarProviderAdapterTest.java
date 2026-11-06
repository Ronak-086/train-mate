package app.trainmate.train;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RailRadarProviderAdapterTest {

    @Test
    void missingKeyThrowsProviderException() {
        RailRadarProviderAdapter adapter = new RailRadarProviderAdapter("");
        assertEquals("railradar", adapter.name());
        assertThrows(ProviderException.class, () ->
            adapter.fetch("12919", LocalDate.of(2026, 11, 4))
        );
    }

    @Test
    void nullKeyThrowsProviderException() {
        RailRadarProviderAdapter adapter = new RailRadarProviderAdapter(null);
        assertThrows(ProviderException.class, () ->
            adapter.fetch("12919", LocalDate.of(2026, 11, 4))
        );
    }
}
