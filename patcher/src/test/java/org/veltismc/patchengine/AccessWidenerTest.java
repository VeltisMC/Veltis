package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The access widener runs before the decompile, so a member it makes illegal makes
 * the decompiled source uncompilable. Two members cannot be widened at all, and
 * both cost a full build cycle to discover:
 *
 * <ul>
 *   <li>an enum constructor is implicitly private, and the JVM rejects the class
 *       outright if it is not -- Vineflower then decompiles {@code public} verbatim
 *       and javac fails with "modifier public not allowed here";</li>
 *   <li>{@code <clinit>} must not be public, protected or private (JVMS 5.5), so
 *       widening it writes a class file no strict verifier accepts.</li>
 * </ul>
 *
 * <p>Everything else is fair game, and that is the point: the decompile cannot be
 * compiled over the un-widened jar, because its sources carry the original
 * {@code protected}/{@code final} modifiers.
 */
class AccessWidenerTest {

    /** An enum with a private constructor, a private field, and a class initializer. */
    private static byte[] enumClass() {
        var cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER
                | Opcodes.ACC_ENUM,
            "sample/Sample", null, "java/lang/Enum", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "id", "I", null, null).visitEnd();
        var init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "(I)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ILOAD, 1);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>",
            "(Ljava/lang/String;I)V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(3, 2);
        init.visitEnd();
        var clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static java.util.Map<String, Integer> widenAndReadFlags() {
        var widened = AccessWidener.widenClass(enumClass());
        var found = new java.util.LinkedHashMap<String, Integer>();
        new ClassReader(widened).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                found.put(name, access);
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return found;
    }

    @Test
    void anEnumConstructorIsLeftPrivate() {
        var access = widenAndReadFlags().get("<init>");
        assertTrue((access & Opcodes.ACC_PRIVATE) != 0,
            "an enum constructor must stay private, or the class will not load and"
                + " the decompiled 'public' will not compile");
        assertTrue((access & Opcodes.ACC_PUBLIC) == 0,
            "an enum constructor must not be public");
    }

    @Test
    void theStaticInitializerIsNeverMadePublic() {
        var access = widenAndReadFlags().get("<clinit>");
        assertTrue((access & Opcodes.ACC_PUBLIC) == 0, "JVMS 5.5: <clinit> must not be public");
        assertTrue((access & Opcodes.ACC_PROTECTED) == 0, "<clinit> must not be protected");
        assertTrue((access & Opcodes.ACC_PRIVATE) == 0, "<clinit> must not be private");
        assertTrue((access & Opcodes.ACC_STATIC) != 0, "<clinit> must stay static");
    }

    @Test
    void everythingElseIsStillWidened() {
        // The whole reason the widened jar exists: the decompiled sources carry the
        // original private members, which cannot be compiled over the original
        // classes. If widening stopped working the build would fail with
        // "attempting to assign weaker access privileges" instead.
        var fields = new java.util.LinkedHashMap<String, Integer>();
        new ClassReader(AccessWidener.widenClass(enumClass()))
            .accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.FieldVisitor visitField(int access, String name,
                        String descriptor, String signature, Object value) {
                    fields.put(name, access);
                    return null;
                }
            }, ClassReader.SKIP_CODE);
        var id = fields.get("id");
        assertTrue((id & Opcodes.ACC_PUBLIC) != 0, "a private field must be widened");
    }

    // ------------------------------------------------------------------
    // Signatures
    // ------------------------------------------------------------------

    /**
     * A jar that looks like a signed one: real manifest with a per-entry digest
     * section, a signature file, and a signature block.
     *
     * <p>The signature bytes are not a real signature — forging one needs a
     * certificate, and a unit test is the wrong place for that. What the test
     * needs is the shape, and the shape is what the JDK looks at: keep a
     * {@code *.SF} and a {@code *.RSA} in place and every class read goes through
     * {@code JarVerifier}.
     */
    private static void signedLookingJar(Path jar, String classEntry, byte[] classBytes)
        throws java.io.IOException {
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        manifest.getMainAttributes().putValue("Created-By", "Mojang");
        var section = new java.util.jar.Attributes();
        section.put(new java.util.jar.Attributes.Name("Name"), classEntry);
        section.putValue("SHA-384-Digest", "b3BlbnNlc2FtZQ==");
        manifest.getEntries().put(classEntry, section);

        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("META-INF/MOJANGCS.SF"));
            out.write("Signature-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/MOJANGCS.RSA"));
            out.write(new byte[] {0x30, (byte) 0x82});
            out.closeEntry();
            out.putNextEntry(new JarEntry(classEntry));
            out.write(classBytes);
            out.closeEntry();
        }
    }

    /**
     * The signature entries still present in a jar.
     *
     * <p>{@code JarFile} has no public "is this signed" accessor, and this is the
     * condition it uses internally: a signature block ({@code *.RSA}, {@code
     * *.DSA}, {@code *.EC}) plus a signature file ({@code *.SF}). While one is
     * present, every class read is routed through {@code JarVerifier}.
     */
    private static List<String> signatureEntries(JarFile jar) {
        return jar.stream()
            .map(JarEntry::getName)
            .filter(AccessWidenerTest::isSignatureName)
            .sorted()
            .toList();
    }

    private static boolean isSignatureName(String name) {
        var upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("META-INF/SIG-")
            || upper.endsWith(".SF") || upper.endsWith(".RSA")
            || upper.endsWith(".DSA") || upper.endsWith(".EC");
    }

    @Test
    void aRewrittenJarIsUnsignedAndStillLoadsItsClasses(@TempDir Path tmp) throws Exception {
        // Mojang publishes a signed server jar. Widening changes class bytes, which
        // invalidates the digests that cover them, so the signature cannot be kept:
        // the JDK verifies a signed jar on the way to every class and throws
        // "SecurityException: SHA-384 digest error for net/minecraft/commands/
        // CommandSource.class" on the first one it is asked for. The server would
        // then die on a Minecraft class, long after the widening step, naming
        // neither it nor the reason.
        var input = tmp.resolve("server.jar");
        var output = tmp.resolve("server-widened.jar");
        var classEntry = "sample/Sample.class";
        signedLookingJar(input, classEntry, enumClass());

        try (var jar = new JarFile(input.toFile())) {
            assertEquals(List.of("META-INF/MOJANGCS.RSA", "META-INF/MOJANGCS.SF"),
                signatureEntries(jar), "the fixture must actually look signed, or this"
                    + " test proves nothing about the case it exists for");
        }

        AccessWidener.widenAlways(input, output);

        var names = new java.util.ArrayList<String>();
        try (var jar = new JarFile(output.toFile())) {
            jar.stream().forEach(e -> names.add(e.getName()));
            assertEquals(List.of(), signatureEntries(jar),
                "the output must not carry Mojang's signature: its class bytes no longer"
                    + " match it, and a signed jar is verified on every class read");
            var main = jar.getManifest().getMainAttributes();
            assertEquals("true", main.getValue("Multi-Release"),
                "the main section describes the jar rather than its contents, so it is"
                    + " kept: dropping Multi-Release would silently disable the versioned"
                    + " entries inside it");
            assertEquals("Mojang", main.getValue("Created-By"), main.toString());
            assertEquals(0, jar.getManifest().getEntries().size(),
                "the per-entry digest sections are the stale part and must be dropped:"
                    + " they are most of Mojang's 2.7 MB manifest");
        }
        assertTrue(names.contains(classEntry), names.toString());

        // The end the signature was breaking. Reading a class out of the widened
        // jar is exactly what the server classloader does before Minecraft starts.
        try (var loader = new URLClassLoader(new URL[] {output.toUri().toURL()},
            ClassLoader.getPlatformClassLoader())) {
            var loaded = Class.forName("sample.Sample", false, loader);
            assertEquals(output.toUri().toURL(),
                loaded.getProtectionDomain().getCodeSource().getLocation(),
                "the class must come from the widened jar, not from this test's own"
                    + " classpath");
        }
    }

    @Test
    void wideningTheSameJarTwiceProducesTheSameBytes(@TempDir Path tmp) throws Exception {
        // Observed failure: the widened jar's SHA-1 is recorded in the decompile
        // marker, and `new ZipEntry(name)` leaves the timestamp unset so
        // ZipOutputStream fills it in with the current time. Two runs over one
        // input therefore produced two different jars, the marker missed, and the
        // next start paid for a full decompile of 5037 files to arrive at exactly
        // the same source. Nothing was wrong with the result -- it just refused to
        // be recognised as the one that was already built.
        var input = tmp.resolve("server.jar");
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(input))) {
            for (var i = 0; i < 5; i++) {
                out.putNextEntry(new JarEntry("net/minecraft/C" + i + ".class"));
                out.write(enumClass());
                out.closeEntry();
            }
        }

        AccessWidener.widenAlways(input, tmp.resolve("first.jar"));
        // Long enough that a two-second-resolution timestamp would differ.
        Thread.sleep(2_100);
        AccessWidener.widenAlways(input, tmp.resolve("second.jar"));

        assertArrayEquals(
            Files.readAllBytes(tmp.resolve("first.jar")),
            Files.readAllBytes(tmp.resolve("second.jar")),
            "the same input must widen to the same bytes, or the widen and decompile"
                + " markers are invalidated by the clock rather than by a real change");
    }

    @Test
    void aJarWithNoSignatureKeepsItsManifestAndLosesNothing(@TempDir Path tmp)
        throws Exception {
        // The stripping must not become a filter that quietly eats ordinary files.
        // META-INF/ in general is not signature material: Mojang's own LICENSE lives
        // there, and so does every service file in an ordinary library.
        var input = tmp.resolve("plain.jar");
        var output = tmp.resolve("plain-widened.jar");
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("META-INF/LICENSE"));
            out.write("Mojang".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/services/sample.Sample"));
            out.write("sample.Sample\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("sample/Sample.class"));
            out.write(enumClass());
            out.closeEntry();
        }

        AccessWidener.widenAlways(input, output);

        var names = new java.util.ArrayList<String>();
        try (var jar = new JarFile(output.toFile())) {
            jar.stream().forEach(e -> names.add(e.getName()));
            assertEquals(List.of(), signatureEntries(jar),
                "an unsigned input cannot produce a signed output");
        }
        assertEquals(List.of("META-INF/LICENSE", "META-INF/services/sample.Sample",
            "sample/Sample.class"), names.stream().sorted().toList(), names.toString());
    }
}
