package de.geolykt.starloader.methodremover;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;

public class MethodremoverGradlePlugin implements Plugin<Project> {

    @Override
    public void apply(Project gradleProject) {
        RemoverGradleExtension extension = gradleProject.getExtensions().create("method-remover", RemoverGradleExtension.class);
        MethodRemovalTask transformerTask = new MethodRemovalTask(extension);
        Task jarTask = gradleProject.getTasks().getByName("jar");
        transformerTask.dependsOn(jarTask);
        gradleProject.getTasks().add(transformerTask);
    }
}
