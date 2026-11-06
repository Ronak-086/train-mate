package app.trainmate.user;

import java.util.Locale;

/** Other travellers see a first name and an initial ("Asha K."), never a full name or an email address. */
public final class DisplayNames {
    private DisplayNames() {}

    public static String from(String fullName, String email) {
        String name = fullName == null ? "" : fullName.trim().replaceAll("\\s+", " ");
        if (!name.isEmpty()) {
            String[] w = name.split(" ");
            String first = w[0];
            if (w.length == 1) return cap(first);
            String initial = w[w.length - 1].substring(0, 1).toUpperCase(Locale.ROOT);
            return cap(first) + " " + initial + ".";
        }
        if (email != null && email.contains("@")) {
            String local = email.substring(0, email.indexOf('@')).replaceAll("[^A-Za-z].*$", "");
            if (!local.isEmpty()) return cap(local);
        }
        return "Traveller";
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
