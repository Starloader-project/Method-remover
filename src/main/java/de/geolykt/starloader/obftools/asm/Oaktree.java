package de.geolykt.starloader.obftools.asm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Primitive class metadata recovery tool.
 * Originally intended for SML0 (a patch-based modding framework intended for larger tasks like multiplayer)
 * but got recycled to fix the mess produced by the remapping software
 *
 * @author Geolykt
 */
public class Oaktree {

    private final List<ClassNode> nodes = new ArrayList<>();
    private final List<FieldSignatureEntry> signatureEntries = new ArrayList<>();

    public Oaktree() {
    }

    public static class FieldSignatureEntry {
        public final String owner;
        public final String name;
        public final String signature;
        public final String desc;

        public FieldSignatureEntry(String clazz, String name, String desc, String sig) {
            owner = clazz;
            this.name = name;
            this.desc = desc;
            signature = sig;
        }
    }

    @Deprecated // not working as intended, if at all
    public void indexSignatureFields(File file) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String ln = br.readLine();
            while (ln != null) {
                ln = ln.split("#")[0].strip();
                if (ln.length() == 0) {
                    ln = br.readLine();
                    continue;
                }
                String[] splits = ln.split(" ");
                String[] fsplits = new String[4];
                int j = 0;
                for (int i = 0; i < splits.length; i++) {
                    if (!splits[i].isBlank()) {
                        fsplits[j++] = splits[i];
                    }
                }
                signatureEntries.add(new FieldSignatureEntry(splits[0], splits[1], splits[2], splits[3]));
                ln = br.readLine();
            }
            br.close();
        }
    }

    public void index(JarFile file) {
        System.out.println("Indexing class files...");
        file.entries().asIterator().forEachRemaining(entry -> {
            if (entry.getName().endsWith(".class")) {
                ClassReader reader;
                try {
                    InputStream is = file.getInputStream(entry);
                    reader = new ClassReader(is);
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
                ClassNode node = new ClassNode();
                reader.accept(node, 0);
                nodes.add(node);
            }
        });
        System.out.println("Indexed class files!");
    }

    public void printClassInfo() {
        for (ClassNode node : nodes) {
            System.out.println("Read class: " + node.name);
            for (InnerClassNode clazzNode : node.innerClasses) {
                System.out.println("Inner class: " + clazzNode.innerName + "; Outer class: " + clazzNode.outerName + "; Intern Name: " + clazzNode.name + "; Access: " + clazzNode.access);
            }
        }
    }

    /**
     * Guesses the inner classes from class nodes
     */
    public void fixInnerClasses() {
        Map<String, InnerClassNode> splitInner = new HashMap<>();
        Set<String> enums = new HashSet<>();
        Map<String, List<InnerClassNode>> parents = new HashMap<>();
        Map<String, ClassNode> classNodes = new HashMap<>();

        // Initial indexing sweep
        System.out.println("Inner Classes Fixup: Initial Sweep");
        for (ClassNode node : nodes) {
            classNodes.put(node.name, node);
            parents.put(node.name, new ArrayList<>());
            if (node.superName.equals("java/lang/Enum")) {
                enums.add(node.name); // Register enum
            }
        }
        // Second sweep
        System.out.println("Inner Classes Fixup: Second Sweep");
        for (ClassNode node : nodes) {
            // Sweep enum members
            if (enums.contains(node.superName)) {
                // Child of (abstract) enum
                boolean skip = false;
                for (InnerClassNode innerNode : node.innerClasses) {
                    if (node.name.equals(innerNode.name)) {
                        skip = true;
                        break;
                    }
                }
                if (!skip) {
                    // Apply fixup
                    // We are using 16400 for access, but are there times where this is not wanted?
                    InnerClassNode innerNode = new InnerClassNode(node.name, null, null, 16400);
                    parents.get(node.superName).add(innerNode);
                    node.innerClasses.add(innerNode);
                }
            } else if (node.name.contains("$")) {
                // This operation cannot be performed during the first sweep
                boolean skip = false;
                for (InnerClassNode innernode : node.innerClasses) {
                    if (innernode.name.equals(node.name)) {
                        skip = true;
                        break;
                    }
                }
                if (!skip) {
                    int lastSeperator = node.name.lastIndexOf('$');
                    String outerMost = node.name.substring(0, lastSeperator++);
                    String parent = outerMost;
                    String innerMost = node.name.substring(lastSeperator);
                    if (innerMost.matches("^[0-9]+$")) {
                        // Anonymous class
                        outerMost = null;
                        innerMost = null;
                    }
                    // TODO is there a possibility to "recover" the original access of the inner node?
                    InnerClassNode innerNode = new InnerClassNode(node.name, outerMost, innerMost, node.access);
                    parents.get(parent).add(innerNode);
                    splitInner.put(node.name, innerNode);
                }
            }
        }
        System.out.println("Inner Classes Fixup: Parent References");
        for (ClassNode node : nodes) {
            // General sweep
            Collection<InnerClassNode> innerNodesToAdd = new ArrayList<>();
            for (FieldNode field : node.fields) {
                String descriptor = field.desc;
                if (descriptor.length() < 4) {
                    continue; // Most likely a primitive
                }
                if (descriptor.charAt(0) == '[') {
                    // Array
                    descriptor = descriptor.substring(2, descriptor.length() - 1);
                } else {
                    // Non-array
                    descriptor = descriptor.substring(1, descriptor.length() - 1);
                }
                InnerClassNode innerNode = splitInner.get(descriptor);
                if (innerNode != null) {
                    if (innerNode.innerName == null/* && !field.name.contains("$")*/) {
                        // Not fatal, but worrying
                        System.err.println(String.format("Unlikely field descriptor for field \"%s\" with descriptor %s in class %s", field.name, field.desc, node.name));
                    }
                    innerNodesToAdd.add(innerNode);
                }
            }
            // Apply inner nodes
            HashSet<String> entryNames = new HashSet<>();
            for (InnerClassNode inner : innerNodesToAdd) {
                if (entryNames.add(inner.name)) {
                    node.innerClasses.add(inner);
                }
            }
        }
        // Add inner classes to the parent of the anonymous classes
        System.out.println("Inner Classes Fixup: Parents");
        for (Entry<String, List<InnerClassNode>> entry : parents.entrySet()) {
            // Remove duplicates
            HashSet<String> entryNames = new HashSet<>();
            ArrayList<InnerClassNode> toRemove = new ArrayList<>();
            for (InnerClassNode inner : entry.getValue()) {
                if (!entryNames.add(inner.name)) {
                    toRemove.add(inner);
                }
            }
            toRemove.forEach(entry.getValue()::remove);
            ClassNode node = classNodes.get(entry.getKey());
            for (InnerClassNode innerEntry : entry.getValue()) {
                boolean skip = false;
                for (InnerClassNode inner : node.innerClasses) {
                    if (inner.name.equals(innerEntry.name)) {
                        skip = true;
                        break;
                    }
                }
                if (!skip) {
                    node.innerClasses.add(innerEntry);
                }
            }
        }
        System.out.println("Inner Classes Fixup: Done!");
    }

    public static final Set<String> LISTS = new HashSet<>();
    public static final Set<String> LISTS_RAW = new HashSet<>();

    @Deprecated
    public void fixGenerics() {
        Map<String, List<FieldSignatureEntry>> lookup = new HashMap<>();
        for (FieldSignatureEntry e : signatureEntries) {
            if (!lookup.containsKey(e.owner)) {
                lookup.put(e.owner, new ArrayList<>());
            }
            lookup.get(e.owner).add(e);
        }
        for (ClassNode node : nodes) {
            List<FieldSignatureEntry> entries = lookup.get(node.name);
            if (entries == null || entries.isEmpty()) {
                continue;
            }
            for (FieldSignatureEntry e : entries) {
                for (FieldNode field : node.fields) {
                    if (field.name.equals(e.name) && field.desc.equals(e.desc)) {
                        field.signature = e.signature;
                    }
                }
            }
        }
    }

    @Deprecated // Does not work, but included if anyone wants to fix it (I will eventually fix it, but that will take quite a while)
    public void fixGenericsGuessing() {
        List<FieldNode> lists = new ArrayList<>();
        List<SignatureNode> listsSignatures = new ArrayList<>();
        HashMap<String, String> superclasses = new HashMap<>();
        HashMap<String, List<String>> interfaces = new HashMap<>();
        HashMap<String, ClassNode> classNodes = new HashMap<>();
        System.out.println("Field generics fixup: Indexing fields");
        for (ClassNode node : nodes) {
            superclasses.put(node.name, node.superName);
            classNodes.put(node.name, node);
            interfaces.put(node.name, node.interfaces);
            for (FieldNode field : node.fields) {
                if (field.signature == null && LISTS.contains(field.desc)) {
                    lists.add(field);
                    listsSignatures.add(new SignatureNode(field));
                }
//                System.out.printf("Class %s\t Field %s\t Has description %s and signature %s\n", node.name, field.name, field.desc, field.signature);
            }
        }

        // ---------------------------- LISTS ---------------------------- //

        Map<FieldRefTriplet, List<FieldRefTriplet>> listRefs = new HashMap<>();
        System.out.println("Field generics fixup: Indexing references");
        for (ClassNode node : nodes) {
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode instructionNode = (MethodInsnNode) instruction;
//                        System.out.printf("%s,%s\n", instructionNode.owner, instructionNode.desc);
                        AbstractInsnNode previous = instruction.getPrevious();
                        if (LISTS_RAW.contains(instructionNode.owner) && previous != null) {
                            switch (instructionNode.name) {
                            case "add":
                                // TODO also allow other ways of obtaining the variable
                                if (instructionNode.desc.equals("(Ljava/lang/Object;)Z") && previous instanceof FieldInsnNode) {
                                    AbstractInsnNode listIsn = previous.getPrevious();
                                    if (listIsn instanceof FieldInsnNode) {
                                        FieldRefTriplet listRef = new FieldRefTriplet((FieldInsnNode) listIsn);
                                        FieldRefTriplet varRef = new FieldRefTriplet((FieldInsnNode) previous);
                                        if (!listRefs.containsKey(listRef)) {
                                            listRefs.put(listRef, new ArrayList<>());
                                        }
                                        listRefs.get(listRef).add(varRef);
                                        System.out.println("Referenced cleanly.");
                                    } else {
                                        // Not yet supported; TODO implement
                                    }
                                    System.out.println("Referenced.");
                                }
                                break;
                            case "remove":
                                if (instructionNode.desc.equals("(Ljava/lang/Object;)Z")) {
                                    // TODO implement (this is rarely called, so we might as well come clear by not using it)
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }
        System.out.println("Field generics fixup: Guessing generics"); // It really is just guessing

        for (Map.Entry<FieldRefTriplet, List<FieldRefTriplet>> entry : listRefs.entrySet()) {
            FieldRefTriplet remappingField = entry.getKey();
            ClassNode parentNode = classNodes.get(remappingField.owner);
            System.out.println("Remapping " + remappingField.toString());
            if (parentNode == null) {
                System.err.printf(Locale.ROOT, "%s does not have a known ClassNode parent. Skipping.", remappingField.toString());
                continue;
            }
            FieldNode fieldNode = null;
            for (FieldNode field : parentNode.fields) {
                if (field.name.equals(remappingField.name) && field.desc.equals(remappingField.desc)) {
                    fieldNode = field;
                    break;
                }
            }
            if (fieldNode == null) {
                System.err.printf(Locale.ROOT, "%s was not found within it's ClassNode parent. Skipping.", remappingField.toString());
                continue;
            }
            if (fieldNode.signature != null) {
                continue; // Already mapped
            }
            List<Set<String>> candidates = new ArrayList<>();
            for (int i = 0; i < entry.getValue().size(); i++) {
                Set<String> c = new HashSet<>();
                putCandidates(entry.getValue().get(i), c, superclasses, interfaces);
                candidates.add(i, c);
            }
            List<String> commonCandidates = new ArrayList<>(candidates.get(0));
            for (int i = 1; i < candidates.size(); i++) {
                final int n = i;
                commonCandidates.removeIf(s -> !candidates.get(n).contains(s));
            }
            String candidate = getCandidate(commonCandidates, superclasses, interfaces);
            if (candidate != null) {
                fieldNode.signature = new SignatureNode(fieldNode.desc, candidate).toString();
            }
        }
    }

    private String getCandidate(List<String> candidates, Map<String, String> supers, Map<String, List<String>> interfaces) {
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        // Try find the most advanced common candidate within the class hierarchy
        List<String> clone = new ArrayList<>(candidates);
        for (String s : candidates) {
            clone.remove(supers.get(s));
            List<String> toRemove = interfaces.get(s);
            if (toRemove != null) {
                clone.removeAll(toRemove);
            }
        }
        if (clone.size() != 1) {
            return clone.get(0);
        }
        System.out.println("multi");
        return null; // Multiple candidates, likely not enough input data
    }

    private void putCandidates(FieldRefTriplet triplet, Set<String> candidates, Map<String, String> supers, Map<String, List<String>> interfaces) {
        String owner = triplet.owner;
        while (owner != null) {
            candidates.add(owner);
            List<String> toAdd = interfaces.get(owner);
            if (toAdd != null) {
                candidates.addAll(toAdd);
            }
            owner = supers.get(owner);
        }
    }

    public void write(OutputStream out) throws IOException {
        System.out.println("Exporting...");
        JarOutputStream jarOut = new JarOutputStream(out);
        for (ClassNode node : nodes) {
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            jarOut.putNextEntry(new ZipEntry(node.name + ".class"));
            jarOut.write(writer.toByteArray());
            jarOut.closeEntry();
        }
        jarOut.close();
        System.out.println("Exported!");
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Not enough arguments. The first argument is the source jar, the second one the target jar.");
            return;
        }
        try {
            Oaktree oakTree = new Oaktree();
            JarFile file = new JarFile(args[0]);
            oakTree.index(file);
            for (int i = 2; i < args.length; i++) {
                oakTree.indexSignatureFields(new File(args[i]));
            }
            file.close();
//            oakTree.printClassInfo();
            oakTree.fixInnerClasses();
            oakTree.fixGenerics();
//            oakTree.printClassInfo();
            FileOutputStream os = new FileOutputStream(args[1]);
            oakTree.write(os);
            os.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("Exiting!");
    }

    static {
        LISTS.add("Ljava/utilVector;");
        LISTS.add("Ljava/util/List;");
        LISTS.add("Ljava/util/ArrayList;");
        LISTS_RAW.add("java/utilVector");
        LISTS_RAW.add("java/util/List");
        LISTS_RAW.add("java/util/ArrayList");
    }
}
