package de.geolykt.starloader.obftools.asm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
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
import org.objectweb.asm.tree.InsnNode;
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

    public static final int VISIBILITY_MODIFIERS = Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_PUBLIC;

    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        if (args.length < 2) {
            System.err.println("Not enough arguments. The first argument is the source jar, the second one the target jar.");
            return;
        }
        if (args.length == 3 && Boolean.valueOf(args[2]) == true) {
            // remapper activate!
            File intermediaryMap = new File("intermediaryMap.temp");
            File inputFile = new File(args[0]);
            File outputFile = new File(args[1] + ".temp.jar");
            IntermediaryGenerator gen = new IntermediaryGenerator(inputFile, intermediaryMap, outputFile);
            gen.remapClasses();
            gen.doProposeFields();
            gen.deobfuscate();
            args[0] = args[1] + ".temp.jar";
            outputFile.deleteOnExit();
            intermediaryMap.deleteOnExit();
        }
        try {
            Oaktree oakTree = new Oaktree();
            JarFile file = new JarFile(args[0]);
            oakTree.index(file);
            file.close();
            oakTree.fixInnerClasses();
            oakTree.fixParameterLVT();
            oakTree.guessFieldGenerics();
            oakTree.fixSwitchMaps(true);
            oakTree.fixForeachOnArray(true);
            oakTree.fixComparators(true, true);
            oakTree.guessAnonymousInnerClasses(true);
            FileOutputStream os = new FileOutputStream(args[1]);
            oakTree.write(os);
            os.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.printf("Finished processing in record pace: Only %d ms!\n", System.currentTimeMillis() - start);
    }

    private final Map<String, ClassNode> nameToNode = new HashMap<>();

    private final List<ClassNode> nodes = new ArrayList<>();
    public Oaktree() {
    }

    /**
     * Add the signature of obvious bridge methods (i. e. comparators).
     *
     * @param doLogging Whether to perform any logging via System.out
     * @param resolveTRArtifact Whether to resolve an artifact left over by tiny remapper.
     */
    public void fixComparators(boolean doLogging, boolean resolveTRArtifact) {
        long start = System.currentTimeMillis();
        int fixedBridges = 0;
        for (ClassNode node : nodes) {
            if (node.signature != null || node.interfaces.size() != 1) {
                continue;
            }
            if (!node.interfaces.get(0).equals("java/util/Comparator")) {
                continue;
            }
            // Ljava/lang/Object;Ljava/util/Comparator<Lorg/junit/runner/Description;>;
            for (MethodNode method : node.methods) {
                if ((method.access & Opcodes.ACC_SYNTHETIC) == 0) {
                    continue;
                }
                if (method.name.equals("compare") && method.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)I")) {
                    AbstractInsnNode insn = method.instructions.getFirst();
                    while (insn instanceof LabelNode || insn instanceof LineNumberNode) {
                        insn = insn.getNext();
                    }
                    if (insn.getOpcode() != Opcodes.ALOAD) {
                        throw new IllegalStateException("invalid bridge method: unexpected opcode");
                    }
                    VarInsnNode aloadThis = (VarInsnNode) insn;
                    if (aloadThis.var != 0) {
                        throw new IllegalStateException("invalid bridge method: unexpected variable loaded");
                    }
                    insn = insn.getNext();
                    if (insn.getOpcode() != Opcodes.ALOAD) {
                        throw new IllegalStateException("invalid bridge method: unexpected opcode");
                    }
                    insn = insn.getNext();
                    if (insn.getOpcode() != Opcodes.CHECKCAST) {
                        throw new IllegalStateException("invalid bridge method: unexpected opcode");
                    }
                    insn = insn.getNext();
                    if (insn.getOpcode() != Opcodes.ALOAD) {
                        throw new IllegalStateException("invalid bridge method: unexpected opcode");
                    }
                    insn = insn.getNext();
                    if (insn.getOpcode() != Opcodes.CHECKCAST) {
                        throw new IllegalStateException("invalid bridge method: unexpected opcode");
                    }
                    insn = insn.getNext();
                    if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) {
                        throw new IllegalStateException("invalid bridge method: unexpected opcode");
                    }
                    MethodInsnNode invokevirtual = (MethodInsnNode) insn;
                    insn = insn.getNext();
                    if (insn.getOpcode() != Opcodes.IRETURN) {
                        throw new IllegalStateException("invalid bridge method: unexpected opcode");
                    }
                    boolean methodCallIsInvalid = true;
                    for (MethodNode m : node.methods) {
                        if (m.name.equals(invokevirtual.name) && m.desc.equals(invokevirtual.desc)) {
                            methodCallIsInvalid = false;
                            break;
                        }
                    }
                    if (methodCallIsInvalid) {
                        if (resolveTRArtifact) {
                            // Tiny remapper artifact
                            invokevirtual.name = "compare";
                        } else {
                            throw new IllegalStateException("invalid bridge method: method does not exist (consider setting resolveTRArtifact to true)");
                        }
                    }
                    String generics = invokevirtual.desc.substring(1, invokevirtual.desc.indexOf(';'));
                    node.signature = "Ljava/lang/Object;Ljava/util/Comparator<" + generics + ";>;";
                    fixedBridges++;
                    method.access |= Opcodes.ACC_BRIDGE;
                    break;
                }
            }
        }
        if (doLogging) {
            System.out.printf("Fixed %d bridge methods! (%d ms)\n", fixedBridges, System.currentTimeMillis() - start);
        }
    }

    /**
     * Resolve useless &lt;unknown&gt; mentions when quiltflower decompiles foreach loops that
     * loop on arrays by adding their respective LVT entries via guessing.
     * However since this requires the knowledge fo the array type, this may not always be successfull.
     *<br/>
     * As this modifies the LVT entries, it should be called AFTER {@link #fixParameterLVT()}.
     *
     * @param doLog Whether to perform any logging operations
     */
    public void fixForeachOnArray(boolean doLog) {
        int addedLVTs = 0;
        long startTime = System.currentTimeMillis();

        for (ClassNode node : nodes) {
            for (MethodNode method : node.methods) {
                AbstractInsnNode instruction = method.instructions.getFirst();
                while (instruction != null) {
                    if (instruction instanceof VarInsnNode && OPHelper.isVarStore(instruction.getOpcode())) {
                        VarInsnNode arrayStore = (VarInsnNode) instruction;
                        AbstractInsnNode next = arrayStore.getNext();
                        // Ensure that the variable that was just stored is reloaded again
                        if (!(next instanceof VarInsnNode && OPHelper.isVarLoad(next.getOpcode())
                                && ((VarInsnNode) next).var == arrayStore.var)) {
                            instruction = next;
                            continue;
                        }
                        // the array length needs to be obtained & stored
                        next = next.getNext();
                        if (!(next instanceof InsnNode && next.getOpcode() == Opcodes.ARRAYLENGTH)) {
                            instruction = next;
                            continue;
                        }
                        next = next.getNext();
                        if (!(next instanceof VarInsnNode && next.getOpcode() == Opcodes.ISTORE)) {
                            instruction = next;
                            continue;
                        }
                        VarInsnNode arrayLengthStore = (VarInsnNode) next;
                        next = next.getNext();
                        // the array index needs to be initialized and stored
                        if (!(next instanceof InsnNode && next.getOpcode() == Opcodes.ICONST_0)) {
                            // is not the init process
                            instruction = next;
                            continue;
                        }
                        next = next.getNext();
                        if (!(next instanceof VarInsnNode && next.getOpcode() == Opcodes.ISTORE)) {
                            // does not store the loop index
                            instruction = next;
                            continue;
                        }
                        VarInsnNode indexStore = (VarInsnNode) next;
                        next = next.getNext();
                        // This is the loop starting point
                        while (next instanceof FrameNode || next instanceof LabelNode) {
                            next = next.getNext();
                        }
                        // The index needs to be loaded and compared do the array length
                        if (!(next instanceof VarInsnNode && next.getOpcode() == Opcodes.ILOAD
                                && ((VarInsnNode)next).var == indexStore.var)) {
                            instruction = next;
                            continue;
                        }
                        next = next.getNext();
                        if (!(next instanceof VarInsnNode && next.getOpcode() == Opcodes.ILOAD
                                && ((VarInsnNode)next).var == arrayLengthStore.var)) {
                            instruction = next;
                            continue;
                        }
                        next = next.getNext();
                        // The end of the loop statement
                        if (!(next instanceof JumpInsnNode && next.getOpcode() == Opcodes.IF_ICMPGE)) {
                            instruction = next;
                            continue;
                        }
                        JumpInsnNode jumpToEnd = (JumpInsnNode) next;
                        next = next.getNext();
                        // obtain array & loop index
                        if (!(next instanceof VarInsnNode && OPHelper.isVarLoad(next.getOpcode())
                                && ((VarInsnNode)next).var == arrayStore.var)) {
                            instruction = next;
                            continue;
                        }
                        VarInsnNode arrayLoad = (VarInsnNode) next;
                        next = next.getNext();
                        if (!(next instanceof VarInsnNode && next.getOpcode() == Opcodes.ILOAD
                                && ((VarInsnNode)next).var == indexStore.var)) {
                            instruction = next;
                            continue;
                        }
                        next = next.getNext();
                        // it should now proceed to actually obtain the referenced object
                        if (!(next instanceof InsnNode && OPHelper.isArrayLoad(next.getOpcode())
                                && OPHelper.isVarSimilarType(next.getOpcode(), arrayLoad.getOpcode()))) {
                            instruction = next;
                            continue;
                        }
                        next = next.getNext();
                        if (!(next instanceof VarInsnNode && OPHelper.isVarStore(next.getOpcode())
                                && OPHelper.isVarSimilarType(next.getOpcode(), arrayStore.getOpcode()))) {
                            instruction = next;
                            continue;
                        }
                        VarInsnNode objectStore = (VarInsnNode) next;
                        next = next.getNext();
                        instruction = next; // There may be nested loops - we want to take a lookout for them
                        // This is now defenitely a for loop on an array. This does not mean however
                        // that it is a foreach loop, which is the kind of loop we were searching for.
                        // There is at least one operation that invalidate the use of a foreach loop:
                        // - obtaining the loop index
                        // Obtaining the array contents might be another issue, but I don't think it qualifies
                        // as it could also be that the array was declared earlier
                        boolean validForEachLoop = true;
                        while (true) { // dangerous while (true) loop; but do not despair, it isn't as dangerous as you may believe
                            if (next == null) {
                                System.err.println("Method " + node.name + "." + method.name + method.desc + " has a cursed for loop.");
                                break;
                            }
                            if (next instanceof VarInsnNode && ((VarInsnNode)next).var == indexStore.var) {
                                validForEachLoop = false;
                                break;
                            }
                            if (next instanceof LabelNode && jumpToEnd.label.equals(next)) {
                                break;
                            }
                            next = next.getNext();
                        }
                        if (validForEachLoop) {
                            // So this is a valid foreach loop on an array!
                            // Grats, but now we need to determine the correct type for LVT.
                            // Since I did a mistake while designing this method, we already know
                            // where the loop came from, so that thankfully is not an issue (yay)
                            AbstractInsnNode previous = arrayStore.getPrevious();
                            if (previous == null) {
                                System.err.println("Method " + node.name + "." + method.name + method.desc + " has invalid bytecode.");
                                continue;
                            }
                            String arrayDesc = null;
                            if (previous instanceof MethodInsnNode) {
                                MethodInsnNode methodInvocation = (MethodInsnNode) previous;
                                arrayDesc = methodInvocation.desc.substring(methodInvocation.desc.lastIndexOf(')') + 1);
                            } else if (previous instanceof FieldInsnNode) {
                                arrayDesc = ((FieldInsnNode)previous).desc;
                            } else if (previous instanceof TypeInsnNode) {
                                if (previous.getOpcode() == Opcodes.ANEWARRAY) {
                                    arrayDesc = "[L" + ((TypeInsnNode)previous).desc + ";";
                                } else {
                                    arrayDesc = ((TypeInsnNode)previous).desc;
                                }
                            } else if (previous instanceof VarInsnNode) {
                                if (OPHelper.isVarLoad(previous.getOpcode())) {
                                    VarInsnNode otherArrayInstance = (VarInsnNode) previous;
                                    while (previous != null) {
                                        if (previous instanceof VarInsnNode
                                                && ((VarInsnNode) previous).var == otherArrayInstance.var
                                                && OPHelper.isVarStore(previous.getOpcode())) {
                                            AbstractInsnNode origin = previous.getPrevious();
                                            if (origin instanceof VarInsnNode && OPHelper.isVarLoad(origin.getOpcode())) {
                                                // Ugh...
                                                otherArrayInstance = (VarInsnNode) origin;
                                                continue;
                                            } else if (origin instanceof MethodInsnNode) {
                                                MethodInsnNode methodInvocation = (MethodInsnNode) origin;
                                                arrayDesc = methodInvocation.desc.substring(methodInvocation.desc.lastIndexOf(')') + 1);
                                                break;
                                            } else if (origin instanceof FieldInsnNode) {
                                                arrayDesc = ((FieldInsnNode)origin).desc;
                                                break;
                                            } else if (origin instanceof TypeInsnNode) {
                                                if (origin.getOpcode() == Opcodes.ANEWARRAY) {
                                                    arrayDesc = "[L" + ((TypeInsnNode)origin).desc + ";";
                                                } else {
                                                    arrayDesc = ((TypeInsnNode)origin).desc;
                                                }
                                                break;
                                            } else {
                                                // I have come to the conclusion that it isn't worth the effort to attempt to recover the
                                                // type of the variable here
                                                // This is as it is likely that the array is hidden deep in the stack before it was stored
                                                break;
                                            }
                                        }
                                        previous = previous.getPrevious();
                                    }
                                }
                            }
                            if (arrayDesc != null) {
                                if (arrayDesc.charAt(0) != '[') {
                                    System.err.println("Method " + node.name + "." + method.name + method.desc + " has invalid bytecode.");
                                    System.err.println("Guessed type: " + arrayDesc + ", but expected an array. Array found at index " + arrayStore.var);
                                    continue;
                                }
                                // Copy my Quiltflower rant from the other genericsfixing method
                                // Actually - it might be for the better as otherwise I would have to spend my time checking if the LVT entry already exists
                                LabelNode startObjectStoreLabel = new LabelNode();
                                method.instructions.insertBefore(objectStore, startObjectStoreLabel);
                                LocalVariableNode localVar = new LocalVariableNode("var" + objectStore.var,
                                        arrayDesc.substring(1), null, startObjectStoreLabel, jumpToEnd.label, objectStore.var);
                                method.localVariables.add(localVar);
                                addedLVTs++;
                            }
                        }
                        continue;
                    }
                    instruction = instruction.getNext();
                }
            }
        }

        if (doLog) {
            System.out.printf("Resolved %d foreach on array LVTs! (%d ms)\n", addedLVTs, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Guesses the inner classes from class nodes
     */
    public void fixInnerClasses() {
        long start = System.currentTimeMillis();
        Map<String, InnerClassNode> splitInner = new HashMap<>();
        Set<String> enums = new HashSet<>();
        Map<String, List<InnerClassNode>> parents = new HashMap<>();

        // Initial indexing sweep
        for (ClassNode node : nodes) {
            parents.put(node.name, new ArrayList<>());
            if (node.superName.equals("java/lang/Enum")) {
                enums.add(node.name); // Register enum
            }
        }
        // Second sweep
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
                    node.outerClass = node.superName;
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
                    String outerNode = node.name.substring(0, lastSeperator++);
                    String innerMost = node.name.substring(lastSeperator);
                    InnerClassNode innerClassNode;
                    if (innerMost.matches("^[0-9]+$")) {
                        // Anonymous class
                        innerClassNode = new InnerClassNode(node.name, null, null, node.access);
                    } else {
                        // We need to check for static inner classes.
                        // We already know that anonymous classes can never be static classes by definition,
                        // So we can skip that step for anonymous classes
                        // A static inner class is static if it has no synthetic fields.
                        // This is a very crude definition of it, but at least it works
                        boolean hasSyntheticField = false;
                        for (FieldNode field : node.fields) {
                            if ((field.access & Opcodes.ACC_SYNTHETIC) != 0) {
                                hasSyntheticField = true;
                                break;
                            }
                        }
                        if (!hasSyntheticField) {
                            node.access |= Opcodes.ACC_STATIC;
                        }
                        innerClassNode = new InnerClassNode(node.name, outerNode, innerMost, node.access);
                    }
                    node.outerClass = outerNode;
                    parents.get(outerNode).add(innerClassNode);
                    splitInner.put(node.name, innerClassNode);
                }
            }
        }
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
                    if (innerNode.innerName == null && !field.name.startsWith("this$")) {
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
            ClassNode node = nameToNode.get(entry.getKey());
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
        System.out.printf("Inner Classes Fixup: Done! (%d ms)\n", System.currentTimeMillis() - start);
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
        long startTime = System.currentTimeMillis();
        for (ClassNode node : nodes) {
            for (MethodNode method : node.methods) {
                List<LocalVariableNode> locals = method.localVariables;
                List<ParameterNode> params = method.parameters;
                if (method.desc.indexOf(')') == 1 && params == null) {
                    // since the description starts with a '(' we don't need to check that one
                    // a closing paranthesis after the opening one suggests that there are no input parameters.
                    continue;
                }
                if ((method.access & Opcodes.ACC_ABSTRACT) != 0) {
                    // abstract methods do not have any local variables apparently.
                    // It makes sense however given that abstract methods do not have a method body
                    // where local variables could be declared
                    continue;
                }
                if (!Objects.requireNonNull(locals).isEmpty()) {
                    // LVTs that have been left alone by the obfuscator will have at least one declared local
                    continue;
                }

                if (params == null) {
                    method.parameters = new ArrayList<>();
                    params = method.parameters;
                    // Generate method parameter array
                    DescString description = new DescString(method.desc);
                    List<String> types = new ArrayList<>();
                    while (description.hasNext()) {
                        types.add(description.nextType());
                    }
                    Set<String> existingTypes = new HashSet<>();
                    Set<String> duplicateTypes = new HashSet<>();
                    duplicateTypes.add("Ljava/lang/Class;"); // class is a keyword
                    boolean oneArray = false;
                    boolean multipleArrays = false;
                    for (String type : types) {
                        if (type.charAt(0) == '[') {
                            if (oneArray) {
                                multipleArrays = true;
                            } else {
                                oneArray = true;
                            }
                        } else {
                            if (!existingTypes.add(type)) {
                                duplicateTypes.add(type);
                            }
                        }
                    }
                    for (int i = 0; i < types.size(); i++) {
                        String type = types.get(i);
                        String name = null;
                        switch (type.charAt(0)) {
                        case 'L':
                            int cutOffIndex = Math.max(type.lastIndexOf('/'), type.lastIndexOf('$')) + 1;
                            name = Character.toString(Character.toLowerCase(type.codePointAt(cutOffIndex))) + type.substring(cutOffIndex + 1, type.length() - 1);
                            if (duplicateTypes.contains(type)) {
                                name += i;
                            }
                            break;
                        case '[':
                            if (multipleArrays) {
                                name = "arr" + i;
                            } else {
                                name = "arr";
                            }
                            break;
                        case 'F': // float
                            name = "float" + i;
                            break;
                        case 'D': // double
                            name = "double" + i;
                            break;
                        case 'Z': // boolean
                            name = "boolean" + i;
                            break;
                        case 'B': // byte
                            name = "byte" + i;
                            break;
                        case 'C': // char
                            if (duplicateTypes.contains(type)) {
                                name = "character" + i;
                            } else {
                                name = "character";
                            }
                            break;
                        case 'S': // short
                            name = "short" + i;
                            break;
                        case 'I': // integer
                            if (duplicateTypes.contains(type)) {
                                name = "integer" + i;
                            } else {
                                name = "integer";
                            }
                            break;
                        case 'J': // long
                            name = "long" + i;
                            break;
                        default:
                            throw new IllegalStateException("Unknown type: " + type);
                        }
                        params.add(new ParameterNode(Objects.requireNonNull(name), 0));
                    }
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
     * Method that tries to restore the SwitchMaps to how they should be.
     * This includes marking the switchmap classes as anonymous classes, so they may not be referenceable
     * afterwards.
     *
     * @param doLogging Whether to put anything to sysout for logging
     */
    public void fixSwitchMaps(boolean doLogging) {
        Map<FieldReference, String> deobfNames = new HashMap<>(); // The deobf name will be something like $SwitchMap$org$bukkit$Material
        long startTime = System.currentTimeMillis();

        // index switch map classes - or at least their candidates
        for (ClassNode node : nodes) {
            if (node.superName != null && node.superName.equals("java/lang/Object") && node.interfaces.isEmpty()) {
                if (node.fields.size() == 1 && node.methods.size() == 1) {
                    MethodNode method = node.methods.get(0);
                    FieldNode field = node.fields.get(0);
                    if (method.name.equals("<clinit>") && method.desc.equals("()V")
                            && field.desc.equals("[I")
                            && (field.access & Opcodes.ACC_STATIC) != 0) {
                        FieldReference fieldRef = new FieldReference(node.name, field);
                        String enumName = null;
                        AbstractInsnNode instruction = method.instructions.getFirst();
                        while (instruction != null) {
                            if (instruction instanceof FieldInsnNode && instruction.getOpcode() == Opcodes.GETSTATIC) {
                                FieldInsnNode fieldInstruction = (FieldInsnNode) instruction;
                                if (fieldRef.equals(new FieldReference(fieldInstruction))) {
                                    AbstractInsnNode next = instruction.getNext();
                                    while (next instanceof FrameNode || next instanceof LabelNode) {
                                        // ASM is sometimes not so nice
                                        next = next.getNext();
                                    }
                                    if (next instanceof FieldInsnNode && next.getOpcode() == Opcodes.GETSTATIC) {
                                        if (enumName == null) {
                                            enumName = ((FieldInsnNode) next).owner;
                                        } else if (!enumName.equals(((FieldInsnNode) next).owner)) {
                                            enumName = null;
                                            break; // It may not be a switchmap field
                                        }
                                    }
                                }
                            }
                            instruction = instruction.getNext();
                        }
                        if (enumName != null) {
                            if (fieldRef.getName().indexOf('$') == -1) {
                                // The deobf name will be something like $SwitchMap$org$bukkit$Material
                                String newName = "$SwitchMap$" + enumName.replace('/', '$');
                                deobfNames.put(fieldRef, newName);
                                instruction = method.instructions.getFirst();
                                // Remap references within this class
                                while (instruction != null) {
                                    if (instruction instanceof FieldInsnNode) {
                                        FieldInsnNode fieldInsn = (FieldInsnNode) instruction;
                                        if ((fieldInsn.getOpcode() == Opcodes.GETSTATIC || fieldInsn.getOpcode() == Opcodes.PUTSTATIC)
                                                && fieldInsn.owner.equals(node.name)
                                                && fieldRef.equals(new FieldReference(fieldInsn))) {
                                            fieldInsn.name = newName;
                                        }
                                    }
                                    instruction = instruction.getNext();
                                }
                                // Remap the actual field declaration
                                // Switch maps can only contain a single field and we have already obtained said field, so it isn't much of a deal here
                                field.name = newName;
                            }
                        }
                    }
                }
            }
        }

        // Rename references to the field
        for (ClassNode node : nodes) {
            Set<String> addedInnerClassNodes = new HashSet<>();
            for (MethodNode method : node.methods) {
                AbstractInsnNode instruction = method.instructions.getFirst();
                while (instruction != null) {
                    if (instruction instanceof FieldInsnNode && instruction.getOpcode() == Opcodes.GETSTATIC) {
                        FieldInsnNode fieldInstruction = (FieldInsnNode) instruction;
                        if (fieldInstruction.owner.equals(node.name)) { // I have no real idea what I was doing here
                            instruction = instruction.getNext();
                            continue;
                        }
                        FieldReference fRef = new FieldReference(fieldInstruction);
                        String newName = deobfNames.get(fRef);
                        if (newName != null) {
                            fieldInstruction.name = newName;
                            if (!addedInnerClassNodes.contains(fRef.getOwner())) {
                                InnerClassNode innerClassNode = new InnerClassNode(fRef.getOwner(), node.name, null, Opcodes.ACC_STATIC ^ Opcodes.ACC_SYNTHETIC ^ Opcodes.ACC_FINAL);
                                ClassNode outerNode = nameToNode.get(fRef.getOwner());
                                if (outerNode != null) {
                                    outerNode.innerClasses.add(innerClassNode);
                                }
                                ClassNode outermostClassnode = null;
                                if (node.outerClass != null) {
                                    outermostClassnode = nameToNode.get(node.outerClass);
                                }
                                if (outermostClassnode == null) {
                                    for (InnerClassNode inner : node.innerClasses) {
                                        if (inner.name.equals(node.name) && inner.outerName != null) {
                                            outermostClassnode = nameToNode.get(inner.outerName);
                                            break;
                                        }
                                    }
                                }
                                if (outermostClassnode != null) {
                                    outermostClassnode.innerClasses.add(innerClassNode);
                                }
                                node.innerClasses.add(innerClassNode);
                            }
                        }
                    }
                    instruction = instruction.getNext();
                }
            }
        }

        if (doLogging) {
            System.out.printf("Recovered %d switch-on-enum switchmap classes! (%d ms)\n", deobfNames.size(), System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Guesses anonymous inner classes by checking whether they have a synthetic field and if they
     * do whether they are referenced only by a single "parent" class.
     * Note: this method is VERY aggressive when it comes to adding inner classes, sometimes it adds
     * inner classes on stuff where it wouldn't belong. This  means that useage of this method should
     * be done wiseley. This method will do some damage even if it does no good.
     *
     * @param doLog whether to perfom any logging via System.out
     */
    public void guessAnonymousInnerClasses(boolean doLog) {
        long start = System.currentTimeMillis();

        // Class name -> referenced class, method
        // I am well aware that we are using method node, but given that there can be multiple methods with the same
        // name it is better to use MethodNode instead of String to reduce object allocation overhead.
        // Should we use triple instead? Perhaps.
        HashMap<String, Map.Entry<String, MethodNode>> candidates = new LinkedHashMap<>();
        for (ClassNode node : nodes) {
            if ((node.access & VISIBILITY_MODIFIERS) != 0) {
                continue; // Anonymous inner classes are always package-private
            }
            boolean skipClass = false;
            FieldNode outerClassReference = null;
            for (FieldNode field : node.fields) {
                final int requiredFlags = Opcodes.ACC_SYNTHETIC | Opcodes.ACC_FINAL;
                if ((field.access & requiredFlags) == requiredFlags
                        && (field.access & VISIBILITY_MODIFIERS) == 0) {
                    if (outerClassReference != null) {
                        skipClass = true;
                        break; // short-circuit
                    }
                    outerClassReference = field;
                }
            }
            if (skipClass || outerClassReference == null) {
                continue;
            }
            // anonymous classes can only have a single constructor since they are only created at a single spot
            // However they also have to have a constructor so they can pass the outer class reference
            MethodNode constructor = null;
            for (MethodNode method : node.methods) {
                if (method.name.equals("<init>")) {
                    if (constructor != null) {
                        // cant have multiple constructors
                        skipClass = true;
                        break; // short-circuit
                    }
                    if ((method.access & VISIBILITY_MODIFIERS) != 0) {
                        // The constructor should be package - protected
                        skipClass = true;
                        break;
                    }
                    constructor = method;
                }
            }
            if (skipClass || constructor == null) { // require a single constructor, not more, not less
                continue;
            }
            // since we have the potential reference to the outer class and we know that it has to be set
            // via the constructor's parameter, we can check whether this is the case here
            DescString desc = new DescString(constructor.desc);
            skipClass = true;
            while (desc.hasNext()) {
                String type = desc.nextType();
                if (type.equals(outerClassReference.desc)) {
                    skipClass = false;
                    break;
                }
            }
            if (skipClass) {
                continue;
            }
            if (node.name.indexOf('$') != -1 && !Character.isDigit(node.name.charAt(node.name.length() - 1))) {
                // Unobfuscated class that is 100% not anonymous
                continue;
            }
            candidates.put(node.name, null);
        }

        // Make sure that the constructor is only invoked in a single class, which should be the outer class
        for (ClassNode node : nodes) {
            for (MethodNode method : node.methods) {
                AbstractInsnNode instruction = method.instructions.getFirst();
                while (instruction != null) {
                    if (instruction instanceof MethodInsnNode && ((MethodInsnNode)instruction).name.equals("<init>")) {
                        MethodInsnNode methodInvocation = (MethodInsnNode) instruction;
                        String owner = methodInvocation.owner;
                        if (candidates.containsKey(owner)) {
                            if (owner.equals(node.name)) {
                                // this is no really valid anonymous class
                                candidates.remove(owner);
                            } else {
                                Map.Entry<String, MethodNode> invoker = candidates.get(owner);
                                if (invoker == null) {
                                    candidates.put(owner, Map.entry(node.name, method));
                                } else if (!invoker.getKey().equals(node.name)
                                        || !invoker.getValue().name.equals(method.name)
                                        || !invoker.getValue().desc.equals(method.desc)) {
                                    // constructor referenced by multiple classes, cannot be valid
                                    // However apparently these classes could be extended? I am not entirely sure how that is possible, but it is.
                                    // That being said, we are going to ignore that this is possible and just consider them invalid
                                    // as everytime this happens the decompiler is able to decompile the class without any issues.
                                    candidates.remove(owner);
                                }
                            }
                        }
                    }
                    instruction = instruction.getNext();
                }
            }
        }

        // If another class has a field reference to the potential anonymous class, and that field is not
        // synthetic, then the class is likely not anonymous.
        // In the future I could settle with not checking for the anonymous access flag, but this would
        // be quite the effort to get around nonetheless since previous steps of this method utilise
        // this access flag
        for (ClassNode node : nodes) {
            for (FieldNode field : node.fields) {
                if (field.desc.length() == 1 || (field.access & Opcodes.ACC_SYNTHETIC) != 0) {
                    continue;
                }
                if (field.desc.codePointAt(field.desc.lastIndexOf('[') + 1) != 'L') {
                    continue;
                }
                // Now technically, they are still inner classes. Just regular ones and they are not static ones
                // however not adding them as a inner class has no effect in recomplieabillity so we will not really care about it just yet.
                // TODO that being said, we should totally do it
                String className = field.desc.substring(field.desc.lastIndexOf('[') + 2, field.desc.length() - 1);
                candidates.remove(className);
            }
        }

        int addedInners = 0;
        for (Map.Entry<String, Map.Entry<String, MethodNode>> candidate : candidates.entrySet()) {
            String inner = candidate.getKey();
            Map.Entry<String, MethodNode> outer = candidate.getValue();
            if (outer == null) {
                continue;
            }
            ClassNode innerNode = nameToNode.get(inner);
            ClassNode outernode = nameToNode.get(outer.getKey());

            MethodNode outerMethod = outer.getValue();
            if (outernode == null) {
                continue;
            }
            boolean hasInnerClassInfoInner = false;
            for (InnerClassNode icn : innerNode.innerClasses) {
                if (icn.name.equals(inner)) {
                    hasInnerClassInfoInner = true;
                    break;
                }
            }
            boolean hasInnerClassInfoOuter = false;
            for (InnerClassNode icn : outernode.innerClasses) {
                if (icn.name.equals(inner)) {
                    hasInnerClassInfoOuter = true;
                    break;
                }
            }
            if (hasInnerClassInfoInner && hasInnerClassInfoOuter) {
                continue;
            }
            InnerClassNode newInnerClassNode = new InnerClassNode(inner, null, null, 16400);
            if (!hasInnerClassInfoInner) {
                innerNode.outerMethod = outerMethod.name;
                innerNode.outerMethodDesc = outerMethod.desc;
                innerNode.outerClass = outernode.name;
                innerNode.innerClasses.add(newInnerClassNode);
            }
            if (!hasInnerClassInfoOuter) {
                outernode.innerClasses.add(newInnerClassNode);
            }
            addedInners++;
        }

        if (doLog) {
            System.out.printf(Locale.ROOT, "Added %d inner class nodes for anonymous classes. (%d ms)\n", addedInners, System.currentTimeMillis() - start);
        }
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
                        // I *might* use this later, but right now we do not
                        // LabelNode loopStartLabel = (LabelNode) next;
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
                            // Quiltflower has a bug where it does not correctly identify LVT entires
                            // and acts as if they weren't there. This preciesely occours as the decompiler
                            // expects that the start label provided by of the LVT entry is equal to the first declaration of the
                            // entry. While I have already brought forward a fix for this, unfortunately this results in a few other
                            // (more serious) issues that result in formerly broken but technically correct and compileable code
                            // being uncompileable. This makes it unlikely that the fix would be pushed anytime soon.
                            // My assumption is that this has something to do with another bug in the decompiler,
                            // but in the meantime I guess that we will have to work around this bug by adding a LabelNode
                            // just before the first astore operation.
                            // Developers have to make sacrifices to attain perfection after all
                            LabelNode firstDeclaration = new LabelNode();
                            method.instructions.insertBefore(iteratedObject, firstDeclaration);
                            // add LVT entry for the iterator
                            LocalVariableNode lvtNode = new LocalVariableNode(
                                    "var" + iteratedObject.var, suggestion,
                                    null,
                                    firstDeclaration, loopEndLabel, iteratedObject.var);
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
                nameToNode.put(node.name, node);
            }
        });
        System.out.println("Oaktree indexed class files!");
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
