package app.trainmate.train;

import java.time.LocalDate;

/**
 * One source of live train data (spec 8.3). Implementations hide a provider's quirks and return the same small shape.
 * {@link #fetch} may throw {@link ProviderException} for anything that goes wrong; the service treats that as "no new data".
 */
public interface ProviderAdapter {
    /** Short stable name, used for the budget counter and as the snapshot's source. */
    String name();

    ProviderStatus fetch(String trainNo, LocalDate journeyDate) throws ProviderException;
}
