package de.geolykt.starloader.obftools.asm;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import de.geolykt.starloader.obftools.asm.remapper.Remapper;

import net.fabricmc.stitch.util.FieldNameFinder;

public class IntermediaryGenerator {

    private final File input;
    private final File map;
    private final File output;
    private final Remapper remapper = new Remapper();

    private final List<ClassNode> nodes = new ArrayList<>();
    private final List<Map.Entry<String, byte[]>> resources = new ArrayList<>();

    public IntermediaryGenerator(File input, File output) {
        this(input, null, output);
    }

    public IntermediaryGenerator(File input) {
        this(input, null, null);
    }

    public IntermediaryGenerator(File input, File map, File output) {
        this.input = input;
        this.map = map;
        this.output = output;
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

    public void remapClasses() {
        BufferedWriter bw = null;
        if (map != null) {
            try {
                @SuppressWarnings("resource")
                BufferedWriter dontcomplain = new BufferedWriter(new FileWriter(map, StandardCharsets.UTF_8, false));
                bw = dontcomplain;
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

    /**
     * Proposes new field names (for example enum member names) that can be easily guessed by the computer.
     */
    @SuppressWarnings("deprecation")
    public void doProposeFields() {
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
        var generatedFieldNames = new FieldNameFinder().findNames(input);
        for (var proposal : generatedFieldNames.entrySet()) {
            var key = proposal.getKey();
            String suggestedValue = proposal.getValue();
            if (key.getName().length() > 2 || key.getName().equals(suggestedValue)) {
                // It is unlikely that this entry is obfuscated
                continue;
            }
            if (bw != null) {
                try {
                    bw.write("FIELD\t");
                    bw.write(key.getOwner());
                    bw.write('\t');
                    bw.write(key.getDesc());
                    bw.write('\t');
                    bw.write(key.getName());
                    bw.write('\t');
                    bw.write(suggestedValue);
                    bw.write('\n');
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            remapper.remapField(key.getOwner(), key.getDesc(), key.getName(), suggestedValue);
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

    public List<ClassNode> getAsClassNodes() {
        return nodes;
    }
}
