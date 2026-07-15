package org.veltismc.veltis;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public final class MixinAgent {

    private static volatile Instrumentation instrumentation;
    private static volatile MixinTransformerBridge transformerBridge = new MixinTransformerBridge();

    private MixinAgent() {}

    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className,
                Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                byte[] classfileBuffer) {
                if (classfileBuffer == null) return null;
                return transformerBridge.transform(className.replace('/', '.'), classfileBuffer);
            }
        }, true);
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    public static void wireTransformer() {
        var env = MixinEnvironment.getCurrentEnvironment();
        var transformer = (IMixinTransformer) env.getActiveTransformer();
        if (transformer != null) {
            transformerBridge.setDelegate(transformer);
            System.out.println("[Moonrise] Wired MixinTransformer to agent bridge");
        }
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    private static final class MixinTransformerBridge {
        private volatile IMixinTransformer delegate;

        void setDelegate(IMixinTransformer t) {
            this.delegate = t;
        }

        byte[] transform(String className, byte[] classfileBuffer) {
            if (delegate == null) return null;
            try {
                return delegate.transformClassBytes(className, className, classfileBuffer);
            } catch (Exception e) {
                System.err.println("[Moonrise] Mixin transform failed for " + className + ": " + e.getMessage());
                return null;
            }
        }
    }
}
