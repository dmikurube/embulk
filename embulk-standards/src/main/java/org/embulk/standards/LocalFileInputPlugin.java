package org.embulk.standards;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigInject;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.spi.BufferAllocator;
import org.embulk.spi.Exec;
import org.embulk.spi.FileInputPlugin;
import org.embulk.spi.TransactionalFileInput;
import org.embulk.spi.util.InputStreamTransactionalFileInput;
import org.slf4j.Logger;

public class LocalFileInputPlugin implements FileInputPlugin {
    public interface PluginTask extends Task {
        @Config("path_prefix")
        String getPathPrefix();

        @Config("last_path")
        @ConfigDefault("null")
        Optional<String> getLastPath();

        @Config("follow_symlinks")
        @ConfigDefault("false")
        boolean getFollowSymlinks();

        List<String> getFiles();

        void setFiles(List<String> files);

        @ConfigInject
        BufferAllocator getBufferAllocator();
    }

    private final Logger log = Exec.getLogger(getClass());

    private static final Path CURRENT_DIR = Paths.get("").normalize();

    @Override
    public ConfigDiff transaction(final ConfigSource config, final FileInputPlugin.Control control) {
        final PluginTask task = config.loadConfig(PluginTask.class);

        // list files recursively
        final List<String> files = listFiles(task);
        log.info("Loading files {}", files);
        task.setFiles(files);

        // number of processors is same with number of files
        final int taskCount = task.getFiles().size();
        return resume(task.dump(), taskCount, control);
    }

    @Override
    public ConfigDiff resume(final TaskSource taskSource, final int taskCount, final FileInputPlugin.Control control) {
        final PluginTask task = taskSource.loadTask(PluginTask.class);

        control.run(taskSource, taskCount);

        // build next config
        final ConfigDiff configDiff = Exec.newConfigDiff();

        // last_path
        if (task.getFiles().isEmpty()) {
            // keep the last value
            if (task.getLastPath().isPresent()) {
                configDiff.set("last_path", task.getLastPath().get());
            }
        } else {
            List<String> files = new ArrayList<String>(task.getFiles());
            Collections.sort(files);
            configDiff.set("last_path", files.get(files.size() - 1));
        }

        return configDiff;
    }

    @Override
    public void cleanup(final TaskSource taskSource, final int taskCount, final List<TaskReport> successTaskReports) {}

    public List<String> listFiles(final PluginTask task) {
        final Path pathPrefixResolved = CURRENT_DIR.resolve(Paths.get(task.getPathPrefix())).normalize();
        final Path dirToStartWalking;
        final PathMatcher baseFileNameMatcher;
        final String fileNamePrefix;
        if (Files.isDirectory(pathPrefixResolved)) {
            dirToStartWalking = pathPrefixResolved;
            fileNamePrefix = "";
            baseFileNameMatcher = buildPathMatcherForBaseFileName("");
        } else {
            dirToStartWalking = Optional.ofNullable(pathPrefixResolved.getParent()).orElse(CURRENT_DIR);
            fileNamePrefix = pathPrefixResolved.getFileName().toString();
            baseFileNameMatcher = buildPathMatcherForBaseFileName(fileNamePrefix);
        }
        final PathMatcher dirToStartWalkingMatcher = buildPathMatcherForDirectory(dirToStartWalking);

        final ArrayList<String> fileListBuilt = new ArrayList<>();
        final String lastPath = task.getLastPath().orElse(null);
        try {
            log.info("Listing local files at directory '{}' filtering filename by prefix '{}'",
                     dirToStartWalking.equals(CURRENT_DIR) ? "." : dirToStartWalking.toString(),
                     fileNamePrefix);

            final Set<FileVisitOption> visitOptions;
            if (task.getFollowSymlinks()) {
                visitOptions = EnumSet.of(FileVisitOption.FOLLOW_LINKS);
            } else {
                visitOptions = EnumSet.noneOf(FileVisitOption.class);
                log.info("\"follow_symlinks\" is set false. Note that symbolic links to directories are skipped.");
            }

            Files.walkFileTree(dirToStartWalking, visitOptions, Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                        // NOTE: |dir| contains |dirToStartWalking|.
                        if (dir.equals(dirToStartWalking)) {
                            return FileVisitResult.CONTINUE;
                        } else if (lastPath != null && dir.toString().compareTo(lastPath) <= 0) {
                            return FileVisitResult.SKIP_SUBTREE;
                        } else if (!dirToStartWalkingMatcher.matches(dir)) {
                            // This condition looks to be always false, but it can be true for example in Mac OS X.
                            // It is due to the difference of |SimpleFileVisitor| and |PathMatcher|.
                            //
                            // |SimpleFileVisitor| visits files under a directory which matches |dirToStartWalking|
                            // in a case-insensitive manner in popular file systems on Mac OS X. On the other hand,
                            // |PathMatcher| always matches in a case-sensitive manner in Mac OS X.
                            //
                            // To match consistently through the entire path specified in user's configuration,
                            // paths that don't match with |PathMatcher| are explicitly rejected here.
                            return FileVisitResult.SKIP_SUBTREE;
                        } else {
                            final Path parent = Optional.ofNullable(dir.getParent()).orElse(CURRENT_DIR);
                            if (parent.equals(dirToStartWalking)) {
                                if (baseFileNameMatcher.matches(dir.getFileName())) {
                                    return FileVisitResult.CONTINUE;
                                } else {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                            } else {
                                return FileVisitResult.CONTINUE;
                            }
                        }
                    }

                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                        // NOTE: |file| contains |dirToStartWalking|.
                        try {
                            // Avoid directories from listing.
                            // Directories are normally unvisited with |FileVisitor#visitFile|, but symbolic links to
                            // directories are visited like files unless |FOLLOW_LINKS| is set in |Files#walkFileTree|.
                            // Symbolic links to directories are explicitly skipped here by checking with |Path#toReadlPath|.
                            if (Files.isDirectory(file.toRealPath())) {
                                return FileVisitResult.CONTINUE;
                            }
                        } catch (final IOException ex) {
                            throw new RuntimeException("Can't resolve symbolic link", ex);
                        }
                        if (lastPath != null && file.toString().compareTo(lastPath) <= 0) {
                            return FileVisitResult.CONTINUE;
                        } else if (!dirToStartWalkingMatcher.matches(file)) {
                            // This condition looks to be always false, but it can be true for example in Mac OS X.
                            // It is due to the difference of |SimpleFileVisitor| and |PathMatcher|.
                            //
                            // |SimpleFileVisitor| visits files under a directory which matches |dirToStartWalking|
                            // in a case-insensitive manner in popular file systems on Mac OS X. On the other hand,
                            // |PathMatcher| always matches in a case-sensitive manner in Mac OS X.
                            //
                            // To match consistently through the entire path specified in user's configuration,
                            // paths that don't match with |PathMatcher| are explicitly rejected here.
                            return FileVisitResult.CONTINUE;
                        } else {
                            final Path parent = Optional.ofNullable(file.getParent()).orElse(CURRENT_DIR);
                            if (parent.equals(dirToStartWalking)) {
                                if (baseFileNameMatcher.matches(file.getFileName())) {
                                    fileListBuilt.add(file.toString());
                                    return FileVisitResult.CONTINUE;
                                }
                            } else {
                                fileListBuilt.add(file.toString());
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    }
                });
        } catch (final IOException ex) {
            throw new RuntimeException(
                    String.format("Failed get a list of local files at '%s'", dirToStartWalking), ex);
        }
        return Collections.unmodifiableList(fileListBuilt);
    }

    @Override
    public TransactionalFileInput open(final TaskSource taskSource, final int taskIndex) {
        final PluginTask task = taskSource.loadTask(PluginTask.class);

        final File file = new File(task.getFiles().get(taskIndex));

        return new InputStreamTransactionalFileInput(
                task.getBufferAllocator(),
                new InputStreamTransactionalFileInput.Opener() {
                    public InputStream open() throws IOException {
                        return new FileInputStream(file);
                    }
                }) {
            @Override
            public void abort() {}

            @Override
            public TaskReport commit() {
                return Exec.newTaskReport();
            }
        };
    }

    private static PathMatcher buildPathMatcherForDirectory(final Path dir) {
        final StringBuilder builder = buildGlobPatternStringBuilder(dir.toString());

        if (builder.charAt(builder.length() - 1) != File.separatorChar) {
            if (File.separatorChar == '\\') {
                builder.append('\\');
            }
            builder.append(File.separator);
        }
        return FileSystems.getDefault().getPathMatcher("glob:" + builder.toString() + "**");
    }

    private static PathMatcher buildPathMatcherForBaseFileName(final String baseFileName) {
        final StringBuilder builder = buildGlobPatternStringBuilder(baseFileName);
        return FileSystems.getDefault().getPathMatcher("glob:" + builder.toString() + "*");
    }

    private static StringBuilder buildGlobPatternStringBuilder(final String pathString) {
        final StringBuilder builder = new StringBuilder();
        // Escape the special characters for the FileSystem#getPathMatcher().
        // See: https://docs.oracle.com/javase/8/docs/api/java/nio/file/FileSystem.html#getPathMatcher-java.lang.String-
        pathString.chars().forEach(c -> {
                switch ((char) c) {
                    case '*':
                    case '?':
                    case '{':
                    case '}':
                    case '[':
                    case ']':
                    case '\\':
                        builder.append('\\');
                        break;
                    default:
                        break;
                }
                builder.append((char) c);
            });
        return builder;
    }

    private static Path getCaseSensitivePathOfDirectory(
            final Path dirNormalized,
            final Set<FileVisitOption> visitOptions)
            throws IOException {
        Path built = Paths.get("");
        for (final Path pathElement : dirNormalized) {
            if (pathElement.equals(PARENT)) {
                built = built.resolve(PARENT);
                continue;
            }

            final String pathElementString = pathElement.toString();

            final ArrayList<Path> matchedCaseInsensitive = new ArrayList<>();
            Files.walkFileTree(built, visitOptions, 1, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                        if (pathElementString.equalsIgnoreCase(dir.getFileName().toString())) {
                            /*
                            if (dir.toFile().getCanonicalPath().equals(
                                    built.resolve(pathElement).toFile().getCanonicalPath())) {
                                // If |dir| points the same file with |built.resolve(pathElement)|.
                            */
                                matchedCaseInsensitive.add(dir);
                            /*
                            }
                            */
                        }
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                });
            if (matchedCaseInsensitive.size() == 1) {
                built = matchedCaseInsensitive.get(0);
            } else if (matchedCaseInsensitive.size() > 1) {
                // If multiple paths are found, it means that the file system is case sensitive.
                built = built.resolve(pathElement);
            } else {
                throw new FileNotFoundException("Directory not found: " + built.resolve(pathElement).toString());
            }
        }
        return built;
    }

    private static final Path PARENT = Paths.get("..");
}
