package de.geolykt.starloader.methodremover;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;

public class MethodremoverGradlePlugin implements Plugin<Project> {

    @Override
    public void apply(Project gradleProject) {
        RemoverGradleExtension extension = gradleProject.getExtensions().create("methodRemover", RemoverGradleExtension.class);
        MethodRemovalTask transformerTask = gradleProject.getTasks().create("methodRemoval", MethodRemovalTask.class, extension);
        gradleProject.afterEvaluate(project -> {
            Task jarTask = project.getTasks().getByName("jar");
            transformerTask.dependsOn(jarTask);
        });
    }
}
