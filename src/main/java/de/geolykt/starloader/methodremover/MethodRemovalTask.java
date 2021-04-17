package de.geolykt.starloader.methodremover;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.internal.file.copy.CopyActionProcessingStream;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.WorkResults;
import org.gradle.jvm.tasks.Jar;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.commons.IOUtils;
import org.zeroturnaround.zip.transform.ByteArrayZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformer;

public class MethodRemovalTask extends Jar {

    private final RemoverGradleExtension extension;

    public MethodRemovalTask(final RemoverGradleExtension extension) {
        super();
        this.extension = extension;
    }

    @Override
    protected CopyAction createCopyAction() {
        return new MethodRemoverCopyAction(extension.annotation, getArchiveFile().get().getAsFile(), getDestinationDirectory().file("output.jar").get().getAsFile());
    }
} class MethodRemoverCopyAction implements CopyAction {

    private final String annotation;
    private final File src;
    private final File target;

    public MethodRemoverCopyAction(String annotation, File target, File source) {
        this.annotation = annotation;
        this.target = target;
        this.src = source;
    }

    @Override
    public WorkResult execute(CopyActionProcessingStream stream) {
        try (FileOutputStream fos = new FileOutputStream(target)) {
            transform(annotation, src, fos);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return WorkResults.didWork(true);
    }

    private static void transform(String annot, File zip, OutputStream out) {
        ZipOutputStream outputStream = new ZipOutputStream(new BufferedOutputStream(out));
        try {
            ZipEntryTransformer transformer = getTransformer(annot);
            ZipUtil.iterate(zip, (input, zipEntry) -> transformer.transform(input, zipEntry, outputStream));
        } finally {
            IOUtils.closeQuietly(out);
        }
    }

    private static ZipEntryTransformer getTransformer(String annotation) {
        return new ByteArrayZipEntryTransformer() {
            @Override
            protected byte[] transform(ZipEntry zipEntry, byte[] input) {
                    ClassReader reader = new ClassReader(input);
                    ClassWriter writer = new ClassWriter(0);
                    ClassVisitor visitor = new ClassremoverVisitor(Opcodes.ASM9, writer, annotation, input);
                    reader.accept(visitor, 0);
                    return writer.toByteArray();
            }
        };
    }
}
