package org.noureddine.joularjx.monitor;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global per-method instrumentation for (almost) exact
 * invocation counts and wall-clock times (inclusive).
 *
 * Called from bytecode-inserted hooks by the Java agent.
 *
 * CRITICAL: Includes guards against early JVM initialization issues
 * that can occur when instrumentation hooks are called during static
 * class initialization.
 */
public final class MethodInstrumentation {

    // Aggregate stats per method key
    private static final ConcurrentHashMap<String, AtomicLong> INVOCATIONS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> TOTAL_TIME_NANOS =
            new ConcurrentHashMap<>();

    // Per-thread start times (methodKey -> startNano)
    private static final ThreadLocal<Map<String, Long>> START_TIMES =
            ThreadLocal.withInitial(java.util.HashMap::new);

    // Guard against calling during early JVM initialization
    private static volatile boolean initialized = false;

    static {
        initialized = true;  // Marked as initialized after static block
    }

    private MethodInstrumentation() {
        // no instantiation
    }

    /**
     * Called at method entry (injected by transformer).
     *
     * Safe to call during any phase of JVM startup.
     */
    public static void onEnter(String methodKey) {
        if (!initialized) {
            return;  // Skip if JVM not fully initialized
        }

        try {
            long now = System.nanoTime();
            START_TIMES.get().put(methodKey, now);
        } catch (Throwable e) {
            // Silently ignore any errors during measurement
            // This prevents instrumentation from breaking application code
        }
    }

    public static String resolveKey(String internalClassName, String name, String descriptor) {
        String javaClassName = internalClassName.replace('/', '.');

        // Use BCEL for signature, but with param count heuristic
        try {
            Class<?> cls = Class.forName(javaClassName, false, Thread.currentThread().getContextClassLoader());
            java.lang.reflect.Method[] methods = cls.getDeclaredMethods();
            return Arrays.stream(methods)
                    .filter(m -> m.getName().equals(name))
                    .max(Comparator.comparingInt(m -> m.getParameterTypes().length))
                    .map(m -> {
                        StringBuilder sb = new StringBuilder(javaClassName).append('.').append(name).append('(');
                        for (Class<?> p : m.getParameterTypes()) {
                            sb.append(p.getSimpleName()).append(',');
                        }
                        if (m.getParameterCount() > 0) sb.setLength(sb.length()-1);
                        return sb.append(')').toString();
                    })
                    .orElse(javaClassName + "." + name + "()");
        } catch (Throwable t) {  // catch *everything*, including Errors
            return javaClassName + "." + name + "()";
        }
    }

    /**
     * Called at every normal/exceptional exit (injected by transformer).
     *
     * Safe to call during any phase of JVM startup.
     */
    public static void onExit(String methodKey) {
        if (!initialized) {
            return;  // Skip if JVM not fully initialized
        }

        try {
            long now = System.nanoTime();
            Map<String, Long> map = START_TIMES.get();
            Long start = map.remove(methodKey);

            if (start == null) {
                return; // unmatched enter/exit (should be rare); ignore
            }

            long duration = now - start;

            INVOCATIONS.computeIfAbsent(methodKey, k -> new AtomicLong()).incrementAndGet();
            TOTAL_TIME_NANOS.computeIfAbsent(methodKey, k -> new AtomicLong()).addAndGet(duration);
        } catch (Throwable e) {
            // Silently ignore any errors during measurement
            // This prevents instrumentation from breaking application code
        }
    }

    // ---- Accessors for ShutdownHandler or debugging ----

    public static long getInvocations(String methodKey) {
        AtomicLong c = INVOCATIONS.get(methodKey);
        return c == null ? 0L : c.get();
    }

    public static long getTotalTimeNanos(String methodKey) {
        AtomicLong t = TOTAL_TIME_NANOS.get(methodKey);
        return t == null ? 0L : t.get();
    }

    public static Map<String, AtomicLong> getAllInvocations() {
        return INVOCATIONS;
    }

    public static Map<String, AtomicLong> getAllTotalTimeNanos() {
        return TOTAL_TIME_NANOS;
    }

    /**
     * Build a readable method key from ASM-style class/method/descriptor.
     * Example: padl.creator.MyClass.myMethod(int,java.lang.String)
     */
    public static String buildKey(String internalClassName, String name, String descriptor) {
        String className = internalClassName.replace('/', '.');
        org.objectweb.asm.Type[] argTypes = org.objectweb.asm.Type.getArgumentTypes(descriptor);
        String[] paramTypeNames = new String[argTypes.length];
        for (int i = 0; i < argTypes.length; i++) {
            paramTypeNames[i] = toJavaType(argTypes[i]);
        }

        return MethodKeyUtil.canonicalKey(className, name, paramTypeNames);
    }

    private static String toJavaType(org.objectweb.asm.Type t) {
        switch (t.getSort()) {
            case org.objectweb.asm.Type.VOID: return "void";
            case org.objectweb.asm.Type.BOOLEAN: return "boolean";
            case org.objectweb.asm.Type.CHAR: return "char";
            case org.objectweb.asm.Type.BYTE: return "byte";
            case org.objectweb.asm.Type.SHORT: return "short";
            case org.objectweb.asm.Type.INT: return "int";
            case org.objectweb.asm.Type.FLOAT: return "float";
            case org.objectweb.asm.Type.LONG: return "long";
            case org.objectweb.asm.Type.DOUBLE: return "double";
            case org.objectweb.asm.Type.ARRAY:
                StringBuilder sb = new StringBuilder(toJavaType(t.getElementType()));
                for (int i = 0; i < t.getDimensions(); i++) {
                    sb.append("[]");
                }
                return sb.toString();
            case org.objectweb.asm.Type.OBJECT:
                return t.getClassName();
            default:
                return t.getDescriptor();
        }
    }
}