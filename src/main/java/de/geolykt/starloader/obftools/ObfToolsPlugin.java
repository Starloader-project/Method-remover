package de.geolykt.starloader.obftools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.transform.ByteArrayZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformerEntry;

import de.geolykt.starloader.obftools.asm.Oaktree;

import cuchaz.enigma.command.ConvertMappingsCommand;
import cuchaz.enigma.command.DeobfuscateCommand;
import net.fabricmc.accesswidener.AccessWidener;
import net.fabricmc.accesswidener.AccessWidenerReader;
import net.fabricmc.accesswidener.AccessWidenerVisitor;
import net.fabricmc.stitch.commands.tinyv2.CommandProposeV2FieldNames;
import net.fabricmc.stitch.util.FieldNameFinder;

public class ObfToolsPlugin implements Plugin<Project> {

    public static final String FOLDER = "build/obftools/";
    public static final String INTERMEDIARY_JAR = FOLDER + "intermediary.jar";
    public static final String INTERMEDIARY_MAP = FOLDER + "slintermediary.tiny";

    @Override
    public void apply(Project gradleProject) {
        ObftoolsExtension extension = gradleProject.getExtensions().create("obfTools", ObftoolsExtension.class);
        PostprocessTask transformerTask = gradleProject.getTasks().create("postprocess", PostprocessTask.class,
                extension);
        gradleProject.afterEvaluate(project -> {
            if (extension.accessWidener != null) {
                doAW(extension, gradleProject);
            }
            Task jarTask = project.getTasks().getByName("jar");
            transformerTask.dependsOn(jarTask);
            if (extension.affectedJar != null) {
                File f = gradleProject.file(extension.affectedJar);
                File map = gradleProject.file(INTERMEDIARY_MAP);
                if (!f.exists()) {
                    throw new RuntimeException("Could not find the specified jar which should be at "
                            + f.getAbsolutePath());
                }
                if (map.exists()) {
                    // Already computed, no need to do it
                    return;
                }
                map.getParentFile().mkdirs();
                remapClasses(f, map);/*
                Command command = new CommandGenerateIntermediary();
                String[] additionallOpts = "-i galimulator -p \"^snoddasmannen\\/galimulator\\/(.+\\/)*[a-zA-Z]{1,2}$\" -t \"snoddasmannen/galimulator/\" --keep-package --only-class-names"
                        .split(" ");
                String[] args = new String[additionallOpts.length + 2];
                System.arraycopy(additionallOpts, 0, args, 2, additionallOpts.length);
                args[0] = f.getAbsolutePath();
                args[1] = map.getAbsolutePath();
                try {
                    command.run(args);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }*/
                doProposeFields(f, map);
//                net.fabricmc.tinyremapper.Main.main(new String[] { f.getAbsolutePath(), gradleProject.file(INTERMEDIARY_JAR).getAbsolutePath(),
//                        map.getAbsolutePath(), "intermediary", "named" });*/
//                net.fabricmc.tinyremapper.Main.main(new String[] { f.getAbsolutePath(), gradleProject.file(INTERMEDIARY_JAR).getAbsolutePath(),
//                        map.getAbsolutePath(), "official", "intermediary" });
                File intermediaryJar = gradleProject.file(INTERMEDIARY_JAR);
                try {
                    new DeobfuscateCommand().run(
                            f.getAbsolutePath(), // input
                            intermediaryJar.getAbsolutePath(), // output
                            map.getAbsolutePath()); // map
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
                Oaktree deobfuscator = new Oaktree();
                try {
                    JarFile jar = new JarFile(intermediaryJar);
                    deobfuscator.index(jar);
                    jar.close();
                    deobfuscator.fixInnerClasses();
                    deobfuscator.fixLVT();
                    FileOutputStream fos = new FileOutputStream(intermediaryJar);
                    deobfuscator.write(fos);
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public void remapClasses(File inFile, File outMap) {
        try {
            JarFile inJar = new JarFile(inFile);
            BufferedWriter buffWriter = new BufferedWriter(new FileWriter(outMap));

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
     *
     * @param jar The input jar
     * @param map The input/output map
     */
    @SuppressWarnings("deprecation")
    public void doProposeFields(File jar, File map) {
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(map, true));
            var generatedFieldNames = new FieldNameFinder().findNames(jar);
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
//                System.out.println(a.getKey() + " -> " + a.getValue());
            }
            bw.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Uses the stitch command to propose fields.
     *
     * @param jar The input jar
     * @param map The input map
     */
    public void doProposeFieldsStitch(File jar, File map) {
        try {
            File temp = new File(map.getParent(), map.getName().concat(".temp"));
            new ConvertMappingsCommand().run("tiny", map.getAbsolutePath(), "tiny_v2", temp.getAbsolutePath());
            map.delete();
            new CommandProposeV2FieldNames().run(new String[] {jar.getAbsolutePath(), temp.getAbsolutePath(), map.getAbsolutePath(), "true"});
//            temp.delete();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void doAW(ObftoolsExtension extension, Project project) {
        File accessWidenerFile = project.file(extension.accessWidener);
        if (!accessWidenerFile.exists()) {
            throw new RuntimeException("Could not find the specified access widener file which should be at "
                    + accessWidenerFile.getAbsolutePath());
        }

        File affectedFile = project.file(extension.affectedJar);
        if (!affectedFile.exists()) {
            throw new RuntimeException("Affected file was not found: " + affectedFile.getAbsolutePath());
        }

        AccessWidener accessWidener = new AccessWidener();
        AccessWidenerReader accessWidenerReader = new AccessWidenerReader(accessWidener);

        try (BufferedReader reader = new BufferedReader(new FileReader(accessWidenerFile))) {
            accessWidenerReader.read(reader);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read project access widener file");
        }
        if (accessWidener.getTargets().isEmpty()) {
            return;
        }
        ZipEntryTransformerEntry[] transformers = accessWidener.getTargets().stream()
                .map(string -> new ZipEntryTransformerEntry(string.replaceAll("\\.", "/") + ".class", getTransformer(accessWidener)))
                .toArray(ZipEntryTransformerEntry[]::new);
        ZipUtil.transformEntries(affectedFile, transformers);
    }

    private ZipEntryTransformer getTransformer(AccessWidener accessWidener) {
        return new ByteArrayZipEntryTransformer() {
            @Override
            protected byte[] transform(ZipEntry zipEntry, byte[] input) {
                ClassReader reader = new ClassReader(input);
                ClassWriter writer = new ClassWriter(0);
                ClassVisitor classVisitor = AccessWidenerVisitor.createClassVisitor(Opcodes.ASM9, writer,
                        accessWidener);
                reader.accept(classVisitor, 0);
                return writer.toByteArray();
            }
        };
    }
}
