package org.veltismc.veltis.util;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.util.HashMap;
import java.util.Map;

public final class VeltisCommodore {

    private static final Map<String, String> CLASS_RENAMES = new HashMap<>(Map.ofEntries(
        Map.entry("org/bukkit/entity/Cow", "org/bukkit/entity/AbstractCow"),
        Map.entry("org/bukkit/entity/Slime", "org/bukkit/entity/AbstractCubeMob"),
        Map.entry("org/bukkit/entity/TextDisplay$TextAligment", "org/bukkit/entity/TextDisplay$TextAlignment"),
        Map.entry("org/spigotmc/event/entity/EntityMountEvent", "org/bukkit/event/entity/EntityMountEvent"),
        Map.entry("org/spigotmc/event/entity/EntityDismountEvent", "org/bukkit/event/entity/EntityDismountEvent"),
        Map.entry("org/bukkit/block/data/type/Crafter$Orientation", "org/bukkit/block/Orientation"),
        Map.entry("org/bukkit/block/data/type/Jigsaw$Orientation", "org/bukkit/block/Orientation"),
        Map.entry("org/bukkit/block/data/type/MossyCarpet$Height", "org/bukkit/block/data/type/Wall$Height"),
        Map.entry("org/bukkit/block/data/type/PinkPetals", "org/bukkit/block/data/type/FlowerBed"),
        Map.entry("org/bukkit/block/data/type/PointedDripstone", "org/bukkit/block/data/type/Speleothem"),
        Map.entry("org/bukkit/block/data/type/PointedDripstone$Thickness", "org/bukkit/block/data/type/Speleothem$Thickness")
    ));

    private static final Map<String, String> MODERN_MATERIAL_RENAMES = new HashMap<>(Map.ofEntries(
        Map.entry("CACTUS_GREEN", "GREEN_DYE"),
        Map.entry("DANDELION_YELLOW", "YELLOW_DYE"),
        Map.entry("ROSE_RED", "RED_DYE"),
        Map.entry("SIGN", "OAK_SIGN"),
        Map.entry("WALL_SIGN", "OAK_WALL_SIGN"),
        Map.entry("ZOMBIE_PIGMAN_SPAWN_EGG", "ZOMBIFIED_PIGLIN_SPAWN_EGG"),
        Map.entry("GRASS_PATH", "DIRT_PATH"),
        Map.entry("GRASS", "SHORT_GRASS"),
        Map.entry("SCUTE", "TURTLE_SCUTE"),
        Map.entry("CHAIN", "IRON_CHAIN")
    ));

    private static final Map<String, String> ART_RENAMES = Map.of(
        "BURNINGSKULL", "BURNING_SKULL",
        "DONKEYKONG", "DONKEY_KONG"
    );

    private static final Map<String, String> DYE_COLOR_RENAMES = Map.of(
        "SILVER", "LIGHT_GRAY"
    );

    public static byte[] process(byte[] classBytes, boolean modern) {
        ClassReader cr;
        try {
            cr = new ClassReader(classBytes);
        } catch (Exception e) {
            return classBytes;
        }
        ClassWriter cw = new ClassWriter(cr, 0);

        cr.accept(new ClassRemapper(new ClassVisitor(Opcodes.ASM9, cw) {

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(this.api, super.visitMethod(access, name, desc, signature, exceptions)) {

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                        if (owner.equals("org/bukkit/Material")) {
                            if (modern) {
                                var renamed = MODERN_MATERIAL_RENAMES.get(name);
                                if (renamed != null) name = renamed;
                            } else {
                                name = "LEGACY_" + name;
                            }
                        }
                        name = renameField(owner, name);
                        super.visitFieldInsn(opcode, owner, name, desc);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (owner.equals("org/bukkit/MapView") && name.equals("getId") && desc.equals("()S")) {
                            super.visitMethodInsn(opcode, owner, name, "()I", itf);
                            return;
                        }
                        if ((owner.equals("org/bukkit/Bukkit") || owner.equals("org/bukkit/Server")) && name.equals("getMap") && desc.equals("(S)Lorg/bukkit/map/MapView;")) {
                            super.visitMethodInsn(opcode, owner, name, "(I)Lorg/bukkit/map/MapView;", itf);
                            return;
                        }
                        if (owner.startsWith("org/bukkit") && desc.contains("org/bukkit/util/Consumer")) {
                            super.visitMethodInsn(opcode, owner, name, desc.replace("org/bukkit/util/Consumer", "java/util/function/Consumer"), itf);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, name, desc, itf);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String string && "com.mysql.jdbc.Driver".equals(string)) {
                            super.visitLdcInsn("com.mysql.cj.jdbc.Driver");
                            return;
                        }
                        super.visitLdcInsn(value);
                    }
                };
            }
        }, new SimpleRemapper(CLASS_RENAMES)), 0);

        return cw.toByteArray();
    }

    private static String renameField(String owner, String name) {
        if (owner.equals("org/bukkit/Art")) {
            var renamed = ART_RENAMES.get(name);
            return renamed != null ? renamed : name;
        }
        if (owner.equals("org/bukkit/DyeColor")) {
            var renamed = DYE_COLOR_RENAMES.get(name);
            return renamed != null ? renamed : name;
        }
        return name;
    }
}
