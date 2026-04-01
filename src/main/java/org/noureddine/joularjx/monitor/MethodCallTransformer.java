package org.noureddine.joularjx.monitor;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.LineNumber;
import org.apache.bcel.classfile.LineNumberTable;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.Type;
import org.apache.bcel.util.SyntheticRepository;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;



/**
 * Instruments application classes at load time to call
 * MethodInstrumentation.onEnter / onExit for every non-abstract method.
 */
public final class MethodCallTransformer implements ClassFileTransformer {

    @Override
    public byte[] transform(
            Module module,
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
    )  {

        if (loader == null || className == null) {
            return null;
        }

//        // Skip JDK and JoularJX agent classes to avoid recursion and overhead
        if (
                className.startsWith("jdk/")) {
            return null;
        }
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(
                        int access,
                        String name,
                        String descriptor,
                        String signature,
                        String[] exceptions
                ) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                    // Do not instrument abstract or native methods
                    if ((access & Opcodes.ACC_ABSTRACT) != 0 || (access & Opcodes.ACC_NATIVE) != 0) {
                        return mv;
                    }

                    // Skip class/static/instance initializers
                    if (name.equals("<clinit>") || name.equals("<init>")) {
                        return mv;
                    }

                    final String methodKey =
//                            MethodInstrumentation.resolveKey(className, name, descriptor);
                            MethodInstrumentation.buildKey(className, name, descriptor);


                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            // Insert MethodInstrumentation.onEnter(methodKey)
                            mv.visitLdcInsn(methodKey);
                            mv.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    "org/noureddine/joularjx/monitor/MethodInstrumentation",
                                    "onEnter",
                                    "(Ljava/lang/String;)V",
                                    false
                            );
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            // On every return or throw, call onExit(methodKey)
                            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN
                                    || opcode == Opcodes.ATHROW) {
                                mv.visitLdcInsn(methodKey);
                                mv.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        "org/noureddine/joularjx/monitor/MethodInstrumentation",
                                        "onExit",
                                        "(Ljava/lang/String;)V",
                                        false
                                );
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            };

            cr.accept(cv, 0);
            return cw.toByteArray();
        } catch (Throwable t) {
            // In case of any problem, do not block class loading
            t.printStackTrace();
            return null;
        }
    }
    public static String resolve(Class<?> clazz, String methodName, int lineNumber) {
        JavaClass javaClass;
        try {
            // Load class using BCEL repository
            SyntheticRepository repo = SyntheticRepository.getInstance();
            javaClass = repo.loadClass(clazz.getName());
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            return null;
        }

        String fullSignature = null;
        String className = javaClass.getClassName();

        for (Method method : javaClass.getMethods()) {
            LineNumberTable lineNumberTable = method.getLineNumberTable();
            if (lineNumberTable != null) {
                LineNumber[] lineNumbers = lineNumberTable.getLineNumberTable();
                if (lineNumbers.length > 0) {
                    int startLine = lineNumbers[0].getLineNumber();
                    int endLine = lineNumbers[lineNumbers.length - 1].getLineNumber();

                    if (method.getName().equals(methodName) &&
                            lineNumber >= startLine && lineNumber <= endLine) {

                        StringBuilder params = new StringBuilder();
                        for (Type paramType : method.getArgumentTypes()) {
                            params.append(paramType.toString()).append(", ");
                        }
                        if (params.length() > 0) {
                            params.setLength(params.length() - 2); // Remove trailing comma
                        }

                        fullSignature = className + "." + methodName + "(" + params + ")";
                        break; // Found the method; exit the loop
                    }
                }
            }
        }

        return fullSignature;
    }
}