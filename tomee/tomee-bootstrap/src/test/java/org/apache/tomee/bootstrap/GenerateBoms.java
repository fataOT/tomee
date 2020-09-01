/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomee.bootstrap;

import lombok.Data;
import lombok.Getter;
import lombok.ToString;
import org.apache.openejb.loader.Files;
import org.apache.openejb.loader.IO;
import org.apache.openejb.loader.JarLocation;
import org.apache.openejb.loader.Zips;
import org.apache.openejb.util.Join;
import org.apache.openejb.util.Strings;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The BOM files in the boms/tomee-microprofile/ and directories are
 * generated by this class.
 *
 * The goal of each BOM is to be completely identical to the respective
 * standalone server zip.  Each library from the zip is translated back
 * to its respective maven coordinate and placed in the pom with all
 * transitive dependencies removed.  Thus the pom is a 1-to-1 mapping
 * of the server zip itself with zero variance possible due to any
 * transitive Maven dependencies.
 *
 * Currently, the generation process is manual and should ideally be
 * done prior to release.
 *
 * @see GenerateBoms#main(String[]) to execute this generator
 */
public class GenerateBoms {

    private final File project;
    private final File boms;
    private final File dists;
    private final Repository repository;

    public GenerateBoms() {
        /*
         * Resolve all project paths relative to this built class
         * that lives in `tomee/tomee-bootstrap/target/test-classes/`
         *
         * We walk backwards from that directory till we find the
         * project root and then we can build all relative paths
         * from there.
         */
        final File testClasses = JarLocation.jarLocation(GenerateBoms.class);
        final File target = testClasses.getParentFile();
        final File tomeeBootstrap = target.getParentFile();
        final File tomee = tomeeBootstrap.getParentFile();

        this.project = tomee.getParentFile();
        this.boms = new File(project, "boms");
        this.dists = new File(tomee, "apache-tomee/target");

        { // Find the ~/.m2/repository directory
            final File junitJar = JarLocation.jarLocation(Test.class);
            final File version = junitJar.getParentFile();
            final File artifact = version.getParentFile();
            final File group = artifact.getParentFile();
            final File repository = group.getParentFile();
            this.repository = new Repository(repository);
        }

        Files.dir(project);
        Files.dir(boms);
        Files.dir(dists);
    }

    /**
     * Use this main method from your IDE to regenerate the TomEE BOM files.
     */
    public static void main(String[] args) throws Exception {
        new GenerateBoms().run();
    }

    /**
     * Navigate to `tomee/apache-tomee/target/` and collect any apache-tomee*.zip files.
     * Translate each into a Distribution instance that contains the complete list of
     *
     * @see GenerateBoms#asDistribution(File)
     */
    public void run() {
        if (!dists.exists()) {
            throw new IllegalStateException("Directory does not exist: " + dists.getAbsolutePath() + "\n" +
                    "Ensure the project has been built and the server zips exist prior to executing" +
                    " this generator.");
        }
        final List<Distribution> distributions = Stream.of(dists.listFiles())
                .filter(file -> file.getName().endsWith(".zip"))
                .filter(file -> file.getName().startsWith("apache-tomee-"))
                .map(this::asDistribution)
                .collect(Collectors.toList());

        verify(distributions);

        distributions.forEach(this::toBom);
    }

    /**
     * If there are files inside a zip that we could not map
     * back to an artifact in the local Maven repo, then the
     * corresponding BOM will be incomplete and we must throw
     * an error.
     *
     * People will not check warnings or log output of any kind
     * so we must be obnixious with this.
     */
    private void verify(final List<Distribution> distributions) {
        final List<Distribution> incomplete = distributions.stream()
                .filter(distribution -> distribution.getMissing().size() > 0)
                .collect(Collectors.toList());

        throw new IncompleteMappingException(incomplete);
    }

    /**
     * Overwrite (or create) the contents of the BOM files in:
     *
     *  - $project.dir/boms/tomee-microprofile
     *  - $project.dir/boms/tomee-webprofile
     *  - $project.dir/boms/tomee-plume
     *  - $project.dir/boms/tomee-plus
     */
    private void toBom(final Distribution distribution) {
        try {
            final URL url = this.getClass().getClassLoader().getResource("pom-template.xml");
            final String template = IO.slurp(url);

            final String dependencies = Join.join("", Artifact::asBomDep, distribution.getArtifacts());

            final String pom = template.replace("TomEE MicroProfile", distribution.getDisplayName())
                    .replace("tomee-microprofile", distribution.getName())
                    .replace("<!--dependencies-->", dependencies);

            final File dist = Files.mkdir(boms, distribution.getName());

            final File pomFile = new File(dist, "pom.xml");

            IO.copy(IO.read(pom), pomFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Represents a particular apache-tomee-foo-1.2.3.zip
     */
    @Data
    public static class Distribution {

        /**
         * The Maven coordinates for any jars found inside
         * either tomee.home/lib/* or tomee.home/bin/*
         */
        private final List<Artifact> artifacts = new ArrayList<>();

        /**
         * The names of any files in tomee.home/lib/* or
         * tomee.home/bin/* that could not be matched with
         * something from the local Maven repository
         */
        private final List<File> missing = new ArrayList<>();

        /**
         * The corresponding apache-tomee-foo-1.2.3.zip
         */
        private final File zip;

        /**
         * The short name of the distribution.  For example
         * `tomee-webprofile` for apache-tomee-webprofile-1.2.3.zip
         */
        private final String name;

        /**
         * The display name of the distribution.  For example
         * `TomEE WebProfile` for apache-tomee-webprofile-1.2.3.zip
         */
        private final String displayName;

        public Distribution(final File zip) {
            this.zip = zip;
            name = zip.getName()
                    .replaceFirst("-[0-9].*", "")
                    .replace("apache-", "");

            this.displayName = Stream.of(name.split("-"))
                    .map(Strings::ucfirst)
                    .map(s -> s.replace("ee", "EE"))
                    .map(s -> s.replace("profile", "Profile"))
                    .reduce((s, s2) -> s + " " + s2)
                    .get();
        }

        @Override
        public String toString() {
            return "Distribution{" +
                    "displayName=" + displayName +
                    ", name=" + name +
                    ", artifacts=" + artifacts.size() +
                    ", missing=" + missing.size() +
                    '}';
        }
    }

    /**
     * Extract the zip that represents a TomEE distribution.  Find
     * all jar files.  Match them up with jars in the local Maven
     * repository.  Identify any jars we could not match.  And finally,
     * return all the data so we have a canonical representation of
     * the distribution.
     */
    private Distribution asDistribution(final File zip) {
        final File tmpdir = Files.tmpdir();
        try {
            Zips.unzip(zip, tmpdir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot unzip " + zip.getAbsolutePath(), e);
        }

        final Distribution distribution = new Distribution(zip);

        final List<File> jars = Files.collect(tmpdir, ".*.jar");

        final Function<File, Artifact> from = file1 -> {
            try {
                return repository.from(file1);
            } catch (IllegalStateException e) {
                distribution.missing.add(file1);
                return null;
            }
        };

        jars.stream()
                .filter(jar -> !jar.getName().equals("bootstrap.jar"))
                .filter(jar -> !jar.getName().equals("catalina-ant.jar"))
                .filter(jar -> !jar.getName().startsWith("tomcat-i18n"))
                .map(from)
                .filter(Objects::nonNull)
                .sorted()
                .forEach(distribution.artifacts::add);

        return distribution;
    }

    /**
     * This class represents the local Maven repository and is used to
     * try and find the correct Maven coordinates for the jars inside
     * the tomee/lib/ and tomee/bin/ directories.
     *
     * This does not attempt any online resolution to Maven Central itself
     * as theoretically you just built all the TomEE dists so the jars that
     * ended up in that dist where already downloaded and we just need to
     * find them.
     */
    public static class Repository {
        private final Map<String, File> artifacts = new HashMap<>();
        private final File path;

        public Repository(final File path) {
            this.path = path;

            final List<File> jars = Files.collect(this.path, ".*\\.jar");
            for (final File jar : jars) {
                this.artifacts.put(jar.getName(), jar);
            }
        }

        /**
         * In several situations the jars inside the tomee/lib/*.jar or tomee/bin/*.jar
         * are a little tricky to map back to something from Maven Central.
         *
         * The logic in this method handles all the edge cases.
         *
         * One of the most confusing is that nearly all `catalina-foo.jar` files do
         * not contain any version, nor do they use the same artifact name that would
         * exist in Maven Central.  For Tomcat, all the `catalina-foo.jar` files tend
         * to map to `tomcat-foo-1.2.3.jar` files in Maven Central.
         *
         * There is another known limitation that the Eclipse Compiler jar (ecj-4.12.jar)
         * found in the Tomcat distribution is not available in Maven Central.  The Tomcat
         * build will download it directly from the Eclipse website.  Very strangely, the
         * Eclipse Compiler team does publish jars to Maven Central, but only for version 3.x
         */
        public Artifact from(final File jar) {
            if (jar.getName().equals("commons-daemon.jar")) {
                return new Artifact("commons-daemon", "commons-daemon", "1.1.0");
            }

            if (jar.getName().equals("tomcat-juli.jar")) {
                return new Artifact("org.apache.tomee", "tomee-juli", "${project.version}");
            }

            if (jar.getName().equals("catalina-ha.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-catalina-ha", "${tomcat.version}");
            }

            if (jar.getName().equals("catalina-storeconfig.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-storeconfig", "${tomcat.version}");
            }

            if (jar.getName().equals("catalina-tribes.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-tribes", "${tomcat.version}");
            }

            if (jar.getName().equals("catalina-ssi.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-ssi", "${tomcat.version}");
            }

            if (jar.getName().equals("catalina.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-catalina", "${tomcat.version}");
            }

            if (jar.getName().equals("el-api.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-el-api", "${tomcat.version}");
            }

            if (jar.getName().equals("jasper-el.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-jasper-el", "${tomcat.version}");
            }

            if (jar.getName().equals("jasper.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-jasper", "${tomcat.version}");
            }

            if (jar.getName().equals("jaspic-api.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-jaspic-api", "${tomcat.version}");
            }

            if (jar.getName().equals("servlet-api.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-servlet-api", "${tomcat.version}");
            }
            if (jar.getName().equals("websocket-api.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-websocket-api", "${tomcat.version}");
            }
            if (jar.getName().equals("tomcat-coyote.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-coyote", "${tomcat.version}");
            }
            if (jar.getName().equals("tomcat-dbcp.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-dbcp", "${tomcat.version}");
            }
            if (jar.getName().equals("tomcat-api.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-api", "${tomcat.version}");
            }
            if (jar.getName().equals("tomcat-websocket.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-websocket", "${tomcat.version}");
            }
            if (jar.getName().equals("tomcat-util.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-util", "${tomcat.version}");
            }
            if (jar.getName().equals("tomcat-util-scan.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-util-scan", "${tomcat.version}");
            }
            if (jar.getName().equals("tomcat-jni.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-jni", "${tomcat.version}");
            }
            if (jar.getName().equals("tomcat-jdbc.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-jdbc", "${tomcat.version}");
            }
            if (jar.getName().equals("jsp-api.jar")) {
                return new Artifact("org.apache.tomcat", "tomcat-jsp-api", "${tomcat.version}");
            }

            if (jar.getName().startsWith("ecj-")) {
                return new Artifact("org.eclipse.jdt", "ecj", "3.22.0");
            }

            if (jar.getName().equals("openejb-javaagent.jar")) {
                return new Artifact("org.apache.tomee", "openejb-javaagent", "${project.version}");
            }

            if (jar.getName().startsWith("openejb-")) {
                final String artifact = jar.getName().replaceAll("-8.0.*", "");
                return new Artifact("org.apache.tomee", artifact, "${project.version}");
            }

            if (jar.getName().startsWith("tomee-")) {
                final String artifact = jar.getName().replaceAll("-8.0.*", "");
                return new Artifact("org.apache.tomee", artifact, "${project.version}");
            }


            // /Users/dblevins/.m2/repository//org/apache/xbean/xbean-naming/4.14/xbean-naming-4.14.jar
            final File file = getFile(jar);
            final File versionDir = file.getParentFile();
            final File artifactDir = versionDir.getParentFile();

            final String groupId = artifactDir.getParentFile()
                    .getAbsolutePath()
                    .substring(path.getAbsolutePath().length() + 1)
                    .replace("/", ".");

            return Artifact.builder()
                    .artifactId(artifactDir.getName())
                    .version(versionDir.getName())
                    .groupId(groupId)
                    .build();
        }

        private File getFile(final File jar) {
            {
                final File file = artifacts.get(jar.getName());
                if (file != null) return file;
            }
            {
                final String name = jar.getName();
                final String relativeName = name.substring(name.length() - 4);

                for (final Map.Entry<String, File> entry : artifacts.entrySet()) {
                    if (entry.getKey().startsWith(relativeName)) return entry.getValue();
                }
            }

            throw new IllegalStateException(jar.getName());
        }
    }

    /**
     * A simple representation of a Maven Coordinate
     */
    @Getter
    @ToString
    @lombok.Builder(toBuilder = true)
    public static class Artifact implements Comparable<Artifact> {
        private final String groupId;
        private final String artifactId;
        private final String version;

        @Override
        public int compareTo(final Artifact o) {
            final String a = this.getGroupId() + ":" + this.artifactId;
            final String b = o.getGroupId() + ":" + o.artifactId;
            return a.compareTo(b);
        }

        /**
         * Long term the dep in the BOM should not have a version
         * and all such data would be in the parent pom.
         */
        public String asBomDep() {
            final String g = groupId;
            final String a = artifactId;
            final String v = version;
            return "" +
                    "    <dependency>\n" +
                    "      <groupId>" + g + "</groupId>\n" +
                    "      <artifactId>" + a + "</artifactId>\n" +
                    "      <version>" + v + "</version>\n" +
                    "      <exclusions>\n" +
                    "        <exclusion>\n" +
                    "          <artifactId>*</artifactId>\n" +
                    "          <groupId>*</groupId>\n" +
                    "        </exclusion>\n" +
                    "      </exclusions>\n" +
                    "    </dependency>\n";
        }

        /**
         * Currently unused, but long term this will be the entry we'd need
         * to add to the parent pom to ensure that the BOMs can just list
         * the dependencies needed, but not duplicate the version information.
         */
        public String asManagedDep() {
            final String g = groupId;
            final String a = artifactId;
            final String v = version;
            return "" +
                    "    <dependency>\n" +
                    "      <groupId>" + g + "</groupId>\n" +
                    "      <artifactId>" + a + "</artifactId>\n" +
                    "      <version>" + v + "</version>\n" +
                    "    </dependency>\n";
        }
    }

    /**
     * Attempt to throw a detailed error message if we are unable to find the
     * matching maven coordinates for a particular lib/*.jar
     */
    public static class IncompleteMappingException extends IllegalStateException {

        private final List<Distribution> incomplete;

        public IncompleteMappingException(final List<Distribution> incomplete) {
            super(message(incomplete));
            this.incomplete = incomplete;
        }

        public List<Distribution> getIncomplete() {
            return incomplete;
        }

        private static String message(final List<Distribution> incomplete) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final PrintStream out = new PrintStream(bytes);

            out.println("Unable to find matching maven coordinates from the following distributions.");
            for (Distribution distribution : incomplete) {
                out.printf("  %s%n", distribution.getZip().getName());
                for (File file : distribution.getMissing()) {
                    out.printf("    - %s%n", file.getName());
                }
            }
            out.flush();
            return new String(bytes.toByteArray());
        }
    }
}
