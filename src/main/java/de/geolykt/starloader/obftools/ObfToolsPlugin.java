package de.geolykt.starloader.obftools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
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

import de.geolykt.starloader.obftools.asm.IntermediaryGenerator;
import de.geolykt.starloader.obftools.asm.Oaktree;

import net.fabricmc.accesswidener.AccessWidener;
import net.fabricmc.accesswidener.AccessWidenerReader;
import net.fabricmc.accesswidener.AccessWidenerVisitor;

public class ObfToolsPlugin implements Plugin<Project> {

    public static final String FOLDER = "build/obftools/";
    public static final String INTERMEDIARY_JAR = FOLDER + "intermediary.jar";
    public static final String INTERMEDIARY_MAP = FOLDER + "slintermediary.tiny";

    @Override
    public void apply(Project gradleProject) {
        @SuppressWarnings("null")
        ObftoolsExtension extension = gradleProject.getExtensions().create("obfTools", ObftoolsExtension.class);

        @SuppressWarnings("null")
        PostprocessTask transformerTask = gradleProject.getTasks().create("postprocess", PostprocessTask.class, extension);

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
                File intermediaryJar = gradleProject.file(INTERMEDIARY_JAR);
                IntermediaryGenerator generator = new IntermediaryGenerator(f, map, intermediaryJar);
                generator.remapClasses();
                generator.doProposeEnumFieldsV2();
                generator.deobfuscate();
                Oaktree deobfuscator = new Oaktree();
                try {
                    JarFile jar = new JarFile(intermediaryJar);
                    deobfuscator.index(jar);
                    jar.close();
                    deobfuscator.fixInnerClasses();
                    deobfuscator.fixParameterLVT();
                    deobfuscator.guessFieldGenerics();
                    deobfuscator.fixSwitchMaps(true);
                    deobfuscator.fixForeachOnArray(true);
                    deobfuscator.fixComparators(true, true);
                    FileOutputStream fos = new FileOutputStream(intermediaryJar);
                    deobfuscator.write(fos);
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
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
