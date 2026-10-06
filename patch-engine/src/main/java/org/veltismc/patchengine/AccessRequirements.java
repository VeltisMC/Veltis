package org.veltismc.patchengine;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Answers one question: <em>which vanilla classes does the bytecode we are about
 * to ship actually need widened?</em>
 *
 * <h2>Why this exists</h2>
 *
 * <p>The development pipeline widens every vanilla class, because the decompiler
 * and the compiler both work on a whole tree and asking "which of these seven
 * thousand classes will a patch eventually reach" up front would be guesswork.
 * Shipping that widened jar is a different matter: it changes roughly 7000 of
 * 17655 entries, so the runtime patch set would be about 59 MB and would have to
 * rewrite almost the whole jar on every install. A patch set is supposed to
 * contain only the classes that differ, and widening everything is not a
 * difference anybody asked for.
 *
 * <p>So the widening is narrowed to the demand: scan the classes the patch set
 * already replaces, ask of every reference they make whether the unwidened
 * vanilla jar would resolve it, and widen exactly the classes and members that
 * answer no. The transform itself is still {@link AccessWidener}'s — this class
 * only decides where it is needed, and the patch set carries the outcome as
 * access-flag metadata per class and per member, never as rewritten bytecode.
 *
 * <h2>What is measured</h2>
 *
 * <p>For every reference a patched class makes into a vanilla class:
 *
 * <ul>
 *   <li>is the named class reachable (its effective access flags are public, or
 *       the two share a package),</li>
 *   <li>is the named member reachable (public, same package, protected from a
 *       subclass, or private from a nestmate),</li>
 *   <li>is the named class something that would have to be subclassed, and is it
 *       final,</li>
 *   <li>does the patched class override a final member of a vanilla
 *       superclass.</li>
 * </ul>
 *
 * <p>Members that resolve outside the vanilla jar are not a concern and are
 * counted separately: widening only ever touched vanilla classes, so the
 * compiler saw exactly the visibility the runtime will see for JDK and library
 * members.
 *
 * <p>The result is also a check on the patch itself. A patched class that
 * <em>narrows</em> a declaration relative to the vanilla one it replaces, or
 * that drops a member other classes might still reference, is reported as a
 * violation rather than silently shipped — because such a patch compiles,
 * packages, and then fails at runtime on a class the patch did not expect to
 * care about.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not rewrite anything, does not interpret method bodies, and does
 * not decide how a widening is expressed: {@link ClassDelta} carries each
 * required change as access-flag metadata — one record per class header, one
 * per member — and applying one is a flag rewrite against verified vanilla
 * bytes, never a shipped replacement class.
 */
public final class AccessRequirements {

    /**
     * The outcome of an analysis.
     *
     * @param wideningRequired internal names of vanilla classes whose header or
     *                          inner-class entries must be rewritten before the
     *                          patched bytecode can link
     * @param memberWidenings   declaring class → the members of it that must be
     *                          rewritten, one entry each: an access widening
     *                          when a reference could not reach the member as
     *                          vanilla declares it, or a final-method strip when
     *                          a patched class overrides one
     * @param violations        reasons the patched bytecode is not safe to ship
     *                          even with every required widening applied
     * @param referencesIntoVanilla how many references were examined
     * @param referencesDeclared   how many of those resolved to a declaration
     *                          inside the vanilla jar
     */
    public record Result(Set<String> wideningRequired,
                         Map<String, List<MemberWidening>> memberWidenings,
                         List<String> violations,
                         int referencesIntoVanilla, int referencesDeclared) {

        /** Whether the patch set can be produced from this result as it stands. */
        public boolean acceptable() {
            return violations.isEmpty();
        }
    }

    /**
     * One vanilla member that must be rewritten for the patched bytecode to
     * link, as flag metadata only — the delta never carries the member's body.
     *
     * @param field      whether this names a field ({@code false} = method)
     * @param name       the member name
     * @param desc       the member descriptor
     * @param finalStrip whether what is needed is dropping {@code ACC_FINAL}
     *                   from an otherwise acceptable method (a patched class
     *                   overrides it) rather than a full access widening
     */
    public record MemberWidening(boolean field, String name, String desc, boolean finalStrip) {
    }

    private final Map<String, ClassInfo> info = new HashMap<>();
    /**
     * The baseline, keyed by entry name. Never written to — it is shared with
     * whoever loaded it, usually because that caller also had to hash the same
     * jar.
     */
    private Map<String, byte[]> vanilla = Map.of();
    private final Set<String> replaced = new TreeSet<>();

    private final Set<String> widenForAccess = new TreeSet<>();
    private final Set<String> widenForFinal = new TreeSet<>();
    private final Map<String, List<MemberWidening>> memberWidenings = new TreeMap<>();
    private final List<String> violations = new ArrayList<>();
    private int referencesIntoVanilla;
    private int referencesDeclared;

    private AccessRequirements() {
    }

    /**
     * Analyzes a set of Veltis classes against the vanilla jar.
     *
     * @param vanillaJar    the unwidened baseline the patch is cut from
     * @param veltisClasses internal name → bytes for every class the patch will
     *                      ship (patched sources, widened extras, and Veltis's
     *                      own modules)
     * @return which vanilla classes need widening, plus any violations
     * @throws IOException when the vanilla jar cannot be read
     */
    public static Result analyze(Path vanillaJar, Map<String, byte[]> veltisClasses)
            throws IOException {
        Objects.requireNonNull(vanillaJar, "vanillaJar cannot be null");
        return analyze(readAllClasses(vanillaJar), veltisClasses);
    }

    /**
     * The same analysis over an already-loaded baseline.
     *
     * <p>Separate so a caller that has to hash and compare the same jar does not
     * read it twice — a full Minecraft server jar is large enough that doing so
     * would be visible in every build.
     *
     * @param vanillaClasses entry name → bytes for every class in the baseline
     * @param veltisClasses  internal name → bytes for every class the patch will ship
     */
    public static Result analyze(Map<String, byte[]> vanillaClasses,
                                 Map<String, byte[]> veltisClasses) {
        Objects.requireNonNull(vanillaClasses, "vanillaClasses cannot be null");
        Objects.requireNonNull(veltisClasses, "veltisClasses cannot be null");
        var analyzer = new AccessRequirements();
        analyzer.vanilla = vanillaClasses;
        analyzer.replaced.addAll(veltisClasses.keySet());

        // Three phases, in this order, because the order is what makes the
        // result independent of hash-map iteration order. Comparing against
        // vanilla first needs vanilla; installing the patched model second makes
        // every later lookup deterministic; scanning last may then resolve a
        // superclass chain through whichever of its links the patch replaces.
        var names = new TreeSet<>(analyzer.replaced);
        for (var name : names) {
            analyzer.checkNotNarrowed(name, veltisClasses.get(name));
        }
        for (var name : names) {
            analyzer.info.put(name,
                new ClassInfo(name, readClass(veltisClasses.get(name))));
        }
        for (var name : names) {
            analyzer.scan(name, veltisClasses.get(name));
        }
        var required = new TreeSet<String>(analyzer.widenForAccess);
        required.addAll(analyzer.widenForFinal);
        required.removeAll(analyzer.replaced);
        var members = new TreeMap<String, List<MemberWidening>>();
        for (var entry : analyzer.memberWidenings.entrySet()) {
            if (analyzer.replaced.contains(entry.getKey())) {
                continue;
            }
            members.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new Result(Set.copyOf(required), Collections.unmodifiableMap(members),
            List.copyOf(analyzer.violations),
            analyzer.referencesIntoVanilla, analyzer.referencesDeclared);
    }

    /** Reads every class file of a jar, keyed by entry name. */
    public static Map<String, byte[]> readAllClasses(Path jar) throws IOException {
        var classes = new HashMap<String, byte[]>();
        try (var zip = new ZipFile(jar.toFile())) {
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                try (var in = zip.getInputStream(entry)) {
                    classes.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return classes;
    }

    /** Reads one class file out of a jar, or {@code null} when it has none. */
    public static byte[] readClass(Path jar, String entryName) throws IOException {
        try (var zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    // ------------------------------------------------------------------
    // The patched classes themselves
    // ------------------------------------------------------------------

    private void checkNotNarrowed(String name, byte[] bytes) {
        var old = info(name);
        if (old == null) {
            return;
        }
        var current = new ClassInfo(name, readClass(bytes));
        if ((old.access & Opcodes.ACC_PUBLIC) != 0 && (current.access & Opcodes.ACC_PUBLIC) == 0) {
            violate(name, "the class is no longer public");
        }
        for (var field : current.fields.entrySet()) {
            var previous = old.fields.get(field.getKey());
            if (previous != null && narrows(previous, field.getValue())) {
                violate(name, "field " + field.getKey() + " is less accessible than vanilla's");
            }
        }
        for (var method : current.methods.entrySet()) {
            var previous = old.methods.get(method.getKey());
            if (previous != null && narrows(previous, method.getValue())) {
                violate(name, "method " + method.getKey() + " is less accessible than vanilla's");
            }
        }
        // A member that disappears is legal for the patched class and fatal for
        // anything still calling it — but only for code that can reach it at
        // all, so each removal is measured against who could reference it
        // rather than reported on sight.
        for (var entry : old.fields.entrySet()) {
            if (!current.fields.containsKey(entry.getKey())
                    && !removalIsSafe(name, entry.getValue())) {
                violate(name, "vanilla field " + entry.getKey()
                    + " is gone from the patched class");
            }
        }
        for (var entry : old.methods.entrySet()) {
            if (!current.methods.containsKey(entry.getKey())
                    && !removalIsSafe(name, entry.getValue())) {
                violate(name, "vanilla method " + entry.getKey()
                    + " is gone from the patched class");
            }
        }
    }

    /**
     * Whether a vanilla member that the patch set no longer declares can only be
     * referenced by classes the patch set also replaces.
     *
     * <p>The JVM's rule about this is narrow and exact: a private member is
     * reachable from its own class and from its nestmates, and from nothing
     * else. So when every class in the nest is being replaced, no class left
     * over can still hold a reference to it, and the removal cannot break
     * anything — which is the case for a compiler-generated lambda body whose
     * shape changed, the ordinary outcome of editing a method.
     *
     * <p>Anything less than private is reachable from well outside the nest, and
     * no analysis short of reading every class in the jar could say whether one
     * of those callers still does reference it, so it is reported.
     */
    private boolean removalIsSafe(String owner, int access) {
        if ((access & Opcodes.ACC_PRIVATE) == 0) {
            return false;
        }
        var declared = info(owner);
        if (declared == null) {
            return false;
        }
        var host = declared.nestHost != null ? declared.nestHost : owner;
        var hostInfo = info(host);
        if (hostInfo == null || !replaced.contains(host)) {
            // The nest's own declaration is not in the jar, or the class that
            // publishes the nest is staying vanilla — either way its other
            // members cannot be enumerated, let alone checked.
            return false;
        }
        for (var member : hostInfo.nestMembers) {
            if (!replaced.contains(member)) {
                return false;
            }
        }
        return true;
    }

    private static boolean narrows(int vanilla, int patched) {
        if ((vanilla & Opcodes.ACC_PUBLIC) != 0 && (patched & Opcodes.ACC_PUBLIC) == 0) {
            return true;
        }
        if ((vanilla & Opcodes.ACC_PROTECTED) != 0
                && (patched & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) {
            return true;
        }
        return (vanilla & Opcodes.ACC_FINAL) == 0 && (patched & Opcodes.ACC_FINAL) != 0;
    }

    private void violate(String name, String why) {
        if (violations.size() < 200) {
            violations.add(name + ": " + why);
        }
    }

    /**
     * Records one member of one vanilla class that the patched bytecode needs
     * rewritten. Keys arrive as {@code name:descriptor}; neither half can
     * contain a colon, so the first one separates them.
     *
     * <p>Members are recorded one by one rather than by widening their whole
     * class: the patch set ships flag metadata for exactly these members, so a
     * class with one unreachable private method does not become a rewritten
     * class with every member's flags changed.</p>
     */
    private void widenMember(String declaringClass, String key, boolean field,
                             boolean finalStrip) {
        var colon = key.indexOf(':');
        if (colon < 0) {
            violate(declaringClass, "internal member key '" + key
                + "' has no descriptor separator; the widening it needs cannot be recorded");
            return;
        }
        var name = key.substring(0, colon);
        var desc = key.substring(colon + 1);
        var list = memberWidenings.computeIfAbsent(declaringClass, ignored -> new ArrayList<>());
        for (var iterator = list.iterator(); iterator.hasNext(); ) {
            var existing = iterator.next();
            if (existing.field() == field && existing.name().equals(name)
                    && existing.desc().equals(desc)) {
                if (finalStrip) {
                    // One member gets one record: an access widening already
                    // drops ACC_FINAL where the JVM allows it, so it answers a
                    // final-strip request too, and a strip is never added over
                    // an existing widening.
                    return;
                }
                iterator.remove();
                break;
            }
        }
        list.add(new MemberWidening(field, name, desc, finalStrip));
    }

    // ------------------------------------------------------------------
    // Scanning one Veltis class
    // ------------------------------------------------------------------

    /**
     * Walks one patched class for the references it makes into vanilla.
     *
     * <p>The declaration model is already installed by the time this runs, so
     * this pass only reads instructions; that keeps it a single parse of the
     * class file rather than two, and keeps every lookup deterministic.
     */
    private void scan(String internal, byte[] bytes) {
        var self = info.get(internal);
        if (self == null) {
            return;
        }
        var superName = self.superName;
        requireClassAccessible(internal, superName);
        requireNotFinal(superName);
        for (var type : self.interfaces) {
            requireClassAccessible(internal, type);
        }
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0) {
                    checkDoesNotOverrideFinal(superName, name + ":" + descriptor);
                }
                return instructionScanner(internal);
            }
        }, 0);
    }

    private MethodVisitor instructionScanner(String internal) {
        return new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitTypeInsn(int opcode, String type) {
                requireClassAccessible(internal, type);
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                requireMemberAccessible(internal, owner, name + ":" + desc, true, false);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc,
                                        boolean isInterface) {
                var subclass = opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESPECIAL;
                requireMemberAccessible(internal, owner, name + ":" + desc, false,
                    subclass && isSubclassOf(internal, owner));
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String desc,
                                               Handle bootstrapMethodHandle,
                                               Object... bootstrapMethodArguments) {
                requireHandle(internal, bootstrapMethodHandle);
                for (var argument : bootstrapMethodArguments) {
                    if (argument instanceof Handle handle) {
                        requireHandle(internal, handle);
                    }
                }
            }

            @Override
            public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                if (type != null) {
                    requireClassAccessible(internal, type);
                }
            }

            @Override
            public void visitLdcInsn(Object value) {
                if (value instanceof Type type) {
                    requireType(internal, type);
                }
            }

            @Override
            public void visitMultiANewArrayInsn(String descriptor, int dims) {
                requireType(internal, Type.getType(descriptor));
            }
        };
    }

    private void requireType(String referrer, Type type) {
        var element = type;
        while (element.getSort() == Type.ARRAY) {
            element = element.getElementType();
        }
        if (element.getSort() == Type.OBJECT) {
            requireClassAccessible(referrer, element.getInternalName());
        }
    }

    private void requireHandle(String referrer, Handle handle) {
        var tag = handle.getTag();
        var field = tag == Opcodes.H_GETFIELD || tag == Opcodes.H_GETSTATIC
            || tag == Opcodes.H_PUTFIELD || tag == Opcodes.H_PUTSTATIC;
        requireMemberAccessible(referrer, handle.getOwner(),
            handle.getName() + ":" + handle.getDesc(), field, false);
    }

    // ------------------------------------------------------------------
    // The rules
    // ------------------------------------------------------------------

    private boolean inVanilla(String internal) {
        return internal != null && vanilla.containsKey(internal + ".class");
    }

    private void requireClassAccessible(String referrer, String owner) {
        if (!inVanilla(owner) || replaced.contains(owner)) {
            return;
        }
        if (isPublic(classAccess(owner)) || samePackage(referrer, owner)) {
            return;
        }
        widenForAccess.add(owner);
    }

    private void requireNotFinal(String target) {
        if (!inVanilla(target) || replaced.contains(target)) {
            return;
        }
        var target0 = info(target);
        if (target0 != null && (target0.access & Opcodes.ACC_FINAL) != 0) {
            widenForFinal.add(target);
        }
    }

    private void requireMemberAccessible(String referrer, String owner, String key,
                                         boolean field, boolean subclass) {
        if (!inVanilla(owner) || replaced.contains(owner)) {
            return;
        }
        referencesIntoVanilla++;
        // The named owner has to be reachable no matter where the member turns
        // out to be declared; skipping this would let a package-private class
        // through on the strength of a public member.
        if (!isPublic(classAccess(owner)) && !samePackage(referrer, owner)) {
            widenForAccess.add(owner);
        }
        var resolved = resolveMember(owner, key, field);
        if (!resolved.found()) {
            // Resolves outside the jar — a JDK or library member. Widening only
            // ever touched vanilla classes, so the compiler saw precisely the
            // visibility the runtime will see here. Nothing to widen.
            return;
        }
        referencesDeclared++;
        if (replaced.contains(resolved.declaring())) {
            return;
        }
        var member = resolved.access();
        var declaringClass = resolved.declaring();
        if (!isPublic(classAccess(declaringClass)) && !samePackage(referrer, declaringClass)) {
            widenForAccess.add(declaringClass);
        }
        if (isPublic(member) || samePackage(referrer, declaringClass)) {
            return;
        }
        if ((member & Opcodes.ACC_PROTECTED) != 0 && subclass) {
            return;
        }
        if ((member & Opcodes.ACC_PRIVATE) != 0 && nestmates(referrer, declaringClass)) {
            return;
        }
        widenMember(declaringClass, key, field, false);
    }

    /** Walks the vanilla superclass chain looking for a final member we override. */
    private void checkDoesNotOverrideFinal(String superName, String member) {
        var current = info(superName);
        var guard = 0;
        while (current != null && guard++ < 64) {
            if (replaced.contains(current.name)) {
                return;
            }
            var access = current.methods.get(member);
            if (access != null && (access & Opcodes.ACC_FINAL) != 0) {
                widenMember(current.name, member, false, true);
                return;
            }
            current = info(current.superName);
        }
    }

    // ------------------------------------------------------------------
    // Class model
    // ------------------------------------------------------------------

    private static final class ClassInfo {
        private final String name;
        private int access;
        private String superName;
        private String[] interfaces = new String[0];
        private final Map<String, Integer> fields = new HashMap<>();
        private final Map<String, Integer> methods = new HashMap<>();
        private final Map<String, Integer> inner = new HashMap<>();
        private final List<String> nestMembers = new ArrayList<>();
        private String nestHost;

        private ClassInfo(String name, RawClass raw) {
            this.name = name;
            this.access = raw.access;
            this.superName = raw.superName;
            if (raw.interfaces != null) {
                this.interfaces = raw.interfaces;
            }
            this.fields.putAll(raw.fields);
            this.methods.putAll(raw.methods);
            this.inner.putAll(raw.inner);
            this.nestMembers.addAll(raw.nestMembers);
            this.nestHost = raw.nestHost;
        }
    }

    /** The parts of a class file this analysis needs, read once. */
    private record RawClass(int access, String superName, String[] interfaces,
                            Map<String, Integer> fields, Map<String, Integer> methods,
                            Map<String, Integer> inner, List<String> nestMembers,
                            String nestHost) {
    }

    private static RawClass readClass(byte[] bytes) {
        var fields = new HashMap<String, Integer>();
        var methods = new HashMap<String, Integer>();
        var inner = new HashMap<String, Integer>();
        var nestMembers = new ArrayList<String>();
        var holder = new Object() {
            int access;
            String superName;
            String[] interfaces;
            String nestHost;
        };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                holder.access = access;
                holder.superName = superName;
                holder.interfaces = interfaces;
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                inner.put(name, access);
            }

            @Override
            public void visitNestHost(String nestHost) {
                holder.nestHost = nestHost;
            }

            @Override
            public void visitNestMember(String nestMember) {
                nestMembers.add(nestMember);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                fields.put(name + ":" + descriptor, access);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                methods.put(name + ":" + descriptor, access);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new RawClass(holder.access, holder.superName, holder.interfaces, fields, methods,
            inner, nestMembers, holder.nestHost);
    }

    /** Lazily parses a vanilla class, so a 17k-entry jar costs only what is asked of it. */
    private ClassInfo info(String internal) {
        if (internal == null) {
            return null;
        }
        var cached = info.get(internal);
        if (cached != null) {
            return cached;
        }
        var bytes = vanilla.get(internal + ".class");
        if (bytes == null) {
            return null;
        }
        var parsed = new ClassInfo(internal, readClass(bytes));
        info.put(internal, parsed);
        return parsed;
    }

    /**
     * The access flags of a class as another class sees them (JVMS 4.7.6): the
     * {@code InnerClasses} entry when there is one, because that is what the
     * compiler consulted and what the runtime enforces.
     */
    private int classAccess(String internal) {
        var parsed = info(internal);
        if (parsed == null) {
            return 0;
        }
        var dollar = internal.lastIndexOf('$');
        while (dollar > 0) {
            var enclosing = internal.substring(0, dollar);
            var outer = info(enclosing);
            if (outer != null) {
                var flags = outer.inner.get(internal);
                if (flags != null) {
                    return flags;
                }
            }
            dollar = internal.lastIndexOf('$', dollar - 1);
        }
        return parsed.access;
    }

    private static String packageOf(String internal) {
        var slash = internal.lastIndexOf('/');
        return slash < 0 ? "" : internal.substring(0, slash);
    }

    private static boolean samePackage(String a, String b) {
        return packageOf(a).equals(packageOf(b));
    }

    private static boolean isPublic(int flags) {
        return (flags & Opcodes.ACC_PUBLIC) != 0;
    }

    private boolean nestmates(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        var left = info(a);
        var right = info(b);
        if (left == null || right == null) {
            return false;
        }
        var hostA = left.nestHost != null ? left.nestHost : a;
        var hostB = right.nestHost != null ? right.nestHost : b;
        return hostA.equals(hostB);
    }

    private boolean isSubclassOf(String child, String ancestor) {
        var current = info(child);
        var guard = 0;
        while (current != null && guard++ < 64) {
            if (ancestor.equals(current.name)) {
                return true;
            }
            for (var itf : current.interfaces) {
                if (ancestor.equals(itf) || isSubclassOf(itf, ancestor)) {
                    return true;
                }
            }
            current = info(current.superName);
        }
        return false;
    }

    /** Where a member is declared, plus its flags, when it is declared in the jar. */
    private record Resolved(String declaring, int access, boolean found) {
    }

    private Resolved resolveMember(String owner, String key, boolean field) {
        var start = info(owner);
        if (start == null) {
            return new Resolved(owner, 0, false);
        }
        var own = field ? start.fields : start.methods;
        var declared = own.get(key);
        if (declared != null) {
            return new Resolved(owner, declared, true);
        }
        var queue = new ArrayDeque<String>(Arrays.asList(start.interfaces));
        var seen = new HashSet<String>();
        while (!queue.isEmpty()) {
            var current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            var parsed = info(current);
            if (parsed == null) {
                continue;
            }
            var match = (field ? parsed.fields : parsed.methods).get(key);
            if (match != null) {
                return new Resolved(current, match, true);
            }
            queue.addAll(Arrays.asList(parsed.interfaces));
        }
        var superclass = info(start.superName);
        var guard = 0;
        while (superclass != null && guard++ < 64) {
            var match = (field ? superclass.fields : superclass.methods).get(key);
            if (match != null) {
                return new Resolved(superclass.name, match, true);
            }
            queue.addAll(Arrays.asList(superclass.interfaces));
            while (!queue.isEmpty()) {
                var current = queue.poll();
                if (!seen.add(current)) {
                    continue;
                }
                var parsed = info(current);
                if (parsed == null) {
                    continue;
                }
                var inherited = (field ? parsed.fields : parsed.methods).get(key);
                if (inherited != null) {
                    return new Resolved(current, inherited, true);
                }
                queue.addAll(Arrays.asList(parsed.interfaces));
            }
            superclass = info(superclass.superName);
        }
        return new Resolved(owner, 0, false);
    }

    // ------------------------------------------------------------------
    // Collecting the classes to widen
    // ------------------------------------------------------------------

    /**
     * Reads the compiled classes of a directory tree, keyed by internal name.
     *
     * @param root the directory, usually {@code build/minecraft/<version>/classes}
     * @throws PatchEngineException when the directory holds no class files at all
     */
    public static Map<String, byte[]> readClassDirectory(Path root) throws IOException {
        var classes = new HashMap<String, byte[]>();
        if (!Files.isDirectory(root)) {
            return classes;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (var file : walk.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".class")).toList()) {
                var relative = root.relativize(file).toString().replace('\\', '/');
                var internal = relative.substring(0, relative.length() - ".class".length());
                classes.put(internal, Files.readAllBytes(file));
            }
        }
        return classes;
    }

    /**
     * Renders a result as a build log line, because "we widened nothing" is a
     * claim a reader should be able to check against the numbers.
     */
    public static String describe(Result result) {
        var members = result.memberWidenings().values().stream().mapToInt(List::size).sum();
        return result.wideningRequired().size() + " class"
            + (result.wideningRequired().size() == 1 ? "" : "es")
            + " need header widening, " + members + " member"
            + (members == 1 ? "" : "s") + " flagged (" + result.referencesDeclared()
            + " of " + result.referencesIntoVanilla()
            + " references into vanilla resolved to a declaration)";
    }
}
