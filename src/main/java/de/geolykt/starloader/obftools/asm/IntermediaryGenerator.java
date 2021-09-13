package de.geolykt.starloader.obftools.asm;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import de.geolykt.starloader.obftools.asm.remapper.Remapper;

class ClassNodeNameComparator implements Comparator<ClassNode> {

    public static final ClassNodeNameComparator INSTANCE = new ClassNodeNameComparator();

    @Override
    public int compare(ClassNode o1, ClassNode o2) {
        int len1 = o1.name.length();
        int len2 = o2.name.length();
        if (len1 == len2) {
            return o1.name.compareTo(o2.name);
        } else {
            return len1 - len2;
        }
    }
} public class IntermediaryGenerator {

    private final File map;
    private final List<ClassNode> nodes = new ArrayList<>();
    private final File output;

    private final Remapper remapper = new Remapper();
    private final List<Map.Entry<String, byte[]>> resources = new ArrayList<>();

    public IntermediaryGenerator(@Nullable File map, File output, @Nullable Collection<ClassNode> nodes) {
        this.map = map;
        this.output = output;
        if (nodes != null) {
            this.nodes.addAll(nodes);
        }
    }

    public IntermediaryGenerator(File input, File map, File output) {
        this(map, output, (Collection<ClassNode>) null);
        try {
            JarFile inJar = new JarFile(input);
            Enumeration<JarEntry> entries = inJar.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                InputStream is = inJar.getInputStream(entry);
                if (!entry.getName().endsWith(".class")) {
                    resources.add(Map.entry(entry.getName(), is.readAllBytes()));
                    is.close();
                    continue;
                }
                ClassNode node = new ClassNode(Opcodes.ASM9);
                ClassReader reader = new ClassReader(is);
                reader.accept(node, 0);
                nodes.add(node);
                is.close();
            }
            inJar.close();
            remapper.addTargets(nodes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Adds resources from a jar file at a given location.
     * This is used for the {@link #deobfuscate()} operation if and only if an output folder was chosen.
     *
     * @param input The file to scan for resources
     * @throws IOException if an IO issue occurred
     */
    public void addResources(@NotNull File input) throws IOException {
        try (JarFile inJar = new JarFile(input)) {
            Enumeration<JarEntry> entries = inJar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    InputStream is = inJar.getInputStream(entry);
                    resources.add(Map.entry(entry.getName(), is.readAllBytes()));
                    is.close();
                    continue;
                }
            }
            inJar.close();
            remapper.addTargets(nodes);
        }
    }

    private String createString(int counter) {
        if (counter > 25) {
            int first = counter / 26;
            int second = counter % 26;
            return String.valueOf(new char[] {(char) ('`' + first), (char) ('a' + second)});
        } else {
            return String.valueOf((char) ('a' + counter));
        }
    }


    public void deobfuscate() {
        remapper.process();
        if (output != null) {
            try (JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(output))) {
                for (ClassNode node : nodes) {
                    ClassWriter writer = new ClassWriter(0);
                    node.accept(writer);
                    jarOut.putNextEntry(new ZipEntry(node.name + ".class"));
                    jarOut.write(writer.toByteArray());
                    jarOut.closeEntry();
                }
                for (Map.Entry<String, byte[]> resource : resources) {
                    jarOut.putNextEntry(new ZipEntry(resource.getKey()));
                    jarOut.write(resource.getValue());
                    jarOut.closeEntry();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Proposes new field names within enum class that can be easily guessed by the computer.
     */
    public void doProposeEnumFieldsV2() {
        BufferedWriter bw = null;
        if (map != null) {
            try {
                @SuppressWarnings("resource")
                BufferedWriter dontcomplain = new BufferedWriter(new FileWriter(map, StandardCharsets.UTF_8, true));
                bw = dontcomplain;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        // We share this map instance for performance reasons (TM)
        Map<String, FieldNode> memberNames = new HashMap<>();
        for (ClassNode node : nodes) {
            if (node.superName.equals("java/lang/Enum")) {
                memberNames.clear();
                String expectedDesc = 'L' + node.name + ';';
                for (FieldNode field : node.fields) {
                    if (!field.desc.equals(expectedDesc)) {
                        continue;
                    }
                    memberNames.put(field.name, field);
                }
                for (MethodNode method : node.methods) {
                    if (method.name.equals("<clinit>")) {
                        AbstractInsnNode instruction = method.instructions.getFirst();
                        while (instruction != null) {
                            if (instruction.getOpcode() == Opcodes.NEW) {
                                TypeInsnNode newCall = (TypeInsnNode) instruction;
                                instruction = newCall.getNext();
                                if (instruction == null || instruction.getOpcode() != Opcodes.DUP) {
                                    break;
                                }
                                instruction = instruction.getNext();
                                if (instruction == null || instruction.getOpcode() != Opcodes.LDC) {
                                    break;
                                }
                                LdcInsnNode enumName = (LdcInsnNode) instruction;
                                if (!(enumName.cst instanceof String)) {
                                    continue;
                                }
                                instruction = instruction.getNext();
                                if (instruction == null) {
                                    break;
                                }
                                // SIPUSH or whatever, not relevant
                                instruction = instruction.getNext();
                                if (instruction == null) {
                                    break;
                                }
                                // other args for the constructor
                                AbstractInsnNode formerInsn = instruction;
                                while (instruction != null && (instruction.getOpcode() != Opcodes.INVOKESPECIAL || !((MethodInsnNode) instruction).owner.equals(newCall.desc))) {
                                    instruction = instruction.getNext();
                                }
                                if (instruction == null) {
                                    instruction = formerInsn;
                                    continue;
                                }
                                if (!((MethodInsnNode) instruction).name.equals("<init>")) {
                                    instruction = formerInsn;
                                    continue;
                                }
                                instruction = instruction.getNext();
                                if (instruction.getOpcode() != Opcodes.PUTSTATIC) {
                                    instruction = formerInsn;
                                    continue;
                                }
                                FieldInsnNode field = (FieldInsnNode) instruction;
                                if (!field.owner.equals(node.name) || !field.desc.equals(expectedDesc) || !memberNames.containsKey(field.name)) {
                                    instruction = formerInsn;
                                    continue;
                                }
                                if (field.name.equals(enumName.cst)) {
                                    continue;
                                }
                                if (bw != null) {
                                    try {
                                        bw.write("FIELD\t");
                                        bw.write(node.name);
                                        bw.write('\t');
                                        bw.write(expectedDesc);
                                        bw.write('\t');
                                        bw.write(field.name);
                                        bw.write('\t');
                                        bw.write(enumName.cst.toString());
                                        bw.write('\n');
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                                remapper.remapField(node.name, expectedDesc, field.name, enumName.cst.toString());
                                continue;
                            }
                            instruction = instruction.getNext();
                        }
                    }
                }
            }
        }
        if (bw != null) {
            try {
                bw.flush();
                bw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public List<ClassNode> getAsClassNodes() {
        return nodes;
    }

    private void remapClass(String oldName, String newName, BufferedWriter bw) {
        remapper.remapClassName(oldName, newName);
        if (bw != null) {
            try {
                bw.write("CLASS\t");
                bw.write(oldName);
                bw.write('\t');
                bw.write(newName);
                bw.write('\n'); // The tiny format does not make use of system-dependent newlines
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void remapClassesV2() {
        BufferedWriter bw = null;
        if (map != null) {
            try {
                @SuppressWarnings("resource")
                BufferedWriter dontcomplain = new BufferedWriter(new FileWriter(map, StandardCharsets.UTF_8, false));
                bw = dontcomplain;
                bw.write("v1\tofficial\tintermediary\n");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        Map<String, TreeSet<ClassNode>> remappedEnums = new HashMap<>();
        Map<String, TreeSet<ClassNode>> remappedInterfaces = new HashMap<>();
        Map<String, TreeSet<ClassNode>> remappedInners = new HashMap<>();
        Map<String, TreeSet<ClassNode>> remappedLocals = new HashMap<>();
        Map<String, TreeSet<ClassNode>> remappedPrivateClasses = new HashMap<>();
        Map<String, TreeSet<ClassNode>> remappedProtectedClasses = new HashMap<>();
        Map<String, TreeSet<ClassNode>> remappedPublicClasses = new HashMap<>();
        for (ClassNode node : nodes) {
            int lastSlash = node.name.lastIndexOf('/');
            String className = node.name.substring(lastSlash + 1);
            String packageName = node.name.substring(0, lastSlash);
            if (packageName.startsWith("org/hamcrest") || packageName.startsWith("org/lwjgl")) {
                // the three packages contain classes that should not be remapped
                continue;
            }
            if (className.length() < 3) {
                if ("java/lang/Enum".equals(node.superName)) {
                    TreeSet<ClassNode> remapSet = remappedEnums.get(packageName);
                    if (remapSet == null) {
                        remapSet = new TreeSet<>(ClassNodeNameComparator.INSTANCE);
                        remappedEnums.put(packageName, remapSet);
                    }
                    remapSet.add(node);
                } else if (node.outerClass != null) {
                    if (node.outerMethod == null) {
                        TreeSet<ClassNode> remapSet = remappedInners.get(packageName);
                        if (remapSet == null) {
                            remapSet = new TreeSet<>(ClassNodeNameComparator.INSTANCE);
                            remappedInners.put(packageName, remapSet);
                        }
                        remapSet.add(node);
                    } else {
                        TreeSet<ClassNode> remapSet = remappedLocals.get(packageName);
                        if (remapSet == null) {
                            remapSet = new TreeSet<>(ClassNodeNameComparator.INSTANCE);
                            remappedLocals.put(packageName, remapSet);
                        }
                        remapSet.add(node);
                    }
                } else if ((node.access & Opcodes.ACC_INTERFACE) != 0) {
                    TreeSet<ClassNode> remapSet = remappedInterfaces.get(packageName);
                    if (remapSet == null) {
                        remapSet = new TreeSet<>(ClassNodeNameComparator.INSTANCE);
                        remappedInterfaces.put(packageName, remapSet);
                    }
                    remapSet.add(node);
                } else if ((node.access & Opcodes.ACC_PUBLIC) != 0) {
                    TreeSet<ClassNode> remapSet = remappedPublicClasses.get(packageName);
                    if (remapSet == null) {
                        remapSet = new TreeSet<>(ClassNodeNameComparator.INSTANCE);
                        remappedPublicClasses.put(packageName, remapSet);
                    }
                    remapSet.add(node);
                } else if ((node.access & Opcodes.ACC_PROTECTED) != 0) {
                    TreeSet<ClassNode> remapSet = remappedProtectedClasses.get(packageName);
                    if (remapSet == null) {
                        remapSet = new TreeSet<>(ClassNodeNameComparator.INSTANCE);
                        remappedProtectedClasses.put(packageName, remapSet);
                    }
                    remapSet.add(node);
                } else {
                    TreeSet<ClassNode> remapSet = remappedPrivateClasses.get(packageName);
                    if (remapSet == null) {
                        remapSet = new TreeSet<>(ClassNodeNameComparator.INSTANCE);
                        remappedPrivateClasses.put(packageName, remapSet);
                    }
                    remapSet.add(node);
                }
            }
        }

        remapSet(remappedEnums, bw, "enum_");
        remapSet(remappedInterfaces, bw, "interface_");
        remapSet(remappedInners, bw, "innerclass_");
        remapSet(remappedLocals, bw, "localclass_");
        remapSet(remappedPublicClasses, bw, "class_");
        remapSet(remappedProtectedClasses, bw, "pclass_"); // protected class
        remapSet(remappedPrivateClasses, bw, "ppclass_"); // package-private class

        if (bw != null) {
            try {
                bw.flush();
                bw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void remapGetters() {
        BufferedWriter bw = null;
        if (map != null) {
            try {
                @SuppressWarnings("resource")
                BufferedWriter dontcomplain = new BufferedWriter(new FileWriter(map, StandardCharsets.UTF_8, true));
                bw = dontcomplain;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        Map<FieldReference, Optional<MethodNode>> getters = new LinkedHashMap<>(nodes.size());
        HashMap<String, ClassNode> name2Node = new HashMap<>(nodes.size());
        HashMap<String, List<String>> directSubtypes = new HashMap<>(nodes.size());

        for (ClassNode node : nodes) {
            name2Node.put(node.name, node);
            if ((node.access & Opcodes.ACC_FINAL) == 0) {
                directSubtypes.put(node.name, new ArrayList<>());
            }
            for (MethodNode method : node.methods) {
                if (method.name.length() > 2) {
                    // unlikely to be obfuscated
                    continue;
                }
                if (method.desc.codePointAt(1) != ')') {
                    // getter methods must be no-args methods
                    continue;
                }
                AbstractInsnNode insn = method.instructions.getFirst();
                if (insn == null) {
                    continue;
                }
                while ((insn instanceof FrameNode || insn instanceof LineNumberNode || insn instanceof LabelNode)) {
                    insn = insn.getNext();
                }
                if ((method.access & Opcodes.ACC_STATIC) == 0 && insn instanceof VarInsnNode && ((VarInsnNode) insn).var == 0) {
                    insn = insn.getNext();
                }
                if (insn.getOpcode() == Opcodes.GETSTATIC || insn.getOpcode() == Opcodes.GETFIELD) {
                    FieldInsnNode getField = (FieldInsnNode) insn;
                    insn = insn.getNext();
                    while ((insn instanceof FrameNode || insn instanceof LineNumberNode)) {
                        insn = insn.getNext();
                    }
                    if (!(insn instanceof InsnNode)) {
                        continue;
                    }
                    if (insn.getOpcode() != Opcodes.ARETURN
                            && insn.getOpcode() != Opcodes.IRETURN
                            && insn.getOpcode() != Opcodes.DRETURN
                            && insn.getOpcode() != Opcodes.FRETURN
                            && insn.getOpcode() != Opcodes.LRETURN) {
                        continue;
                    }
                    if (!getField.owner.equals(node.name)) {
                        continue;
                    }
                    FieldReference fref = new FieldReference(getField);
                    if (getters.containsKey(fref)) {
                        getters.put(fref, Optional.empty());
                    } else {
                        getters.put(fref, Optional.of(method));
                    }
                }
            }
        }

        for (ClassNode node : nodes) {
            List<String> a = directSubtypes.get(node.superName);
            if (a != null) {
                a.add(node.name);
            }
            for (String interfaceName : node.interfaces) {
                a = directSubtypes.get(interfaceName);
                if (a != null) {
                    a.add(node.name);
                }
            }
        }

        // propagate changes through that hierarchy
        Map<MethodNode, Set<String>> hierarchicalGetters = new LinkedHashMap<>(getters.size());

        for (Map.Entry<FieldReference, Optional<MethodNode>> entry : getters.entrySet()) {
            if (entry.getValue().isPresent()) {
                MethodNode method = entry.getValue().get();
                if ((method.access & Opcodes.ACC_STATIC) != 0) {
                    hierarchicalGetters.put(method, Set.of(entry.getKey().getOwner()));
                    continue;
                }
                OverrideScope scope = OverrideScope.fromFlags(method.access);
                FieldReference ref = entry.getKey();
                ClassNode curr = name2Node.get(ref.getOwner());
                String pkg = curr.name.substring(0, curr.name.lastIndexOf('/'));

                List<ClassNode> specifiers = getSpecifiyingClasses(method, curr, name2Node);
                for (ClassNode node : specifiers) {
                    propagateMethodRename(ref, method, name2Node, directSubtypes, node, hierarchicalGetters, scope, pkg);
                }
            }
        }

        StringBuilder sharedBuilder = new StringBuilder();
        for (Map.Entry<MethodNode, Set<String>> entry : hierarchicalGetters.entrySet()) {
            MethodNode method = entry.getKey();
            FieldInsnNode field = null;
            AbstractInsnNode insn = method.instructions.getFirst();
            while (insn != null) {
                if (insn instanceof FieldInsnNode) {
                    field = (FieldInsnNode) insn;
                    break;
                }
                insn = insn.getNext();
            }
            if (field == null) {
                throw new NullPointerException();
            }
            for (String clazz : entry.getValue()) {
                sharedBuilder.setLength(0);
                if (field.name.length() > 2) {
                    sharedBuilder.append("get");
                    sharedBuilder.appendCodePoint(Character.toUpperCase(field.name.codePointAt(0)));
                    sharedBuilder.append(field.name.substring(1));
                } else {
                    sharedBuilder.append("get_");
                    sharedBuilder.append(field.name);
                }
                String newName = sharedBuilder.toString();
                if (bw != null) {
                    try {
                        bw.write("METHOD\t");
                        bw.write(clazz);
                        bw.write('\t');
                        bw.write(method.desc);
                        bw.write('\t');
                        bw.write(method.name);
                        bw.write('\t');
                        bw.write(newName);
                        bw.write('\n');
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                remapper.remapMethod(clazz, method.desc, method.name, newName);
            }
        }

        if (bw != null) {
            try {
                bw.flush();
                bw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void collectParents(ClassNode node, Map<String, ClassNode> lookup, Set<ClassNode> out, boolean interfacesOnly) {
        if ((node.access & Opcodes.ACC_INTERFACE) == 0) {
            ClassNode superClass = lookup.get(node.superName);
            if (superClass != null) {
                if (!interfacesOnly) {
                    out.add(superClass);
                }
                collectParents(superClass, lookup, out, interfacesOnly);
            }
        }
        for (String itf : node.interfaces) {
            ClassNode interfaceNode = lookup.get(itf);
            if (interfaceNode != null) {
                out.add(interfaceNode);
                collectParents(interfaceNode, lookup, out, interfacesOnly);
            }
        }
    }

    private static List<ClassNode> getSpecifiyingClasses(MethodNode implMethod, ClassNode implClass, Map<String, ClassNode> nodeLookup) {
        Set<ClassNode> nodes = new LinkedHashSet<>();
        collectParents(implClass, nodeLookup, nodes, false);
        List<ClassNode> returnedNodes = new ArrayList<>();
        boolean isStatic = (implMethod.access & Opcodes.ACC_STATIC) != 0;
        String scopePackage = implClass.name.substring(0, implClass.name.lastIndexOf('/'));
        for (ClassNode node : nodes) {
            Optional<MethodNode> optMethod = getMethod(node, implMethod.name, implMethod.desc, isStatic);
            if (optMethod.isPresent()) {
                MethodNode method = optMethod.get();
                OverrideScope scope = OverrideScope.fromFlags(method.access);
                if (scope == OverrideScope.NEVER) {
                    continue;
                } else if (scope == OverrideScope.PACKAGE) {
                    if (!scopePackage.equals(node.name.substring(0, node.name.lastIndexOf('/')))) {
                        continue;
                    }
                }
                returnedNodes.add(node);
            }
        }
        returnedNodes.add(implClass);
        return returnedNodes;
    }

    private static Optional<MethodNode> getMethod(ClassNode node, String name, String desc, boolean isStatic) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                if (isStatic) {
                    if ((method.access & Opcodes.ACC_STATIC) == 0) {
                        continue;
                    }
                } else {
                    if ((method.access & Opcodes.ACC_STATIC) != 0) {
                        continue;
                    }
                }
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }

    private static void propagateMethodRename(FieldReference ref, MethodNode propagatingMethod, Map<String, ClassNode> lookup, Map<String, List<String>> directSubtypes, ClassNode currentNode, Map<MethodNode, Set<String>> hierarchicalGetters, OverrideScope access, String scopePackage) {
        if (OverrideScope.fromFlags(propagatingMethod.access) == OverrideScope.NEVER) {
            Set<String> a = hierarchicalGetters.get(propagatingMethod);
            if (a == null) {
                a = new LinkedHashSet<>();
                hierarchicalGetters.put(propagatingMethod, a);
            }
            a.add(currentNode.name);
            return;
        }
        OverrideScope scope = access;
        Optional<MethodNode> currentMethod = getMethod(currentNode, propagatingMethod.name, propagatingMethod.desc, false);

        // Javac will still reference a method on a class even if the method is not declared in a class
        // e.g. for an implementation of the class "Object" javac will still reference it's #equals() method
        // even if it is not overridden there
        Set<String> a = hierarchicalGetters.get(propagatingMethod);
        if (a == null) {
            a = new LinkedHashSet<>();
            hierarchicalGetters.put(propagatingMethod, a);
        }
        a.add(currentNode.name);
        if (currentMethod.isPresent()) {
            scope = OverrideScope.fromFlags(currentMethod.get().access);
            if (scope == OverrideScope.ALWAYS) {
                scopePackage = null;
            }
        }

        List<String> subtypes = directSubtypes.get(currentNode.name);
        if (subtypes != null) {
            for (String implementingClass : subtypes) {
                ClassNode implementation = lookup.get(implementingClass);
                if (implementation != null) {
                    if (scope == OverrideScope.PACKAGE) {
                        int lastSlash = implementingClass.lastIndexOf('/');
                        String implementingPackage = implementingClass.substring(0, lastSlash);
                        if (!implementingPackage.equals(scopePackage)) {
                            continue;
                        }
                    }
                    if (implementingClass.equals(currentNode.name)) {
                        throw new IllegalStateException("StackOverflowError inbound. Class: " + implementingClass);
                    }
                    propagateMethodRename(ref, propagatingMethod, lookup, directSubtypes, implementation, hierarchicalGetters, scope, scopePackage);
                }
            }
        }
    }

    private void remapSet(Map<String, TreeSet<ClassNode>> set, BufferedWriter writer, String prefix) {
        prefix = '/' + prefix;
        for (Map.Entry<String, TreeSet<ClassNode>> packageNode : set.entrySet()) {
            String packageName = packageNode.getKey();
            int counter = 0;
            for (ClassNode node : packageNode.getValue()) {
                remapClass(node.name, packageName + prefix + createString(counter++), writer);
            }
        }
    }
} enum OverrideScope {
    NEVER,
    PACKAGE,
    ALWAYS;

    public static OverrideScope fromFlags(int accessFlags) {
        if ((accessFlags & Opcodes.ACC_STATIC) != 0
                || (accessFlags & Opcodes.ACC_FINAL) != 0
                || (accessFlags & Opcodes.ACC_PRIVATE) != 0) {
            return OverrideScope.NEVER;
        }
        if ((accessFlags & Opcodes.ACC_PROTECTED) != 0
                || (accessFlags & Opcodes.ACC_PUBLIC) != 0) {
            return OverrideScope.ALWAYS;
        }
        return OverrideScope.PACKAGE;
    }
}
