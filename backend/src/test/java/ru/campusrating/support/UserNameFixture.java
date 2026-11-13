package ru.campusrating.support;

public final class UserNameFixture {
    private UserNameFixture() {}

    public static String last(String name) { return parts(name)[0]; }
    public static String first(String name) {
        var parts = parts(name);
        return parts.length > 1 ? parts[1] : parts[0];
    }
    public static String middle(String name) {
        var parts = parts(name);
        return parts.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(parts, 2, parts.length)) : null;
    }
    public static String middleOrEmpty(String name) {
        var value = middle(name);
        return value == null ? "" : value;
    }
    private static String[] parts(String name) { return name.strip().split("\\s+"); }
}
