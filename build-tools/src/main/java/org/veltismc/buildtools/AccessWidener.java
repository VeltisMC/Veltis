package org.veltismc.buildtools;

import org.veltismc.patchengine.VeltisConsole;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

public class AccessWidener {

    private static final Logger LOG = LogManager.getLogger(AccessWidener.class);

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            LOG.error("Usage: AccessWidener <input.jar> <output.jar>");
            System.exit(1);
        }
        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        var start = System.nanoTime();
        LOG.info("Widening Minecraft server access");
        widenAccess(input, output);
        LOG.info("Minecraft server access widened ({})",
            VeltisConsole.formatDuration(System.nanoTime() - start));
        LOG.debug("Access-widened jar: {}", output);
    }

    public static void widenAccess(Path input, Path output) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarInputStream jis = new JarInputStream(new FileInputStream(input.toFile()))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] data = jis.readAllBytes();
                if (name.endsWith(".class") && !name.contains("module-info") && !name.contains("package-info")) {
                    try {
                        data = widenClass(data);
                    } catch (Exception e) {
                        LOG.warn("Could not process {}: {}", name, e.getMessage());
                    }
                }
                entries.put(name, data);
            }
        }
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(output.toFile()))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue());
                jos.closeEntry();
            }
        }
    }

    private static byte[] widenClass(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            private boolean isInterface;
            private boolean isAnnotation;

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                this.isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
                this.isAnnotation = (access & Opcodes.ACC_ANNOTATION) != 0;
                super.visit(version, widenClassAccess(access), name, signature, superName, interfaces);
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                super.visitInnerClass(name, outerName, innerName, widenAll(access, false));
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                return super.visitField(widenAll(access, this.isInterface || this.isAnnotation), name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return super.visitMethod(widenAll(access, false), name, descriptor, signature, exceptions);
            }
        }, 0);
        return cw.toByteArray();
    }

    private static int widenClassAccess(int access) {
        // Remove ACC_FINAL from classes, but not from enums
        if ((access & Opcodes.ACC_ENUM) == 0 && (access & Opcodes.ACC_ANNOTATION) == 0) {
            access = access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL);
        } else {
            access = access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
        }
        if ((access & Opcodes.ACC_PUBLIC) == 0) {
            access |= Opcodes.ACC_PUBLIC;
        }
        return access;
    }

    private static int widenAll(int access, boolean keepFinal) {
        access = access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
        if ((access & Opcodes.ACC_PUBLIC) == 0) {
            access |= Opcodes.ACC_PUBLIC;
        }
        if (!keepFinal && (access & Opcodes.ACC_ENUM) == 0) {
            access = access & ~Opcodes.ACC_FINAL;
        }
        return access;
    }
}
