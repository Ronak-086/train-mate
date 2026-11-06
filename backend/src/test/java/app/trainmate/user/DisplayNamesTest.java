package app.trainmate.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DisplayNamesTest {
    @Test void firstNameAndInitial() {
        assertEquals("Asha K.", DisplayNames.from("Asha Kumar", "asha@x.com"));
        assertEquals("Asha K.", DisplayNames.from("  asha   kumar  ", null));
    }
    @Test void usesTheLastWordForTheInitial() { assertEquals("Mary S.", DisplayNames.from("Mary Jane Smith", null)); }
    @Test void singleNameStaysAsIs() { assertEquals("Priya", DisplayNames.from("priya", null)); }
    @Test void neverExposesAnEmailAddress() {
        assertEquals("Asha", DisplayNames.from(null, "asha.kumar@example.com"));
        assertEquals("Rahul", DisplayNames.from("", "rahul_91@example.com"));
        assertEquals("Traveller", DisplayNames.from(null, "12345@example.com"));
    }
    @Test void fallsBackToTraveller() { assertEquals("Traveller", DisplayNames.from(null, null)); }
    @Test void keepsNonLatinNames() { assertEquals("अनु शर्मा".charAt(0) + "", DisplayNames.from("अनु शर्मा", null).substring(0, 1)); }
}
