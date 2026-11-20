package dukes.agent;


/** Settings from outside the code: a system property (-D) or an environment variable, then the default. */
final class Settings {

    private Settings() {
    }


    static String get(String name, String fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
