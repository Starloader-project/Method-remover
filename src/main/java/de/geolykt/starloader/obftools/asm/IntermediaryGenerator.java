package de.geolykt.starloader.obftools.asm;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import net.fabricmc.stitch.util.FieldNameFinder;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

public class IntermediaryGenerator {

    private final File input;
    private final File map;
    private final File output;

    public IntermediaryGenerator(File input, File map, File output) {
        this.input = input;
        this.map = map;
        this.output = output;
    }

    public void remapClasses() {
        try {
            JarFile inJar = new JarFile(input);
            BufferedWriter buffWriter = new BufferedWriter(new FileWriter(map));

            buffWriter.write("v1\tofficial\tintermediary\n");
            Enumeration<JarEntry> entries = inJar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                char[] nameChars = entry.getName().toCharArray();
                if (nameChars.length < 9 || nameChars[nameChars.length - 8] != '/') {
                    continue;
                }
                char[] jvmName = new char[nameChars.length - 6];
                System.arraycopy(nameChars, 0, jvmName, 0, nameChars.length - 6);
                nameChars = null;

                buffWriter.write("CLASS\t");
                buffWriter.write(jvmName);
                buffWriter.write('\t');
                char[] newName = new char[jvmName.length + 6];
                System.arraycopy(jvmName, 0, newName, 0, jvmName.length - 1); // head
                newName[newName.length - 1] = jvmName[jvmName.length - 1]; // tail
                System.arraycopy("class_".toCharArray(), 0, newName, newName.length - 7, 6); // class_
                buffWriter.write(newName);
                buffWriter.write('\n'); // The tiny format does not make use of system-dependent newlines
            }
            inJar.close();
            buffWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Proposes new field names (for example enum member names) that can be easily guessed by the computer.
     */
    @SuppressWarnings("deprecation")
    public void doProposeFields() {
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(map, true));
            var generatedFieldNames = new FieldNameFinder().findNames(input);
            for (var proposal : generatedFieldNames.entrySet()) {
                var key = proposal.getKey();
                String suggestedValue = proposal.getValue();
                if (key.getName().length() > 2 || key.getName().equals(suggestedValue)) {
                    // It is unlikely that this entry is obfuscated
                    continue;
                }
                bw.write("FIELD\t");
                bw.write(key.getOwner());
                bw.write('\t');
                bw.write(key.getDesc());
                bw.write('\t');
                bw.write(key.getName());
                bw.write('\t');
                bw.write(suggestedValue);
                bw.write('\n');
            }
            bw.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void deobfuscate() {
        TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(map.toPath(), "official", "intermediary"))
                .renameInvalidLocals(true)
                .threads(-1)
                .build();

        try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(output.toPath()).build()) {
            outputConsumer.addNonClassFiles(input.toPath(), NonClassCopyMode.FIX_META_INF, remapper);
            remapper.readInputs(input.toPath());
            remapper.apply(outputConsumer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            remapper.finish();
        }
    }
}
