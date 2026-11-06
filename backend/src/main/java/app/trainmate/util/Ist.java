package app.trainmate.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Indian Standard Time helpers. Every timestamp the API returns is ISO-8601 with an explicit +05:30 offset. */
public final class Ist {
    public static final ZoneOffset OFFSET = ZoneOffset.ofHoursMinutes(5, 30);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private Ist() {}

    /** "2026-10-25T09:06:00+05:30". */
    public static String iso(Instant t) {
        return ISO.format(t.atOffset(OFFSET));
    }

    public static LocalDate date(Instant t) {
        return t.atOffset(OFFSET).toLocalDate();
    }

    public static Instant startOfDay(LocalDate d) {
        return d.atStartOfDay().toInstant(OFFSET);
    }

    /** Journey date plus a day offset, at a wall-clock time in IST. */
    public static Instant at(LocalDate journeyDate, LocalTime time, int dayOffset) {
        return journeyDate.plusDays(dayOffset).atTime(time).toInstant(OFFSET);
    }

    /** For the HTTP Date header, which clients use to learn the server's clock. */
    public static String httpDate(Instant t) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(t.atOffset(ZoneOffset.UTC));
    }
}
