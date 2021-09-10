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
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import de.geolykt.starloader.obftools.asm.remapper.Remapper;

public class IntermediaryGenerator {

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

    public void remapClasses() {
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
        for (ClassNode node : nodes) {
            char[] nameChars = node.name.toCharArray();
            boolean illegalClassName = (nameChars[0] == 'd' && nameChars[1] == 'o') || (nameChars[0] == 'i' && nameChars[1] == 'f');
            if (!illegalClassName && (nameChars.length < 2 || nameChars[nameChars.length - 2] != '/')) {
                continue;
            }

            char[] newName = new char[nameChars.length + 6];
            System.arraycopy(nameChars, 0, newName, 0, nameChars.length - (illegalClassName ? 2 : 1)); // package
            newName[newName.length - 1] = nameChars[nameChars.length - 1]; // actual name
            if (illegalClassName) {
                newName[newName.length - 2] = nameChars[nameChars.length - 2];
            }
            System.arraycopy("class_".toCharArray(), 0, newName, newName.length - (6 + (illegalClassName ? 2 : 1)), 6); // class_

            remapper.remapClassName(node.name, String.copyValueOf(newName));
            if (bw != null) {
                try {
                    bw.write("CLASS\t");
                    bw.write(nameChars);
                    bw.write('\t');
                    bw.write(newName);
                    bw.write('\n'); // The tiny format does not make use of system-dependent newlines
                } catch (IOException e) {
                    e.printStackTrace();
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
            if (packageName.equals("org/hamcrest/core") || packageName.equals("org/lwjgl/openal/") || packageName.equals("org/lwjgl/opencl/")) {
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

    private String createString(int counter) {
        if (counter > 25) {
            int first = counter / 26;
            int second = counter % 26;
            return String.valueOf(new char[] {(char) ('`' + first), (char) ('a' + second)});
        } else {
            return String.valueOf((char) ('a' + counter));
        }
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
} class ClassNodeNameComparator implements Comparator<ClassNode> {

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
}
