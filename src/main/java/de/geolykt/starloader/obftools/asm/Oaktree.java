package de.geolykt.starloader.obftools.asm;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ParameterNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Primitive class metadata recovery tool.
 * Originally intended for SML0 (a patch-based modding framework intended for larger tasks like multiplayer)
 * but got recycled to fix the mess produced by the remapping software and to make it ready for decompilation.
 *
 * @author Geolykt
 */
public class Oaktree {

    /**
     * A hardcoded set of implementations of the Iterable interface that apply for
     * generics checking later on.
     */
    public static final Set<String> ITERABLES = new HashSet<>() {
        private static final long serialVersionUID = -3779578266088390365L;

        {
            add("Ljava/util/Vector;");
            add("Ljava/util/List;");
            add("Ljava/util/ArrayList;");
            add("Ljava/lang/Iterable;");
            add("Ljava/util/Collection;");
            add("Ljava/util/AbstractCollection;");
            add("Ljava/util/AbstractList;");
            add("Ljava/util/AbstractSet;");
            add("Ljava/util/AbstractQueue;");
            add("Ljava/util/HashSet;");
            add("Ljava/util/Set;");
            add("Ljava/util/Queue;");
            add("Ljava/util/concurrent/ArrayBlockingQueue;");
            add("Ljava/util/concurrent/ConcurrentLinkedQueue;");
            add("Ljava/util/concurrent/ConcurrentLinkedQueue;");
            add("Ljava/util/concurrent/DelayQueue;");
            add("Ljava/util/concurrent/LinkedBlockingQueue;");
            add("Ljava/util/concurrent/SynchronousQueue;");
            add("Ljava/util/concurrent/BlockingQueue;");
            add("Ljava/util/concurrent/BlockingDeque;");
            add("Ljava/util/concurrent/LinkedBlockingDeque;");
            add("Ljava/util/concurrent/ConcurrentLinkedDeque;");
            add("Ljava/util/Deque;");
            add("Ljava/util/ArrayDeque;");
        }
    };

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Not enough arguments. The first argument is the source jar, the second one the target jar.");
            return;
        }
        try {
            Oaktree oakTree = new Oaktree();
            JarFile file = new JarFile(args[0]);
            oakTree.index(file);
            file.close();
//            oakTree.printClassInfo();
            oakTree.fixInnerClasses();
            oakTree.fixParameterLVT();
//            oakTree.printClassInfo();
            FileOutputStream os = new FileOutputStream(args[1]);
            oakTree.write(os);
            os.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("Exiting!");
    }

    private final List<ClassNode> nodes = new ArrayList<>();

    public Oaktree() {
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

    /**
     * Method that tries to put the Local Variable Table (LVT) in a acceptable state
     * by synchronising parameter declarations with lvt declarations. Does not do
     * anything to the LVT is the LVT is declared but empty, which is a sign of the
     * usage of obfuscation tools.
     * It is intended to be used in combination with decompilers such as quiltflower
     * but might not be usefull for less naive decompilers such as procyon, which do not decompile
     * into incoherent java code if the LVT is damaged.
     */
    public void fixParameterLVT() {
        System.out.println("Resolving LVT conflicts...");
        long startTime = System.currentTimeMillis();
        for (ClassNode node : nodes) {
            for (MethodNode method : node.methods) {
                List<LocalVariableNode> locals = method.localVariables;
                List<ParameterNode> params = method.parameters;
                if ((method.access & Opcodes.ACC_ABSTRACT) != 0 && locals == null) {
                    // abstract methods do not have any local variables apparently.
                    // It makes sense however given that abstract methods do not have a method body
                    // where local variables could be declared
                    continue;
                }
                if (method.desc.indexOf(')') == 1 && params == null) {
                    // since the description starts with a '(' we don't need to check that one
                    // a closing paranthesis after the opening one suggests that there are no input parameters.
                    continue;
                }
                if (!locals.isEmpty()) {
                    // LVTs that have been left alone by the obfuscator will have at least one declared local
                    continue;
                }
                int localVariableIndex = 0;
                if ((method.access & Opcodes.ACC_STATIC) == 0) {
                    localVariableIndex++;
                }
                DescString description = new DescString(method.desc);

                // since we can only guess when the parameters are used and when they are not
                // it only makes sense that we are cheating here and declaring empty label nodes.
                // Apparently both ASM and quiltflower accept this, so /shrug
                LabelNode start = new LabelNode();
                LabelNode end = new LabelNode();
                for (int i = 0; i < params.size(); i++) {
                    String type = description.nextType();
                    LocalVariableNode a = new LocalVariableNode(params.get(i).name,
                            type,
                            null, // we can only guess about the signature, so it'll be null
                            start,
                            end,
                            localVariableIndex);
                    char c = type.charAt(0);
                    if (c == 'D' || c == 'J') {
                        // doubles and longs take two frames on the stack. Makes sense, I know
                        localVariableIndex += 2;
                    } else {
                        localVariableIndex++;
                    }
                    locals.add(a);
                }
            }
        }
        System.out.printf("Resolved LVT conflicts! (%d ms)\n", System.currentTimeMillis() - startTime);
    }

    /**
     * Guesses the generic signatures of fields based on their usage. This might be inaccurate under
     * some circumstances, however it tries to play it as safe as possible.
     * The main algorithm in this method checks for generics via foreach iteration over the field
     * and searches for the CHECKCAST to determine the signature.
     * Note: this method should be invoked AFTER {@link #fixParameterLVT()}, invoking this method before it
     * would lead to LVT fixing not working properly
     */
    public void guessFieldGenerics() {
        Map<Map.Entry<String, Map.Entry<String, String>>, SignatureNode> newFieldSignatures = new HashMap<>();
        int addedFieldSignatures = 0;

        System.out.println("Starting guessing field signatures...");
        long startTime = System.currentTimeMillis();
        // index signatureless fields
        for (ClassNode node : nodes) {
            for (FieldNode field : node.fields) {
                if (field.signature == null && ITERABLES.contains(field.desc)) {
                    newFieldSignatures.put(Map.entry(node.name, Map.entry(field.name, field.desc)), null);
                }
            }
        }

        // guess signatures based on iterators
        for (ClassNode node : nodes) {
            for (MethodNode method : node.methods) {
                AbstractInsnNode instruction = method.instructions.getFirst();
                while (instruction != null) {
                    if (instruction instanceof FieldInsnNode) {
                        FieldInsnNode fieldNode = (FieldInsnNode) instruction;
                        var key = Map.entry(fieldNode.owner, Map.entry(fieldNode.name, fieldNode.desc));
                        AbstractInsnNode next = instruction.getNext();
                        if (!newFieldSignatures.containsKey(key) // The field doesn't actively search for a new signature
                                || !(next instanceof MethodInsnNode)) { // We cannot work with this instruction
                            instruction = next;
                            continue;
                        }
                        MethodInsnNode iteratorMethod = (MethodInsnNode) next;
                        next = next.getNext();
                        // check whether the called method is Iterable#iterator
                        if (iteratorMethod.itf // definitely not it
                                || !iteratorMethod.name.equals("iterator")
                                || !iteratorMethod.desc.equals("()Ljava/util/Iterator;")
                                || !(next instanceof VarInsnNode)) { // We cannot work with this instruction
                            instruction = next;
                            continue;
                        }
                        // cache instruction for later. This instruction should store the iterator that was just obtained
                        VarInsnNode storeInstruction = (VarInsnNode) next;
                        next = next.getNext();
                        if (!(next instanceof LabelNode)) { // this is the label that marks the beginning of the loop
                            instruction = next;
                            continue;
                        }
                        LabelNode loopStartLabel = (LabelNode) next;
                        next = next.getNext();
                        while ((next instanceof FrameNode) || (next instanceof LineNumberNode)) {
                            // filter out pseudo-instructions
                            next = next.getNext();
                        }
                        if (!(next instanceof VarInsnNode)) { // require the load instruction where the iterator will be obtained again
                            instruction = next;
                            continue;
                        }
                        VarInsnNode loadInstruction = (VarInsnNode) next;
                        next = next.getNext();
                        if (loadInstruction.var != storeInstruction.var // both instruction should load/save the same local
                                || loadInstruction.getOpcode() != Opcodes.ALOAD // the load instruction should actually load
                                || storeInstruction.getOpcode() != Opcodes.ASTORE // and the store instruction should actually store
                                || !(next instanceof MethodInsnNode)) { // we cannot work with this instruction
                            instruction = next;
                            continue;
                        }
                        MethodInsnNode hasNextInstruction = (MethodInsnNode) next;
                        next = next.getNext();
                        if (!hasNextInstruction.itf // iterator is an interface
                                || !hasNextInstruction.owner.equals("java/util/Iterator") // check whether this is the right method
                                || !hasNextInstruction.name.equals("hasNext")
                                || !hasNextInstruction.desc.equals("()Z")
                                || !(next instanceof JumpInsnNode)) { // it is pretty clear that this is a while loop now, but we have this for redundancy anyways
                            instruction = next;
                            continue;
                        }
                        JumpInsnNode loopEndJump = (JumpInsnNode) next;
                        LabelNode loopEndLabel = loopEndJump.label;
                        next = next.getNext();
                        if (!(next instanceof VarInsnNode)) { // require the load instruction where the iterator will be obtained again
                            instruction = next;
                            continue;
                        }
                        // redo the load instruction check
                        loadInstruction = (VarInsnNode) next;
                        next = next.getNext();
                        if (loadInstruction.var != storeInstruction.var // both instruction should load/save the same local
                                || loadInstruction.getOpcode() != Opcodes.ALOAD // the load instruction should actually load
                                || storeInstruction.getOpcode() != Opcodes.ASTORE // and the store instruction should actually store
                                || !(next instanceof MethodInsnNode)) { // we cannot work with this instruction
                            instruction = next;
                            continue;
                        }
                        MethodInsnNode getNextInstruction = (MethodInsnNode) next;
                        next = next.getNext();
                        if (!getNextInstruction.itf // iterator is an interface
                                || !getNextInstruction.owner.equals("java/util/Iterator") // check whether this is the right method
                                || !getNextInstruction.name.equals("next")
                                || !getNextInstruction.desc.equals("()Ljava/lang/Object;")
                                || !(next instanceof TypeInsnNode)) { // this instruction is the core of our check, and the holy grail - sadly it wasn't here. Hopefully we have better luck next time
                            instruction = next;
                            continue;
                        }
                        TypeInsnNode checkCastInstruction = (TypeInsnNode) next;
                        next = next.getNext();
                        if (checkCastInstruction.getOpcode() != Opcodes.CHECKCAST) {
                            // so close!
                            instruction = next;
                            continue;
                        }
                        String suggestion = "L" + checkCastInstruction.desc + ";";
                        SignatureNode suggestedSignature = new SignatureNode(fieldNode.desc, suggestion);
                        SignatureNode currentlySuggested = newFieldSignatures.get(key);
                        instruction = next;
                        if (currentlySuggested != null) {
                            if (!suggestedSignature.equals(currentlySuggested)) {
                                addedFieldSignatures--;
                                System.out.println("Contested signatures for " + key);
                                newFieldSignatures.remove(key);
                                continue;
                            }
                        } else {
                            addedFieldSignatures++;
                            newFieldSignatures.put(key, suggestedSignature);
                        }

                        // Add arbitrary LVT entries to reduce the amount of <unknown>
                        if (!(next instanceof VarInsnNode) || next.getOpcode() != Opcodes.ASTORE) {
                            // We don't have a variable to attach anything to (???) - not critical, so shrug
                            continue;
                        }
                        VarInsnNode iteratedObject = (VarInsnNode) next;
                        List<LocalVariableNode> localVars = method.localVariables;
                        boolean alreadyDeclaredLVT = false;
                        for (LocalVariableNode var0 : localVars) {
                            if (var0.index == iteratedObject.var && var0.desc.equals(suggestion)) {
                                alreadyDeclaredLVT = true;
                                break;
                            }
                        }
                        if (!alreadyDeclaredLVT) {
                            // add LVT entry for the iterator
                            LocalVariableNode lvtNode = new LocalVariableNode(
                                    "var" + iteratedObject.var, suggestion,
                                    null,
                                    loopStartLabel, loopEndLabel, iteratedObject.var);
                            localVars.add(lvtNode);
                        }
                        continue;
                    }
                    instruction = instruction.getNext();
                }
            }
        }
        System.out.printf("Guessed %d field signatures! (%d ms)\n", addedFieldSignatures, System.currentTimeMillis() - startTime);

        for (ClassNode node : nodes) {
            for (FieldNode field : node.fields) {
                if (field.signature == null && ITERABLES.contains(field.desc)) {
                    SignatureNode result = newFieldSignatures.get(Map.entry(node.name, Map.entry(field.name, field.desc)));
                    if (result == null) {
                        //System.out.println("Unable to find signature for : " + node.name + "." + field.name);
                    } else {
                        field.signature = result.toString();
                    }
                }
            }
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
}
