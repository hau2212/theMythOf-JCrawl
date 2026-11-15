package themythofcrawl.burp;

import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Preferences;
import themythofcrawl.app.Prefs;

/** Burp's global preferences, or the data saved in the Burp project file. */
final class MontoyaPrefs {
    private MontoyaPrefs() {
    }

    static Prefs of(Preferences p) {
        return new Prefs() {
            @Override
            public String get(String key) {
                return p.getString(key);
            }

            @Override
            public void set(String key, String value) {
                if (value == null) {
                    p.deleteString(key);
                } else {
                    p.setString(key, value);
                }
            }
        };
    }

    static Prefs of(PersistedObject o) {
        return new Prefs() {
            @Override
            public String get(String key) {
                return o.getString(key);
            }

            @Override
            public void set(String key, String value) {
                if (value == null) {
                    o.deleteString(key);
                } else {
                    o.setString(key, value);
                }
            }
        };
    }
}
