package org.veltismc.patchengine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Widens class, field and method access in the verified Minecraft classes jar.
 *
 * <p>This runs before the decompile, and that ordering is the whole point: the
 * decompiled source is compiled against the widened jar, so a method Mojang
 * declared {@code protected} is {@code public} in both. Decompiling the
 * un-widened jar instead produces sources that cannot be compiled over the
 * widened classes at all — "attempting to assign weaker access privileges" on
 * every override the patch set touches.
 *
 * <p>It lives in {@code patch-engine} rather than in {@code build-tools} so both
 * callers run one implementation: the Gradle pipeline and a checkout's own
 * {@code VeltisRuntime.prepare()}. Neither is a server start. A server
 * installation never widens anything — it downloads Mojang's jar and applies
 * the bytecode patch set inside {@code server.jar}, and the widening a patch
 * needs was decided and shipped when the patch set was generated (see
 * {@link BytecodePatchGenerator}). That split is deliberate: widening about
 * seven thousand classes takes seconds, and it is work a contributor's machine
 * can afford and an operator's cannot.
 *
 * <p>Entry order is preserved and no class is dropped, so the output is a
 * drop-in replacement for the input. A class the rewriter cannot handle is
 * copied through unchanged and reported, rather than failing the whole run over
 * one unparseable class.
 *
 * <p>Mojang's jar is signed, and the signature cannot be carried over: rewriting
 * a class invalidates the digest that covers it. The signature entries are
 * therefore dropped, which leaves the output an ordinary unsigned jar. Keeping
 * them would be worse than useless — the JDK verifies a signed jar on the way to
 * every class it hands out and throws on the first one, so the server would fail
 * on a Minecraft type and name neither this step nor the reason. The same rule
 * travels with the bytecode patch set as data rather than as a rule, through
 * {@link JarSignatures#isSignatureEntry(String)}, so the applier removes them
 * from a baseline that was never widened in the first place.
 */
public final class AccessWidener {

    /**
     * Bumped when the rewriting rules change, so an already-widened jar in a
     * cache is not silently reused under the new rules. Recorded in the widen
     * marker together with the input's SHA-1; both must match for the cached jar
     * to be accepted.
     */
    public static final int FORMAT = 3;

    private static final Logger LOG = LogManager.getLogger(AccessWidener.class);

    private AccessWidener() {
    }

    /**
     * Widens {@code input} into {@code output} if the cached output does not
     * already match this input and this rewriter.
     *
     * @return whether anything was written
     */
    public static boolean widen(Path input, Path output, String inputSha1) {
        if (Files.isRegularFile(output) && markerMatches(output, inputSha1)) {
            LOG.debug("[VeltisMC] Reusing the access-widened jar at {}", output);
            return false;
        }
        try {
            widenAlways(input, output);
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisMC] Failed to widen Minecraft server access"
                    + "\n  Input: " + input
                    + "\n  Output: " + output
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
        writeMarker(output, inputSha1);
        return true;
    }

    /** Widens unconditionally, replacing any previous output. */
    public static void widenAlways(Path input, Path output) throws IOException {
        var parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        var entries = new LinkedHashMap<String, byte[]>();
        var widened = 0;
        var unsigned = 0;
        byte[] manifest = null;
        try (var zip = new ZipFile(input.toFile())) {
            // Sorted by name, and with the timestamps fixed below, so the output is
            // byte-for-byte reproducible from the input. That is what lets the widen
            // marker, the decompile marker and a build cache agree across runs
            // instead of forcing a 60 MB download and a full decompile to be redone
            // because a zip was written in a different second.
            // ZipFile, not JarFile: the entries are treated as opaque name/bytes
            // pairs, and the manifest is parsed explicitly below.
            var ordered = zip.stream()
                .sorted(java.util.Comparator.comparing(ZipEntry::getName))
                .toList();
            for (var source : ordered) {
                if (source.isDirectory()) {
                    continue;
                }
                byte[] data;
                try (InputStream in = zip.getInputStream(source)) {
                    data = in.readAllBytes();
                }
                if (JarSignatures.isSignatureEntry(source.getName())) {
                    unsigned++;
                    continue;
                }
                if (MANIFEST.equals(source.getName())) {
                    manifest = stripManifestDigests(data);
                    continue;
                }
                if (isClass(source.getName())) {
                    try {
                        data = widenClass(data);
                        widened++;
                    } catch (Exception e) {
                        // One unreadable class must not cost the whole jar: the
                        // class is copied through with its original access, which
                        // is what the build already did before the engine owned
                        // this step.
                        LOG.warn("[VeltisMC] Could not widen {}: {}", source.getName(),
                            MojangMetadata.rootMessage(e));
                    }
                }
                entries.put(source.getName(), data);
            }
        }
        // Written to a fixed sibling and moved into place, so an interrupted
        // widening never leaves a half-rewritten jar that a later run would
        // accept. The name is derived from the output, not randomised, so there
        // is exactly one staging path per workspace.
        var staging = output.resolveSibling(output.getFileName() + ".widening");
        try {
            try (var out = new ZipOutputStream(Files.newOutputStream(staging))) {
                if (manifest != null) {
                    out.putNextEntry(entry(MANIFEST));
                    out.write(manifest);
                    out.closeEntry();
                }
                for (var e : entries.entrySet()) {
                    out.putNextEntry(entry(e.getKey()));
                    out.write(e.getValue());
                    out.closeEntry();
                }
            }
            try {
                Files.move(staging, output, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(staging, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staging);
        }
        LOG.info("[VeltisMC] Widened access in {} classes", widened);
        if (unsigned > 0) {
            // Worth saying out loud: the output is deliberately not the jar Mojang
            // published, and that is why the signature cannot survive it.
            LOG.info("[VeltisMC] Removed {} entries of Mojang's signature; the widened jar is"
                + " unsigned because its contents no longer match it", unsigned);
        }
    }

    // ------------------------------------------------------------------
    // Reproducibility
    // ------------------------------------------------------------------

    /**
     * An output entry with a fixed timestamp.
     *
     * <p>{@code new ZipEntry(name)} leaves the timestamp unset, and
     * {@code ZipOutputStream} then fills it in with the current time -- so two runs
     * over the same input produced two different jars. That is not cosmetic here:
     * the widened jar's SHA-1 is recorded in the decompile marker, so an unstable
     * output invalidates the marker and the next start pays for a full decompile
     * of 5037 files to produce the same source. A second is enough to trigger it.
     *
     * <p>Time zero is 1970, before the zip epoch, so the JDK clamps it to
     * 1980-01-01 -- a fixed value, which is the point. Nothing in the pipeline
     * reads these timestamps: the decompiler and the classloader read bytes, and
     * the jar's identity is its content hash.
     */
    private static ZipEntry entry(String name) {
        var entry = new ZipEntry(name);
        entry.setTime(0L);
        return entry;
    }

    // ------------------------------------------------------------------
    // Signatures
    // ------------------------------------------------------------------

    private static final String MANIFEST = "META-INF/MANIFEST.MF";

    // Signature material is identified by JarSignatures rather than here: the
    // runtime's guard asks the same question and it cannot link this class, see
    // JarSignatures.

    /**
     * Rewrites a manifest down to its main section, dropping every per-entry
     * digest section.
     *
     * <p>The main section is kept in full: it holds the attributes that describe
     * the jar rather than its contents — {@code Multi-Release}, {@code
     * Main-Class}, {@code Class-Path} — and losing one of those would change how
     * the jar behaves. The per-entry sections are the stale digests, and they are
     * most of the file: Mojang's server manifest is 2.7 MB of them.
     *
     * @return the rewritten manifest, or {@code null} if it cannot be parsed, in
     *         which case the jar is written without one
     */
    private static byte[] stripManifestDigests(byte[] original) {
        try {
            var parsed = new java.util.jar.Manifest(new java.io.ByteArrayInputStream(original));
            var mainSection = new java.util.jar.Manifest();
            mainSection.getMainAttributes().putAll(parsed.getMainAttributes());
            var buffer = new java.io.ByteArrayOutputStream();
            mainSection.write(buffer);
            return buffer.toByteArray();
        } catch (java.io.IOException e) {
            LOG.warn("[VeltisMC] Could not read the jar manifest, writing none:"
                + " {}", MojangMetadata.rootMessage(e));
            return null;
        }
    }

    private static boolean isClass(String name) {
        return name.endsWith(".class")
            && !name.contains("module-info")
            && !name.contains("package-info");
    }

    // ------------------------------------------------------------------
    // Cache marker
    // ------------------------------------------------------------------

    /** Proves which input, and which rewriter, produced the cached jar. */
    private static Path markerFor(Path output) {
        return output.resolveSibling(output.getFileName() + ".widen-marker");
    }

    private static String markerContent(String inputSha1) {
        return "format=" + FORMAT + "\ninputSha1=" + inputSha1 + "\n";
    }

    private static boolean markerMatches(Path output, String inputSha1) {
        var marker = markerFor(output);
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        try {
            return Files.readString(marker, StandardCharsets.UTF_8)
                .equals(markerContent(inputSha1));
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeMarker(Path output, String inputSha1) {
        MinecraftDownloader.writeMarker(markerFor(output), markerContent(inputSha1));
    }

    // ------------------------------------------------------------------
    // The rewrite itself
    // ------------------------------------------------------------------

    /** Package-private so {@code AccessWidenerTest} can widen a class in isolation. */
    static byte[] widenClass(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        // The constant pool and method bodies are copied through untouched: only
        // the access flags change, so every instruction offset in the class stays
        // exactly where it was. That is what lets a widened class still be the
        // same class the patches were authored against.
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            private boolean isInterface;
            private boolean isAnnotation;
            private boolean isEnum;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                this.isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
                this.isAnnotation = (access & Opcodes.ACC_ANNOTATION) != 0;
                this.isEnum = (access & Opcodes.ACC_ENUM) != 0;
                super.visit(version, widenClassAccess(access), name, signature, superName, interfaces);
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                super.visitInnerClass(name, outerName, innerName, widenAll(access, false));
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                return super.visitField(
                    widenAll(access, this.isInterface || this.isAnnotation), name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (isEnum && "<init>".equals(name)) {
                    // An enum constructor is implicitly private and the JVM rejects
                    // the class outright if it is not. The blanket rule below would
                    // turn it public, and Vineflower decompiles the flag verbatim,
                    // so the result is source that javac rejects with "modifier
                    // public not allowed here". Nothing is lost by leaving it
                    // alone: a constructor cannot be overridden anyway, so widening
                    // it buys no access a Veltis patch could have used.
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }
                if ("<clinit>".equals(name)) {
                    // JVMS 5.5: the static initializer must be static and must not
                    // be public, protected or private. Widening it would write a
                    // class file no strict verifier accepts.
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }
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
