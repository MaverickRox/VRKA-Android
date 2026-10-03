package android.text;

/**
 * Shadow implementation of android.text.TextUtils for local JVM unit testing.
 * Prevents mockable jar from returning false for isEmpty(null).
 */
public class TextUtils {
    public static boolean isEmpty(CharSequence str) {
        return str == null || str.length() == 0;
    }

    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (a != null && b != null && a.length() == b.length()) {
            return a.toString().equals(b.toString());
        }
        return false;
    }
}
