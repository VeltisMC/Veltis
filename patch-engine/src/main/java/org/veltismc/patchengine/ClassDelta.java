package org.veltismc.patchengine;

import org.objectweb.asm.Attribute;
import org.objectweb.asm.ByteVector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One runtime class delta: the difference between a vanilla class and the class
 * the patched sources compile to, expressed as member additions, member
 * removals and access-flag rewrites — never as a replacement class file.
 *
 * <h2>Why a delta and not a class</h2>
 *
 * <p>A full replacement class would carry Mojang's bytecode (copied through by
 * javac from a decompilation of it) for every method the patch did not touch.
 * A delta carries only what the patched sources actually changed: methods and
 * fields that differ after {@linkplain ClassDeltaGenerator normalisation},
 * members that disappeared, and the access flags vanilla declares differently
 * from what the patched compilation saw. Everything else is served from the
 * verified vanilla class at apply time.</p>
 *
 * <h2>File format (versioned binary, not JSON)</h2>
 *
 * <p>A delta is itself a class file. Its header is the patched class's header
 * (version, access, name, super, interfaces); its members are the carried
 * (added or modified) members; its class-level attributes are the patched
 * class's. The delta metadata lives in one custom class-level attribute,
 * {@value #ATTRIBUTE_TYPE}, laid out big-endian as:</p>
 *
 * <pre>
 *   u4 formatVersion
 *   u2 minecraftVersion length, Minecraft version (UTF-8)
 *   u2 target length, target internal class name (UTF-8)
 *   32 bytes vanilla SHA-256
 *   32 bytes patched (result) SHA-256
 *   32 bytes Shulker source-patch fingerprint (SHA-256)
 *   32 bytes class-patch fingerprint (SHA-256)
 *   u2 removed count
 *     repeated: u1 kind (0 = field, 1 = method),
 *               u2 name length + name, u2 descriptor length + descriptor
 *   u2 widened count
 *     repeated: u1 kind, u2 name length + name, u2 descriptor length +
 *               descriptor, u4 new access flags (u4 because ASM reads a
 *               member's Deprecated attribute into the pseudo-flag
 *               {@code ACC_DEPRECATED = 0x20000}, which a u2 would silently
 *               truncate and lose)
 * </pre>
 *
 * <p>Nothing in the attribute references the constant pool, so the payload
 * survives any pool rebuild. The applier trusts none of the recorded hashes:
 * it re-derives them from the bytes in front of it ({@link #apply}), and a
 * mismatch in format, target, vanilla bytes, result bytes or fingerprint is a
 * clear refusal rather than a partial application.</p>
 *
 * <h2>Applying</h2>
 *
 * <ol>
 *   <li>Locate the vanilla class (the caller does; the hash checked here
 *       proves it is the right one).</li>
 *   <li>Verify the vanilla SHA-256 recorded in the delta.</li>
 *   <li>Splice: header from the delta, class metadata by the rules in
 *       {@link #merge}, members merged per the delta's lists.</li>
 *   <li>Verify the result SHA-256 and the class-patch fingerprint.</li>
 * </ol>
 *
 * <p>Every failure message names the target, both sides of the mismatch and
 * the reason, and states that nothing has been applied.</p>
 */
public final class ClassDelta {

    /** The custom class-level attribute that carries the delta metadata. */
    public static final String ATTRIBUTE_TYPE = "VeltisClassDelta";

    /**
     * The delta format version. Shares the patch-set format number so a
     * container and its deltas are always read by one build.
     */
    public static final int FORMAT = BytecodePatch.FORMAT;

    private static final int HASH_BYTES = 32;
    private static final int KIND_FIELD = 0;
    private static final int KIND_METHOD = 1;
    private static final String ZERO_HASH =
        "0".repeat(HASH_BYTES * 2);

    private ClassDelta() {
    }

    /**
     * A field or method reference: kind, name, descriptor.
     *
     * @param field whether this names a field ({@code false} = method)
     * @param name  the member name
     * @param desc  the member descriptor
     */
    public record MemberRef(boolean field, String name, String desc) {
    }

    /**
     * An access-flag rewrite the delta performs on a vanilla member.
     *
     * @param field  whether this names a field ({@code false} = method)
     * @param name   the member name
     * @param desc   the member descriptor
     * @param access the new access flags, exactly as the patched compilation
     *               declared them
     */
    public record WidenedMember(boolean field, String name, String desc, int access) {
    }

    /**
     * Everything a delta says about itself.
     *
     * @param formatVersion        the delta format version
     * @param minecraftVersion     the Minecraft version the vanilla hash came from
     * @param target               the target internal class name
     * @param vanillaSha256        SHA-256 of the vanilla class it patches
     * @param patchedSha256        SHA-256 of the class applying this delta yields
     * @param shulkerFingerprint   fingerprint of the Shulker source patch set
     * @param classPatchFingerprint fingerprint binding the three values above
     * @param removed              members the vanilla class declares and the
     *                             patched class does not
     * @param widened              members whose access flags differ
     */
    public record Metadata(int formatVersion, String minecraftVersion, String target,
                           String vanillaSha256, String patchedSha256,
                           String shulkerFingerprint, String classPatchFingerprint,
                           List<MemberRef> removed, List<WidenedMember> widened) {
        public Metadata {
            Objects.requireNonNull(target, "target cannot be null");
            Objects.requireNonNull(vanillaSha256, "vanillaSha256 cannot be null");
            Objects.requireNonNull(patchedSha256, "patchedSha256 cannot be null");
            removed = removed == null ? List.of() : List.copyOf(removed);
            widened = widened == null ? List.of() : List.copyOf(widened);
        }
    }

    /**
     * What applying one delta produced.
     *
     * @param bytes    the merged class bytes — the class the server must serve
     * @param metadata the delta's recorded metadata, for cross-checking against
     *                 the index that carried the payload
     */
    public record Applied(byte[] bytes, Metadata metadata) {
    }

    // ------------------------------------------------------------------
    // Applying
    // ------------------------------------------------------------------

    /**
     * Applies a delta to its vanilla class, verifying every recorded claim.
     *
     * <p>The vanilla hash, the result hash and the class-patch fingerprint are
     * all re-derived from the bytes at hand; the delta's own claims are only
     * accepted when they match. This is the whole of the runtime contract:
     * version mismatch, target mismatch, vanilla mismatch, result mismatch and
     * fingerprint mismatch each refuse the delta with a message that names
     * both sides, and none of them writes anywhere.</p>
     *
     * @param vanillaBytes the verified vanilla class bytes
     * @param deltaBytes   the delta payload
     * @return the merged class and the metadata that produced it
     * @throws PatchEngineException when the payload is not a delta in this
     *                               format, targets another class, was built
     *                               against other vanilla bytes, or does not
     *                               produce the class it promises
     */
    public static Applied apply(byte[] vanillaBytes, byte[] deltaBytes) {
        return applyCore(vanillaBytes, deltaBytes, true);
    }

    /**
     * Applies without checking the result hash — used once during generation,
     * where the result hash is what the second pass is about to compute.
     * Everything else (format, target, vanilla hash) is still enforced.
     */
    static Applied applyForGeneration(byte[] vanillaBytes, byte[] deltaBytes) {
        return applyCore(vanillaBytes, deltaBytes, false);
    }

    private static Applied applyCore(byte[] vanillaBytes, byte[] deltaBytes, boolean verifyResult) {
        var delta = parseDelta(deltaBytes);
        var meta = metadataOf(delta);
        var vanilla = new ClassNode();
        try {
            new ClassReader(vanillaBytes).accept(vanilla, 0);
        } catch (RuntimeException e) {
            throw new PatchEngineException(
                "[Veltis] The vanilla class for " + meta.target() + " is not readable"
                    + "\n  Reason: " + MojangMetadata.rootMessage(e)
                    + "\n  Nothing has been applied.", e);
        }
        if (!Objects.equals(meta.target(), delta.name)) {
            throw new PatchEngineException(
                "[Veltis] Class delta metadata and payload disagree about the target class"
                    + "\n  Metadata target: " + meta.target()
                    + "\n  Payload target:  " + delta.name
                    + "\n  Reason: a delta that names one class but carries another would"
                    + " splice one class's members into a different one"
                    + "\n  Nothing has been applied."
                    + "\n  Fix: regenerate the patch set.");
        }
        if (!Objects.equals(meta.target(), vanilla.name)) {
            throw new PatchEngineException(
                "[Veltis] Class delta for " + meta.target() + " was applied to a different class"
                    + "\n  Delta target:     " + meta.target()
                    + "\n  Vanilla class:    " + vanilla.name
                    + "\n  Reason: the caller looked up a vanilla class that is not the one"
                    + " this delta was cut from, so the two would be spliced together"
                    + "\n  Nothing has been applied.");
        }
        var actualVanilla = BytecodePatch.sha256Hex(vanillaBytes);
        if (!actualVanilla.equalsIgnoreCase(meta.vanillaSha256())) {
            throw new PatchEngineException(
                "[Veltis] The vanilla class " + meta.target() + " does not match the class this"
                    + " delta was built against"
                    + "\n  Expected vanilla SHA-256: " + meta.vanillaSha256()
                    + "\n  Actual vanilla SHA-256:   " + actualVanilla
                    + "\n  Reason: the delta's member lists describe a different vanilla class,"
                    + " so applying it would add, drop or rewrite the wrong members"
                    + "\n  Nothing has been applied."
                    + "\n  Fix: the patch set and the server jar come from different builds;"
                    + " regenerate the patch set against this server jar.");
        }
        var merged = merge(vanilla, delta, meta);
        if (verifyResult) {
            var actualResult = BytecodePatch.sha256Hex(merged);
            if (!actualResult.equalsIgnoreCase(meta.patchedSha256())) {
                throw new PatchEngineException(
                    "[Veltis] Applying the delta for " + meta.target() + " did not produce the"
                        + " class it promises"
                        + "\n  Expected result SHA-256: " + meta.patchedSha256()
                        + "\n  Actual result SHA-256:   " + actualResult
                        + "\n  Reason: the delta payload is corrupt, or this build's applier"
                        + " disagrees with the build that recorded the hash. Either way the"
                        + " served class would not be the one that was verified"
                        + "\n  Nothing has been applied."
                        + "\n  Fix: regenerate the patch set.");
            }
            var fingerprint =
                classPatchFingerprint(meta.vanillaSha256(), meta.patchedSha256(), meta.target());
            if (!fingerprint.equalsIgnoreCase(meta.classPatchFingerprint())) {
                throw new PatchEngineException(
                    "[Veltis] The class-patch fingerprint of " + meta.target() + " does not match"
                        + " its own hashes"
                        + "\n  Recorded fingerprint: " + meta.classPatchFingerprint()
                        + "\n  Recomputed:           " + fingerprint
                        + "\n  Reason: the fingerprint binds target, vanilla hash, result hash"
                        + " and format, so a mismatch means the metadata was edited after the"
                        + " hashes were written"
                        + "\n  Nothing has been applied."
                        + "\n  Fix: regenerate the patch set.");
            }
        }
        return new Applied(merged, meta);
    }

    /**
     * Parses a delta payload, refusing anything that is not a readable class
     * carrying {@link #ATTRIBUTE_TYPE}.
     */
    private static ClassNode parseDelta(byte[] deltaBytes) {
        var node = new ClassNode();
        try {
            new ClassReader(deltaBytes).accept(node, new Attribute[]{new DeltaAttribute()}, 0);
        } catch (PatchEngineException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PatchEngineException(
                "[Veltis] The class delta payload is not a readable class file"
                    + "\n  Size: " + deltaBytes.length + " bytes"
                    + "\n  Reason: " + MojangMetadata.rootMessage(e)
                    + "\n  Nothing has been applied."
                    + "\n  Fix: regenerate the patch set.", e);
        }
        return node;
    }

    private static Metadata metadataOf(ClassNode delta) {
        DeltaAttribute found = null;
        if (delta.attrs != null) {
            for (var attribute : delta.attrs) {
                if (attribute instanceof DeltaAttribute deltaAttribute) {
                    if (found != null) {
                        throw new PatchEngineException(
                            "[Veltis] The class delta payload declares " + ATTRIBUTE_TYPE
                                + " twice"
                                + "\n  Target: " + delta.name
                                + "\n  Reason: a delta has one set of metadata; two would make"
                                + " the applied result depend on which one is read first"
                                + "\n  Nothing has been applied."
                                + "\n  Fix: regenerate the patch set.");
                    }
                    found = deltaAttribute;
                }
            }
        }
        if (found == null || found.metadata() == null) {
            throw new PatchEngineException(
                "[Veltis] The class delta payload carries no " + ATTRIBUTE_TYPE + " attribute"
                    + "\n  Reason: that attribute is where a delta declares its target, its"
                    + " hashes and its member lists. Without it the payload is an ordinary"
                    + " class file, not a delta, and applying it would replace a verified"
                    + " vanilla class with something unverified"
                    + "\n  Nothing has been applied."
                    + "\n  Fix: regenerate the patch set.");
        }
        return found.metadata();
    }

    // ------------------------------------------------------------------
    // Splicing
    // ------------------------------------------------------------------

    /**
     * Builds the merged class from the verified vanilla node and the delta.
     *
     * <p>Header comes from the delta (it is the patched class's header).
     * Class-level metadata (signature, source, annotations, record components
     * and the rest) is taken as one group: the delta's when the two groups
     * serialise differently, vanilla's when they serialise the same — so the
     * result matches what shipping the compiled class would have produced.
     * Inner-class and nest-member entries are the delta's list with vanilla-only
     * names appended, which keeps the patched entries and does not lose a
     * vanilla entry the patched compilation did not mention. Unknown class
     * attributes stay vanilla's; the delta never carries them. Members merge
     * per the delta's lists: carried members replace or append, removed members
     * vanish, widened members keep their vanilla bodies with the recorded
     * flags.</p>
     */
    private static byte[] merge(ClassNode vanilla, ClassNode delta, Metadata meta) {
        var removed = new HashSet<>(meta.removed());
        var widened = new HashMap<MemberRef, Integer>();
        for (var entry : meta.widened()) {
            widened.put(new MemberRef(entry.field(), entry.name(), entry.desc()), entry.access());
        }
        var deltaFields = index(delta.fields);
        var deltaMethods = index(delta.methods);
        checkContradictions(meta.target(), removed, widened, deltaFields, deltaMethods);

        var merged = new ClassNode();
        merged.version = delta.version;
        merged.access = delta.access;
        merged.name = delta.name;
        merged.superName = delta.superName;
        merged.interfaces = delta.interfaces != null ? delta.interfaces
            : (vanilla.interfaces != null ? vanilla.interfaces : new ArrayList<>());
        if (Arrays.equals(scalarShell(vanilla), scalarShell(delta))) {
            copyScalars(vanilla, merged);
        } else {
            copyScalars(delta, merged);
        }
        merged.innerClasses = unionInner(delta.innerClasses, vanilla.innerClasses);
        merged.nestMembers = unionNames(delta.nestMembers, vanilla.nestMembers);
        merged.attrs = vanilla.attrs;
        merged.fields = mergeMembers(vanilla.fields, delta.fields, removed, widened,
            deltaFields, meta.target(), true);
        merged.methods = mergeMembers(vanilla.methods, delta.methods, removed, widened,
            deltaMethods, meta.target(), false);

        var writer = new ClassWriter(0);
        merged.accept(writer);
        return writer.toByteArray();
    }

    /** Every member the delta carries must not also be removed or widened. */
    private static void checkContradictions(String target, Set<MemberRef> removed,
                                            Map<MemberRef, Integer> widened,
                                            Map<MemberRef, FieldNode> deltaFields,
                                            Map<MemberRef, MethodNode> deltaMethods) {
        for (var key : removed) {
            if (deltaFields.containsKey(key) || deltaMethods.containsKey(key)) {
                throw contradiction(target, key, "removes and carries");
            }
        }
        for (var key : widened.keySet()) {
            if (deltaFields.containsKey(key) || deltaMethods.containsKey(key)) {
                throw contradiction(target, key, "rewrites the flags of and carries");
            }
        }
    }

    private static PatchEngineException contradiction(String target, MemberRef key, String verb) {
        return new PatchEngineException(
            "[Veltis] The class delta for " + target + " both " + verb + " member "
                + key.name() + key.desc()
                + "\n  Reason: a delta decides one way per member — carried members bring"
                + " their own flags, the others are described by the removed and widened"
                + " lists — so a member in two of them makes the result ambiguous"
                + "\n  Nothing has been applied."
                + "\n  Fix: regenerate the patch set.");
    }

    private static <T> Map<MemberRef, T> index(List<T> members) {
        var byKey = new HashMap<MemberRef, T>();
        for (var member : members) {
            MemberRef key;
            if (member instanceof FieldNode field) {
                key = new MemberRef(true, field.name, field.desc);
            } else if (member instanceof MethodNode method) {
                key = new MemberRef(false, method.name, method.desc);
            } else {
                throw new PatchEngineException(
                    "[Veltis] Refusing to merge a class delta holding an unknown member node: "
                        + member.getClass().getName()
                        + "\n  Reason: only fields and methods can be spliced into a vanilla"
                        + " class, and anything else here would mean a malformed payload");
            }
            var previous = byKey.put(key, member);
            if (previous != null) {
                throw new PatchEngineException(
                    "[Veltis] The class delta carries member " + key.name() + key.desc()
                        + " twice"
                        + "\n  Reason: one class file has one member of a given name and"
                        + " descriptor, so a duplicate would make the result depend on order");
            }
        }
        return byKey;
    }

    /**
     * Merges one kind of member: walks the vanilla list, dropping what the
     * delta removes, replacing what it carries and applying recorded flag
     * rewrites, then appends the delta's new members. Every claim is checked
     * against the vanilla list — a removed or widened member vanilla does not
     * declare means the delta was not built from these bytes, and vanilla's
     * hash has already been verified, so that can only be a bad build.
     */
    private static <T> List<T> mergeMembers(List<T> vanillaMembers, List<T> deltaMembers,
                                            Set<MemberRef> removed,
                                            Map<MemberRef, Integer> widened,
                                            Map<MemberRef, T> deltaByKey, String target,
                                            boolean fields) {
        var vanillaKeys = new HashSet<MemberRef>();
        var out = new ArrayList<T>(vanillaMembers.size());
        for (var member : vanillaMembers) {
            var key = memberKey(member, fields);
            vanillaKeys.add(key);
            if (removed.contains(key)) {
                continue;
            }
            var carried = deltaByKey.get(key);
            if (carried != null) {
                out.add(carried);
                continue;
            }
            var access = widened.get(key);
            if (access != null) {
                applyAccess(member, access, fields);
            }
            out.add(member);
        }
        for (var key : removed) {
            if ((fields && key.field()) || (!fields && !key.field())) {
                if (!vanillaKeys.contains(key)) {
                    throw unexpectedMember(target, key, "removes");
                }
            }
        }
        for (var key : widened.keySet()) {
            if ((fields && key.field()) || (!fields && !key.field())) {
                if (!vanillaKeys.contains(key)) {
                    throw unexpectedMember(target, key, "rewrites the flags of");
                }
            }
        }
        for (var member : deltaMembers) {
            var key = memberKey(member, fields);
            if (!vanillaKeys.contains(key)) {
                out.add(member);
            }
        }
        return out;
    }

    private static PatchEngineException unexpectedMember(String target, MemberRef key,
                                                         String verb) {
        return new PatchEngineException(
            "[Veltis] The class delta for " + target + " " + verb + " member "
                + key.name() + key.desc() + ", which the vanilla class does not declare"
                + "\n  Reason: the vanilla class's hash matched the hash the delta was built"
                + " against, so this delta was not built from these bytes"
                + "\n  Nothing has been applied."
                + "\n  Fix: regenerate the patch set against this server jar.");
    }

    private static MemberRef memberKey(Object member, boolean fields) {
        if (fields) {
            var field = (FieldNode) member;
            return new MemberRef(true, field.name, field.desc);
        }
        var method = (MethodNode) member;
        return new MemberRef(false, method.name, method.desc);
    }

    /**
     * Rewrites one member's access flags, dropping a constant value that the
     * patched compilation would not have recorded. A non-final field carries
     * its initial value in the static initialiser — which the delta carries or
     * the vanilla class already has — not in a ConstantValue attribute.
     */
    private static <T> void applyAccess(T member, int access, boolean fields) {
        if (fields) {
            var field = (FieldNode) member;
            field.access = access;
            if ((access & Opcodes.ACC_FINAL) == 0) {
                field.value = null;
            }
        } else {
            ((MethodNode) member).access = access;
        }
    }

    // ------------------------------------------------------------------
    // Class-level metadata rules
    // ------------------------------------------------------------------

    /**
     * Serialises just the scalar class-level metadata of a class: signature,
     * source, module, enclosing class, nest host, permitted subclasses,
     * annotations and record components — with a fixed header and no members,
     * no inner-class table and no unknown attributes, none of which this
     * comparison is about. Two classes serialize the same here exactly when
     * this group of attributes is the same.
     */
    static byte[] scalarShell(ClassNode node) {
        var shell = new ClassNode();
        shell.version = Opcodes.V21;
        shell.access = Opcodes.ACC_PUBLIC;
        shell.name = "Shell";
        shell.superName = "java/lang/Object";
        shell.interfaces = new ArrayList<>();
        shell.innerClasses = new ArrayList<>();
        shell.fields = new ArrayList<>();
        shell.methods = new ArrayList<>();
        copyScalars(node, shell);
        var writer = new ClassWriter(0);
        shell.accept(writer);
        return writer.toByteArray();
    }

    static void copyScalars(ClassNode from, ClassNode to) {
        to.signature = from.signature;
        to.sourceFile = from.sourceFile;
        to.sourceDebug = from.sourceDebug;
        to.module = from.module;
        to.outerClass = from.outerClass;
        to.outerMethod = from.outerMethod;
        to.outerMethodDesc = from.outerMethodDesc;
        to.nestHostClass = from.nestHostClass;
        to.permittedSubclasses = from.permittedSubclasses;
        to.visibleAnnotations = from.visibleAnnotations;
        to.invisibleAnnotations = from.invisibleAnnotations;
        to.visibleTypeAnnotations = from.visibleTypeAnnotations;
        to.invisibleTypeAnnotations = from.invisibleTypeAnnotations;
        to.recordComponents = from.recordComponents;
    }

    /**
     * The delta's list first, in its order, then the vanilla entries whose
     * names it does not mention: the patched compilation's view wins, and a
     * vanilla inner class the patch never touched keeps its entry.
     */
    private static List<InnerClassNode> unionInner(List<InnerClassNode> base,
                                                   List<InnerClassNode> extra) {
        if (base == null || base.isEmpty()) {
            return extra == null ? new ArrayList<>() : new ArrayList<>(extra);
        }
        var out = new ArrayList<>(base);
        if (extra != null) {
            var seen = new HashSet<String>();
            for (var entry : base) {
                seen.add(entry.name);
            }
            for (var entry : extra) {
                if (seen.add(entry.name)) {
                    out.add(entry);
                }
            }
        }
        return out;
    }

    private static List<String> unionNames(List<String> base, List<String> extra) {
        if (base == null || base.isEmpty()) {
            return extra == null ? new ArrayList<>() : new ArrayList<>(extra);
        }
        var out = new ArrayList<>(base);
        if (extra != null) {
            var seen = new HashSet<>(base);
            for (var name : extra) {
                if (seen.add(name)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Fingerprints
    // ------------------------------------------------------------------

    /**
     * The class-patch fingerprint: SHA-256 over the vanilla hash (32 bytes),
     * the result hash (32 bytes), the target internal name (UTF-8) and the
     * delta format as a big-endian u4. It binds each delta to exactly the
     * three claims it makes, so editing any of them without rewriting the
     * fingerprint is caught even though each hash is checked separately.
     *
     * @param vanillaSha256 hex SHA-256 of the vanilla class
     * @param patchedSha256 hex SHA-256 of the merged class
     * @param target        the target internal class name
     * @return the hex fingerprint
     */
    static String classPatchFingerprint(String vanillaSha256, String patchedSha256,
                                        String target) {
        var vanilla = parseHash(vanillaSha256, "vanilla");
        var patched = parseHash(patchedSha256, "result");
        var name = target.getBytes(StandardCharsets.UTF_8);
        var all = new byte[HASH_BYTES + HASH_BYTES + name.length + 4];
        System.arraycopy(vanilla, 0, all, 0, HASH_BYTES);
        System.arraycopy(patched, 0, all, HASH_BYTES, HASH_BYTES);
        System.arraycopy(name, 0, all, HASH_BYTES * 2, name.length);
        var at = HASH_BYTES * 2 + name.length;
        all[at] = (byte) (FORMAT >>> 24);
        all[at + 1] = (byte) (FORMAT >>> 16);
        all[at + 2] = (byte) (FORMAT >>> 8);
        all[at + 3] = (byte) FORMAT;
        return BytecodePatch.sha256Hex(all);
    }

    /** The zero hash used as a placeholder before the result is known. */
    static String placeholderHash() {
        return ZERO_HASH;
    }

    private static byte[] parseHash(String hash, String label) {
        if (hash == null || hash.length() != HASH_BYTES * 2) {
            throw new PatchEngineException(
                "[Veltis] The class delta's " + label + " hash is malformed: " + hash
                    + "\n  Reason: a SHA-256 is " + HASH_BYTES * 2 + " hex characters; a"
                    + " different length means the metadata was written by hand or truncated");
        }
        try {
            return HexFormat.of().parseHex(hash);
        } catch (IllegalArgumentException e) {
            throw new PatchEngineException(
                "[Veltis] The class delta's " + label + " hash is not hexadecimal: " + hash
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }

    // ------------------------------------------------------------------
    // The attribute
    // ------------------------------------------------------------------

    /**
     * Creates the class-level attribute embedding one metadata record. The
     * generator writes it into the delta node's attribute list; this is the
     * only way deltas gain their metadata.
     */
    static Attribute attribute(Metadata metadata) {
        return new DeltaAttribute(Objects.requireNonNull(metadata,
            "metadata cannot be null"));
    }

    /**
     * The {@code VeltisClassDelta} class attribute: parse side and serialise
     * side of {@link ClassDelta#FORMAT}'s binary layout.
     */
    private static final class DeltaAttribute extends Attribute {

        private final Metadata metadata;

        /** Prototype passed to {@link ClassReader#accept}; filled by {@code read}. */
        private DeltaAttribute() {
            super(ATTRIBUTE_TYPE);
            this.metadata = null;
        }

        private DeltaAttribute(Metadata metadata) {
            super(ATTRIBUTE_TYPE);
            this.metadata = metadata;
        }

        Metadata metadata() {
            return metadata;
        }

        @Override
        protected Attribute read(ClassReader reader, int offset, int length, char[] buffer,
                                 int codeOffset, Label[] labels) {
            var bytes = reader.readBytes(offset, length);
            return new DeltaAttribute(decode(bytes));
        }

        @Override
        protected ByteVector write(ClassWriter writer, byte[] code, int length, int maxStack,
                                   int maxLocals) {
            if (metadata == null) {
                throw new PatchEngineException(
                    "[Veltis] Refusing to serialise a " + ATTRIBUTE_TYPE + " prototype"
                        + "\n  Reason: the prototype carries no metadata, so writing it would"
                        + " produce a delta without target or hashes");
            }
            var bytes = encode(metadata);
            return new ByteVector().putByteArray(bytes, 0, bytes.length);
        }
    }

    private static byte[] encode(Metadata meta) {
        try {
            var out = new ByteArrayOutputStream();
            var data = new DataOutputStream(out);
            data.writeInt(meta.formatVersion());
            writeString(data, meta.minecraftVersion());
            writeString(data, meta.target());
            data.write(parseHash(meta.vanillaSha256(), "vanilla"));
            data.write(parseHash(meta.patchedSha256(), "result"));
            data.write(parseHash(meta.shulkerFingerprint(), "Shulker"));
            data.write(parseHash(meta.classPatchFingerprint(), "class-patch fingerprint"));
            if (meta.removed().size() > 0xFFFF || meta.widened().size() > 0xFFFF) {
                throw new PatchEngineException(
                    "[Veltis] The class delta for " + meta.target() + " has too many members"
                        + "\n  Removed: " + meta.removed().size()
                        + "\n  Widened: " + meta.widened().size()
                        + "\n  Reason: the format counts members with a u2, and no real class"
                        + " comes near " + 0xFFFF);
            }
            data.writeShort(meta.removed().size());
            for (var member : meta.removed()) {
                writeMember(data, member.field(), member.name(), member.desc());
            }
            data.writeShort(meta.widened().size());
            for (var member : meta.widened()) {
                writeMember(data, member.field(), member.name(), member.desc());
                data.writeInt(member.access());
            }
            data.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new PatchEngineException(
                "[Veltis] Failed to encode the class delta metadata for " + meta.target()
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        var bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xFFFF) {
            throw new PatchEngineException(
                "[Veltis] Refusing to encode a class delta string longer than "
                    + 0xFFFF + " bytes: " + bytes.length
                    + "\n  Reason: the format stores string lengths as u2");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static void writeMember(DataOutputStream out, boolean field, String name,
                                    String desc) throws IOException {
        out.writeByte(field ? KIND_FIELD : KIND_METHOD);
        writeString(out, name);
        writeString(out, desc);
    }

    private static Metadata decode(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes);
        if (buffer.remaining() < 4) {
            throw truncated("it is shorter than the format version alone");
        }
        var format = buffer.getInt();
        if (format != FORMAT) {
            throw new PatchEngineException(
                "[Veltis] Class delta uses format " + format + ", which this build does not"
                    + " understand"
                    + "\n  Expected format version: " + FORMAT
                    + "\n  Actual format version:   " + format
                    + "\n  Reason: a delta in another format would have its member lists read"
                    + " from the wrong offsets, and a misread delta applies the wrong members"
                    + " to a verified class"
                    + "\n  Nothing has been applied."
                    + "\n  Fix: rebuild the patch set with a matching Veltis build.");
        }
        try {
            var minecraftVersion = readString(buffer);
            var target = readString(buffer);
            var vanilla = hex(read(buffer, HASH_BYTES));
            var patched = hex(read(buffer, HASH_BYTES));
            var shulker = hex(read(buffer, HASH_BYTES));
            var classPatch = hex(read(buffer, HASH_BYTES));
            var removedCount = unsignedShort(buffer);
            var removed = new ArrayList<MemberRef>(removedCount);
            for (var i = 0; i < removedCount; i++) {
                removed.add(readMember(buffer));
            }
            var widenedCount = unsignedShort(buffer);
            var widened = new ArrayList<WidenedMember>(widenedCount);
            for (var i = 0; i < widenedCount; i++) {
                var kind = buffer.get();
                if (kind != KIND_FIELD && kind != KIND_METHOD) {
                    throw new PatchEngineException(
                        "[Veltis] The class delta names member kind " + kind
                            + "\n  Reason: the format uses 0 for a field and 1 for a method; any"
                            + " other value would decide the merge on an unknown kind"
                            + "\n  Nothing has been applied.");
                }
                var name = readString(buffer);
                var desc = readString(buffer);
                widened.add(new WidenedMember(kind == KIND_FIELD, name, desc,
                    buffer.getInt()));
            }
            if (buffer.hasRemaining()) {
                throw truncated("it holds " + buffer.remaining() + " trailing bytes");
            }
            return new Metadata(format, minecraftVersion, target, vanilla, patched, shulker,
                classPatch, removed, widened);
        } catch (PatchEngineException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PatchEngineException(
                "[Veltis] The class delta's " + ATTRIBUTE_TYPE + " attribute is malformed"
                    + "\n  Reason: " + MojangMetadata.rootMessage(e)
                    + "\n  Nothing has been applied."
                    + "\n  Fix: regenerate the patch set.", e);
        }
    }

    private static PatchEngineException truncated(String because) {
        return new PatchEngineException(
            "[Veltis] The class delta's " + ATTRIBUTE_TYPE + " attribute is truncated: " + because
                + "\n  Reason: the payload was cut short or written by another format"
                + "\n  Nothing has been applied."
                + "\n  Fix: regenerate the patch set.");
    }

    private static String readString(ByteBuffer buffer) {
        var length = unsignedShort(buffer);
        return StandardCharsets.UTF_8.decode(read(buffer, length)).toString();
    }

    private static MemberRef readMember(ByteBuffer buffer) {
        var field = buffer.get();
        if (field != KIND_FIELD && field != KIND_METHOD) {
            throw new PatchEngineException(
                "[Veltis] The class delta names member kind " + field
                    + "\n  Reason: the format uses 0 for a field and 1 for a method; any"
                    + " other value would decide the merge on an unknown kind"
                    + "\n  Nothing has been applied.");
        }
        var name = readString(buffer);
        var desc = readString(buffer);
        return new MemberRef(field == KIND_FIELD, name, desc);
    }

    private static ByteBuffer read(ByteBuffer buffer, int count) {
        if (count < 0 || buffer.remaining() < count) {
            throw truncated("a field of " + count + " bytes does not fit");
        }
        var bytes = new byte[count];
        buffer.get(bytes);
        return ByteBuffer.wrap(bytes);
    }

    private static int unsignedShort(ByteBuffer buffer) {
        return buffer.getShort() & 0xFFFF;
    }

    private static String hex(ByteBuffer buffer) {
        var bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
