package app.trainmate.train;

/** The provider could not give us a usable answer (timeout, error, no data for this train). */
public class ProviderException extends Exception {
    private static final long serialVersionUID = 1L;

    public ProviderException(String message) { super(message); }

    public ProviderException(String message, Throwable cause) { super(message, cause); }
}
