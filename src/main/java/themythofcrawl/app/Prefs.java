package themythofcrawl.app;

/** Small string store: Burp's global preferences, or the Burp project file. */
public interface Prefs {
    String get(String key);

    void set(String key, String value);

    /** In-memory store (tests, or when Burp has no project file). */
    static Prefs memory() {
        java.util.Map<String, String> m = new java.util.concurrent.ConcurrentHashMap<>();
        return new Prefs() {
            @Override
            public String get(String key) {
                return m.get(key);
            }

            @Override
            public void set(String key, String value) {
                if (value == null) {
                    m.remove(key);
                } else {
                    m.put(key, value);
                }
            }
        };
    }
}
