package de.geolykt.starloader.methodremover;

import java.util.HashSet;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public class ClassremoverVisitor extends ClassVisitor {

    protected final String annotation;
    protected final HashSet<Map.Entry<String, String>> methodBlackList = new HashSet<>();

    public ClassremoverVisitor(int api, ClassVisitor classVisitor, String annotation, byte[] original) {
        super(api, classVisitor);
        this.annotation = annotation;
        final ClassReader reader = new ClassReader(original);
        final ClassNode code = new ClassNode(api);
        reader.accept(code, 0);
        for (MethodNode method : code.methods) {
            boolean skip = false;
            for (AnnotationNode annotationNode : method.invisibleAnnotations) {
                System.out.println("Annotation found: " + annotationNode.desc);
                if (annotationNode.desc.equalsIgnoreCase(annotation)) {
                    System.out.println("Annotation matched for method " + method.name + " with descriptor " + method.desc);
                    methodBlackList.add(Map.entry(method.name, method.desc));
                    skip = true;
                    break;
                }
            }
            if (!skip) {
                for (AnnotationNode annotationNode : method.visibleAnnotations) {
                    System.out.println("Annotation found: " + annotationNode.desc);
                    if (annotationNode.desc.equalsIgnoreCase(annotation)) {
                        System.out.println("Annotation matched for method " + method.name + " with descriptor " + method.desc);
                        methodBlackList.add(Map.entry(method.name, method.desc));
                        skip = true;
                        break;
                    }
                }
            }
        }
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
            String[] exceptions) {
        if (methodBlackList.contains(Map.entry(name, descriptor))) {
            System.out.println("Stopped method from being processed: " + name + " with descriptor " + descriptor);
            return null;
        }
        return super.visitMethod(access, name, descriptor, signature, exceptions);
    }
}
