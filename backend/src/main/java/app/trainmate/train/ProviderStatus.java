package app.trainmate.train;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/** A provider's answer, normalised: the last station the train crossed, how late it was, and when that was observed. */
public record ProviderStatus(String trainNo, LocalDate journeyDate, String lastStationCode, int delayMinutes, Instant observedAt, Map<String, Object> raw) {}
