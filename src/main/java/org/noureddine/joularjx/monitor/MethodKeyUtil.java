package org.noureddine.joularjx.monitor;

public final class MethodKeyUtil {

    public static String canonicalKey(
            String javaClassName, String methodName, String[] paramTypeNames) {
        StringBuilder sb = new StringBuilder();
        sb.append(javaClassName).append('.').append(methodName).append('(');
        for (int i = 0; i < paramTypeNames.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(paramTypeNames[i]);
        }
        sb.append(')');
        return sb.toString();
    }
}