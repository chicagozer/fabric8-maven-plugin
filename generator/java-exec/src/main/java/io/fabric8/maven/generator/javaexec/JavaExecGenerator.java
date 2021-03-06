package io.fabric8.maven.generator.javaexec;
/*
 *
 * Copyright 2016 Roland Huss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import io.fabric8.maven.core.util.Configs;
import io.fabric8.maven.core.util.MavenUtil;
import io.fabric8.maven.docker.config.AssemblyConfiguration;
import io.fabric8.maven.docker.config.BuildImageConfiguration;
import io.fabric8.maven.docker.config.ImageConfiguration;
import io.fabric8.maven.generator.api.FromSelector;
import io.fabric8.maven.generator.api.GeneratorContext;
import io.fabric8.maven.generator.api.support.BaseGenerator;
import io.fabric8.utils.Strings;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.assembly.model.Assembly;
import org.apache.maven.plugin.assembly.model.DependencySet;
import org.apache.maven.plugin.assembly.model.FileSet;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.*;

/**
 * @author roland
 * @since 21/09/16
 */

public class JavaExecGenerator extends BaseGenerator {

    // Environment variable used for specifying a main class
    private static final String JAVA_MAIN_CLASS_ENV_VAR = "JAVA_MAIN_CLASS";

    // Plugins indicating a plain java build
    private static final String[] JAVA_EXEC_MAVEN_PLUGINS = new String[] {
        "org.codehaus.mojo:exec-maven-plugin",
        "org.apache.maven.plugins:maven-shade-plugin"
    };

    private final FatJarDetector fatJarDetector;
    private final MainClassDetector mainClassDetector;

    public JavaExecGenerator(GeneratorContext context) {
        this(context, "java-exec");
    }

    protected JavaExecGenerator(GeneratorContext context, String name) {
        super(context, name, new FromSelector.Default(context, "java"));
        fatJarDetector = new FatJarDetector(getProject().getBuild().getDirectory());
        mainClassDetector = new MainClassDetector(getConfig(Config.mainClass),
                                                  new File(getProject().getBuild().getOutputDirectory()),
                                                  context.getLogger());
    }

    public enum Config implements Configs.Key {
        // Webport to expose. Set to 0 if no port should be exposed
        webPort        {{ d = "8080"; }},

        // Jolokia from the base image to expose. Set to 0 if no such port should be exposed
        jolokiaPort    {{ d = "8778"; }},

        // Prometheus port from base image. Set to 0 if no required
        prometheusPort {{ d = "9779"; }},

        // Basedirectory where to put the application data into (within the Docker image
        targetDir {{d = "/deployments"; }},

        // The name of the main class for non-far jars. If not speficied it is tried
        // to find a main class within target/classes.
        mainClass,

        // Reference to a predefined assembly descriptor to use. By defult it is tried to be detected
        assemblyRef;

        public String def() { return d; } protected String d;
    }

    @Override
    public boolean isApplicable(List<ImageConfiguration> configs) throws MojoExecutionException {
        if (shouldAddImageConfiguration(configs)) {
            // If a main class is configured, we always kick in
            if (getConfig(Config.mainClass) != null) {
                return true;
            }
            // Check for the existing of plugins indicating a plain java exec app
            for (String plugin : JAVA_EXEC_MAVEN_PLUGINS) {
                if (MavenUtil.hasPlugin(getProject(), plugin)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public List<ImageConfiguration> customize(List<ImageConfiguration> configs, boolean prePackagePhase) throws MojoExecutionException {
        ImageConfiguration.Builder imageBuilder = new ImageConfiguration.Builder();
        BuildImageConfiguration.Builder buildBuilder = null;
        buildBuilder = new BuildImageConfiguration.Builder()
            .from(getFrom())
            .ports(extractPorts());
        if (!prePackagePhase) {
            // Only add assembly if not in a pre-package phase where the referenced files
            // won't be available.
            buildBuilder.assembly(createAssembly());
        }
        Map<String, String> envMap = getEnv(prePackagePhase);
        envMap.put("JAVA_APP_DIR", getConfig(Config.targetDir));
        buildBuilder.env(envMap);
        addLatestTagIfSnapshot(buildBuilder);
        imageBuilder
            .name(getImageName())
            .alias(getAlias())
            .buildConfig(buildBuilder.build());
        configs.add(imageBuilder.build());
        return configs;
    }

    /**
     * Hook for adding extra environment vars
     *
     * @return map with environment variables to use
     * @param prePackagePhase
     */
    protected Map<String, String> getEnv(boolean prePackagePhase) throws MojoExecutionException {
        Map<String, String> ret = new HashMap<>();
        if (!isFatJar()) {
            String mainClass = getConfig(Config.mainClass);
            if (mainClass == null) {
                mainClassDetector.getMainClass();
                if (mainClass == null) {
                    if (prePackagePhase) {
                        return ret;
                    } else {
                        throw new MojoExecutionException("Cannot extract main class to startup");
                    }
                }
                ret.put(JAVA_MAIN_CLASS_ENV_VAR, mainClass);
            }
        }
        return ret;
    }

    protected AssemblyConfiguration createAssembly() throws MojoExecutionException {
        AssemblyConfiguration.Builder builder = new AssemblyConfiguration.Builder().targetDir(getConfig(Config.targetDir));
        addAssembly(builder);
        return builder.build();
    }

    protected void addAssembly(AssemblyConfiguration.Builder builder) throws MojoExecutionException {
        String assemblyRef = getConfig(Config.assemblyRef);
        if (assemblyRef != null) {
            builder.descriptorRef(assemblyRef);
        } else {
            if (isFatJar()) {
                FatJarDetector.Result fatJar = detectFatJar();
                Assembly assembly = new Assembly();
                MavenProject project = getProject();
                if (fatJar == null) {
                    DependencySet dependencySet = new DependencySet();
                    dependencySet.addInclude(project.getGroupId() + ":" + project.getArtifactId());
                    assembly.addDependencySet(dependencySet);
                } else {
                    FileSet fileSet = new FileSet();
                    File buildDir = new File(project.getBuild().getDirectory());
                    fileSet.setDirectory(toRelativePath(buildDir, project.getBasedir()));
                    fileSet.addInclude(toRelativePath(fatJar.getArchiveFile(), buildDir));
                    fileSet.setOutputDirectory(".");
                    assembly.addFileSet(fileSet);
                }
                assembly.addFileSet(createFileSet("src/main/fabric8-includes/bin","bin","0755","0755"));
                assembly.addFileSet(createFileSet("src/main/fabric8-includes",".","0644","0755"));
                builder.assemblyDef(assembly);
            } else {
                builder.descriptorRef("artifact-with-dependencies");
            }
        };
    }

    private String toRelativePath(File archiveFile, File basedir) {
        String absolutePath = archiveFile.getAbsolutePath();
        absolutePath = absolutePath.replace('\\', '/');
        String basedirPath = basedir.getAbsolutePath().replace('\\', '/');
        return absolutePath.startsWith(basedirPath) ?
            absolutePath.substring(basedirPath.length() + 1) :
            absolutePath;
    }

    private FileSet createFileSet(String sourceDir, String outputDir, String fileMode, String directoryMode) {
        FileSet fileSet = new FileSet();
        fileSet.setDirectory(sourceDir);
        fileSet.setOutputDirectory(outputDir);
        fileSet.setFileMode(fileMode);
        fileSet.setDirectoryMode(directoryMode);
        return fileSet;
    }

    protected boolean isFatJar() throws MojoExecutionException {
        return !hasMainClass() && detectFatJar() != null;
    }

    protected boolean hasMainClass() {
        return Boolean.parseBoolean(getConfig(Config.mainClass,"false"));
    }

    public FatJarDetector.Result detectFatJar() throws MojoExecutionException {
        return fatJarDetector.scan();
    }

    protected List<String> extractPorts() {
        // TODO would rock to look at the base image and find the exposed ports!
        List<String> answer = new ArrayList<>();
        addPortIfValid(answer, getConfig(Config.webPort));
        addPortIfValid(answer, getConfig(Config.jolokiaPort));
        addPortIfValid(answer, getConfig(Config.prometheusPort));
        return answer;
    }

    private void addPortIfValid(List<String> list, String port) {
        if (Strings.isNotBlank(port) && Integer.parseInt(port) != 0) {
            list.add(port);
        }
    }
}
