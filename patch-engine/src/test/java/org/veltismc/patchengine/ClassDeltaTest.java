package org.veltismc.patchengine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ByteVector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/**
 * The runtime class-delta contract: one delta per modified class, carrying
 * only the delta — widened flags, removed members, and the members the patch
 * set actually changed — spliced onto a verified vanilla class at apply time.
 *
 * <p>The widened-access case is the regression this file exists for: ASM reads
 * a member's {@code Deprecated} attribute into the pseudo-flag
 * {@code ACC_DEPRECATED = 0x20000}, which does not fit the classfile's u2
 * access field. A delta format that truncated the recorded access to u2
 * silently dropped the flag during the splice, and verification caught it as a
 * member that disagreed with the compilation; the access field is u4 so the
 * whole node-level value round-trips.
 */
class ClassDeltaTest {

    private static final String INTERNAL = "net/minecraft/ClassDeltaTarget";
    private static final String ENTRY = INTERNAL + ".class";
    private static final String VERSION = "26.3";
    private static final String SHULKER = "b".repeat(64);

    // ------------------------------------------------------------------
    // One delta per modified class
    // ------------------------------------------------------------------

    @Test
    void aDeltaCarriesOnlyTheDeltaAndSplicesWidenedRemovedAndNewMembersOntoVanilla() {
        var vanilla = vanillaOf(INTERNAL);
        var compiled = compiledOf(INTERNAL);

        var result = ClassDeltaGenerator.generate(
            Map.of(ENTRY, vanilla), Map.of(INTERNAL, compiled), VERSION, SHULKER, 1);

        assertEquals(1, result.deltaCount(),
            "one modified class is one runtime delta, never one per source patch");
        assertEquals(1, result.entries().size());
        var entry = result.entries().get(0);
        assertEquals(ENTRY, entry.name());
        assertEquals(BytecodePatch.Kind.CLASS, entry.kind());
        assertEquals(BytecodePatch.sha256Hex(vanilla), entry.originalSha256());

        var applied = ClassDelta.apply(vanilla, entry.payload());
        assertEquals(entry.resultSha256(), BytecodePatch.sha256Hex(applied.bytes()),
            "the index line must promise exactly what applying the delta produces");

        var meta = applied.metadata();
        assertEquals(ClassDelta.FORMAT, meta.formatVersion());
        assertEquals(VERSION, meta.minecraftVersion());
        assertEquals(INTERNAL, meta.target());
        assertEquals(BytecodePatch.sha256Hex(vanilla), meta.vanillaSha256());
        assertEquals(BytecodePatch.sha256Hex(applied.bytes()), meta.patchedSha256());
        assertEquals(SHULKER, meta.shulkerFingerprint());
        assertTrue(meta.classPatchFingerprint().matches("[0-9a-f]{64}"),
            "the class-patch fingerprint must be a real SHA-256: "
                + meta.classPatchFingerprint());

        // The u4 regression, at the record level: the widened access keeps ASM's
        // ACC_DEPRECATED pseudo-flag instead of a u2-truncated 0x1.
        var widenedLegacy = meta.widened().stream()
            .filter(w -> "legacy".equals(w.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "the deprecated field's flag change must be recorded as a widening"));
        assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_DEPRECATED, widenedLegacy.access(),
            "the recorded access must be the compilation's whole value, pseudo-flag included");
        assertTrue(meta.removed().contains(new ClassDelta.MemberRef(false, "secret", "()V")),
            "the removal must be recorded by name and descriptor");

        // And at the class the applier hands to the loader.
        var merged = parse(applied.bytes());
        var legacy = field(merged, "legacy");
        assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_DEPRECATED, legacy.access,
            "the spliced field must be public and still deprecated: losing either"
                + " half would change what the class means");
        var constant = field(merged, "CONST");
        assertEquals(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, constant.access);
        assertEquals(1, constant.value, "an untouched final constant keeps its value");
        assertTrue(merged.methods.stream().noneMatch(m -> "secret".equals(m.name)),
            "the removed method must be gone from the result");
        assertTrue(merged.methods.stream().anyMatch(m -> "visible".equals(m.name)),
            "the new method must be present in the result");

        // The artifact contains only the delta: no unchanged vanilla member
        // travels in it, because kept members come from the vanilla class at
        // apply time, against a hash that was checked first.
        var payload = parse(entry.payload());
        assertTrue(payload.fields.isEmpty(),
            "no vanilla field may travel inside the delta payload");
        assertEquals(List.of("visible"),
            payload.methods.stream().map(m -> m.name).toList(),
            "only the changed member travels inside the delta payload");
    }

    @Test
    void aClassThePatchSetIntroducesShipsWholeAndIsNotADelta() {
        var introduced = compiledOf(INTERNAL);
        var result = ClassDeltaGenerator.generate(
            Map.of(), Map.of(INTERNAL, introduced), VERSION, SHULKER, 1);

        assertEquals(0, result.deltaCount(),
            "a class with no vanilla counterpart is a whole-class entry, not a delta");
        var entry = result.entries().get(0);
        assertEquals(BytecodePatch.Kind.ENTRY, entry.kind());
        assertEquals(BytecodePatch.ABSENT, entry.originalSha256(),
            "there is no baseline to hash, and '-' says so without pretending");
        assertArrayEquals(introduced, entry.payload());
        assertEquals(BytecodePatch.sha256Hex(introduced), entry.resultSha256());
    }

    @Test
    void aClassByteIdenticalToVanillaProducesNothing() {
        var same = vanillaOf(INTERNAL);
        var result = ClassDeltaGenerator.generate(
            Map.of(ENTRY, same), Map.of(INTERNAL, same), VERSION, SHULKER, 1);

        assertEquals(0, result.deltaCount());
        assertEquals(0, result.entries().size(),
            "an identical class has no difference to distribute");
        assertEquals(1, result.unchanged());
    }

    @Test
    void theRuntimeCountEqualsTheNumberOfDistinctModifiedClasses() {
        var first = INTERNAL;
        var second = "net/minecraft/SecondTarget";
        var third = "net/minecraft/UntouchedOne";
        var fourth = "net/minecraft/UntouchedTwo";
        var vanilla = Map.of(
            first + ".class", vanillaOf(first),
            second + ".class", vanillaOf(second),
            third + ".class", vanillaOf(third),
            fourth + ".class", vanillaOf(fourth));
        var compiled = Map.of(
            first, compiledOf(first),
            second, compiledOf(second),
            third, vanillaOf(third),
            fourth, vanillaOf(fourth));

        var result = ClassDeltaGenerator.generate(vanilla, compiled, VERSION, SHULKER, 1);

        var classEntries = result.entries().stream()
            .filter(entry -> entry.kind() == BytecodePatch.Kind.CLASS)
            .toList();
        assertEquals(2, classEntries.size(),
            "two distinct modified classes are two runtime deltas, whatever the"
                + " source patch count was");
        assertEquals(result.deltaCount(), classEntries.size(),
            "the reported delta count and the class entries must be the same quantity");
        assertEquals(2, result.unchanged());
        assertTrue(classEntries.stream().allMatch(entry ->
            entry.name().equals(first + ".class") || entry.name().equals(second + ".class")),
            "the untouched classes must not appear in the patch set at all");
    }

    @Test
    void generationIsDeterministicAcrossWorkerCounts() {
        var vanilla = Map.of(
            ENTRY, vanillaOf(INTERNAL),
            "net/minecraft/SecondTarget.class", vanillaOf("net/minecraft/SecondTarget"));
        var compiled = Map.of(
            INTERNAL, compiledOf(INTERNAL),
            "net/minecraft/SecondTarget", compiledOf("net/minecraft/SecondTarget"));

        var one = ClassDeltaGenerator.generate(vanilla, compiled, VERSION, SHULKER, 1);
        var four = ClassDeltaGenerator.generate(vanilla, compiled, VERSION, SHULKER, 4);

        assertEquals(1, one.timings().workers());
        assertEquals(4, four.timings().workers());
        assertEquals(one.entries().size(), four.entries().size());
        for (var i = 0; i < one.entries().size(); i++) {
            var sequential = one.entries().get(i);
            var parallel = four.entries().get(i);
            assertEquals(sequential.name(), parallel.name());
            assertEquals(sequential.kind(), parallel.kind());
            assertArrayEquals(sequential.payload(), parallel.payload(),
                "the bounded pool must not change what is written: entry "
                    + sequential.name() + " differs between worker counts");
        }
        assertTrue(one.timings().totalNanos() > 0L, "the timings must be real");
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    @Test
    void aDeltaBuiltAgainstAnotherVanillaClassIsRefused() {
        var vanilla = vanillaOf(INTERNAL);
        var entry = ClassDeltaGenerator.generate(
            Map.of(ENTRY, vanilla), Map.of(INTERNAL, compiledOf(INTERNAL)),
            VERSION, SHULKER, 1).entries().get(0);

        var replaced = vanillaOfWithExtraField(INTERNAL);
        var failure = assertThrows(PatchEngineException.class,
            () -> ClassDelta.apply(replaced, entry.payload()),
            "a baseline the delta was not cut against must not be spliced");
        assertTrue(failure.getMessage().contains("does not match the class this"),
            failure.getMessage());
        assertTrue(failure.getMessage().contains("Expected vanilla SHA-256"),
            failure.getMessage());
        assertTrue(failure.getMessage().contains(INTERNAL), failure.getMessage());
    }

    @Test
    void aDeltaForADifferentTargetIsRefused() {
        var vanilla = vanillaOf(INTERNAL);
        var other = "net/minecraft/OtherDeltaTarget";
        var otherPayload = ClassDeltaGenerator.generate(
            Map.of(other + ".class", vanillaOf(other)),
            Map.of(other, compiledOf(other)), VERSION, SHULKER, 1)
            .entries().get(0).payload();

        var failure = assertThrows(PatchEngineException.class,
            () -> ClassDelta.apply(vanilla, otherPayload),
            "applying one class's delta to another class must fail before merging");
        assertTrue(failure.getMessage().contains("was applied to a different class"),
            failure.getMessage());
    }

    @Test
    void aDeltaInTheWrongFormatIsRefusedBeforeAnythingIsApplied() {
        var vanilla = vanillaOf(INTERNAL);
        var payload = ClassDeltaGenerator.generate(
            Map.of(ENTRY, vanilla), Map.of(INTERNAL, compiledOf(INTERNAL)),
            VERSION, SHULKER, 1).entries().get(0).payload().clone();

        var content = attributeContentOffset(payload);
        ByteBuffer.wrap(payload).putInt(content, ClassDelta.FORMAT + 1);

        var failure = assertThrows(PatchEngineException.class,
            () -> ClassDelta.apply(vanilla, payload),
            "a format this build does not understand must be a clear refusal");
        assertTrue(failure.getMessage().contains("uses format"), failure.getMessage());
        assertTrue(failure.getMessage().contains(String.valueOf(ClassDelta.FORMAT + 1)),
            failure.getMessage());
        assertTrue(failure.getMessage().contains("Nothing has been applied"),
            failure.getMessage());
    }

    @Test
    void aPayloadThatIsNotAClassFileIsRefused() {
        var vanilla = vanillaOf(INTERNAL);
        var failure = assertThrows(PatchEngineException.class,
            () -> ClassDelta.apply(vanilla, new byte[] {1, 2, 3}),
            "garbage must be reported as unreadable, not partially applied");
        assertTrue(failure.getMessage().contains("not a readable class file"),
            failure.getMessage());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * The baseline: one private deprecated field (private {@code +} the
     * {@code Deprecated} attribute reads back as {@code 0x20002}), one private
     * final constant, one private method.
     */
    private static byte[] vanillaOf(String internal) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_DEPRECATED,
            "legacy", "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
            "CONST", "I", null, 1).visitEnd();
        var secret = writer.visitMethod(Opcodes.ACC_PRIVATE, "secret", "()V", null, null);
        secret.visitCode();
        secret.visitInsn(Opcodes.RETURN);
        secret.visitMaxs(0, 0);
        secret.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * The compilation: the deprecated field widened to public (still
     * deprecated, {@code 0x20001}), the constant untouched, the private method
     * removed, a new public method added.
     */
    private static byte[] compiledOf(String internal) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_DEPRECATED,
            "legacy", "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
            "CONST", "I", null, 1).visitEnd();
        var visible = writer.visitMethod(Opcodes.ACC_PUBLIC, "visible", "()V", null, null);
        visible.visitCode();
        visible.visitInsn(Opcodes.RETURN);
        visible.visitMaxs(0, 0);
        visible.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Same class name, different bytes — the hash check is what must fire. */
    private static byte[] vanillaOfWithExtraField(String internal) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_DEPRECATED,
            "legacy", "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
            "CONST", "I", null, 1).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "extra", "I", null, null).visitEnd();
        var secret = writer.visitMethod(Opcodes.ACC_PRIVATE, "secret", "()V", null, null);
        secret.visitCode();
        secret.visitInsn(Opcodes.RETURN);
        secret.visitMaxs(0, 0);
        secret.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode parse(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static org.objectweb.asm.tree.FieldNode field(ClassNode node, String name) {
        return node.fields.stream()
            .filter(f -> name.equals(f.name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the class must declare field " + name));
    }

    /**
     * Where the {@link ClassDelta#ATTRIBUTE_TYPE} content begins inside a
     * delta payload, located by letting the classfile parser itself report
     * the attribute's offset and length.
     */
    private static int attributeContentOffset(byte[] payload) {
        var seen = new int[] {-1};
        var probe = new Attribute(ClassDelta.ATTRIBUTE_TYPE) {
            @Override
            protected Attribute read(ClassReader reader, int offset, int length,
                                     char[] buffer, int codeAttributeOffset,
                                     Label[] labelCache) {
                seen[0] = offset;
                return this;
            }

            @Override
            protected ByteVector write(ClassWriter writer, byte[] code, int codeLength,
                                       int stack, int locals) {
                return new ByteVector();
            }
        };
        new ClassReader(payload).accept(new ClassVisitor(Opcodes.ASM9) {
        }, new Attribute[] {probe}, 0);
        assertTrue(seen[0] >= 0,
            "the delta payload must carry its " + ClassDelta.ATTRIBUTE_TYPE + " attribute");
        return seen[0];
    }
}
