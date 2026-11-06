package app.trainmate.train;

import java.time.LocalDate;

/** Tries the primary provider, and the secondary only if the primary fails (spec 8.3). */
public final class FallbackProviderAdapter implements ProviderAdapter {
    private final ProviderAdapter primary;
    private final ProviderAdapter secondary;

    public FallbackProviderAdapter(ProviderAdapter primary, ProviderAdapter secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override public String name() { return primary.name() + "+" + secondary.name(); }

    @Override
    public ProviderStatus fetch(String trainNo, LocalDate date) throws ProviderException {
        try {
            return primary.fetch(trainNo, date);
        } catch (ProviderException first) {
            try {
                return secondary.fetch(trainNo, date);
            } catch (ProviderException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }
}
