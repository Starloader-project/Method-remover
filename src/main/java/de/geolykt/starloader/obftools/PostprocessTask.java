package de.geolykt.starloader.obftools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import javax.inject.Inject;

import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.internal.file.copy.CopyActionProcessingStream;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.WorkResults;
import org.gradle.jvm.tasks.Jar;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import de.geolykt.starloader.obftools.asm.remapper.Remapper;
import de.geolykt.starloader.obftools.asm.remapper.RemapperUtils;

public class PostprocessTask extends Jar {

    private final ObftoolsExtension extension;

    @Inject
    public PostprocessTask(final ObftoolsExtension extension) {
        super();
        this.extension = extension;
    }

    @Override
    protected CopyAction createCopyAction() {
        File source = getArchiveFile().get().getAsFile();
        File map = new File(source.getParentFile().getParentFile().getParentFile(), ObfToolsPlugin.INTERMEDIARY_MAP);
        return new TransformedCopyTask(extension.annotation, source, source, map);
    }
} class TransformedCopyTask implements CopyAction {

    private final String annotation;
    private final File mapLocation;
    private final File src;
    private final File targetFinal;

    public TransformedCopyTask(String annotation, File targetFinal, File source, File mapLocation) {
        this.annotation = annotation;
        this.targetFinal = targetFinal;
        this.src = source;
        this.mapLocation = mapLocation;
    }

    @Override
    public WorkResult execute(CopyActionProcessingStream stream) {
        List<ClassNode> nodes = new ArrayList<>();
        List<Map.Entry<String, byte[]>> resources = new ArrayList<>();
        Remapper remapper = new Remapper();

        try {
            RemapperUtils.readReversedTinyV1File(mapLocation, remapper);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            JarFile inJar = new JarFile(src);
            Enumeration<JarEntry> entries = inJar.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                InputStream is = inJar.getInputStream(entry);
                if (!entry.getName().endsWith(".class")) {
                    if (entry.getName().endsWith(".accesswidener")) {
                        ByteArrayOutputStream remappedStream = new ByteArrayOutputStream();
                        remapper.remapAccesswidener(is, remappedStream);
                        resources.add(Map.entry(entry.getName(), remappedStream.toByteArray()));
                    } else {
                        resources.add(Map.entry(entry.getName(), is.readAllBytes()));
                    }
                    is.close();
                    continue;
                }
                // TODO refractor this a second time - this is an eyesore
                ClassNode originalNode = new ClassNode(Opcodes.ASM9);
                ClassReader reader = new ClassReader(is);
                reader.accept(originalNode, 0);
                ClassNode newNode = new ClassNode(Opcodes.ASM9);
                ClassremoverVisitor crv = new ClassremoverVisitor(Opcodes.ASM9, newNode, annotation, originalNode);
                originalNode.accept(crv);
                nodes.add(newNode);
                is.close();
            }
            inJar.close();
            remapper.addTargets(nodes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        remapper.process();

        try (JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(targetFinal))) {
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
            throw new RuntimeException(e);
        }
        return WorkResults.didWork(true);
    }
}
