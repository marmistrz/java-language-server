package org.javacs;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.util.Options;

import javax.lang.model.element.Element;
import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Maintains a reference to a Java compiler, 
 * and several of its internal data structures,
 * which we need to fiddle with to get incremental compilation 
 * and extract the diagnostic information we want.
 */
public class JavacHolder {

    public static JavacHolder create(Set<Path> sourcePath, Set<Path> classPath, Path outputDirectory) {
        return new JavacHolder(sourcePath, classPath, outputDirectory);
    }
         
    /**
     * Compile a single file, without updating the index.
     *
     * As an optimization, this function may ignore code not accessible to the cursor.
     */
    public FocusedResult compileFocused(URI file, Optional<String> textContent, int line, int column, boolean pruneStatements) {
        JavaFileObject fileObject = findFile(file, textContent);

        if (pruneStatements)
            fileObject = TreePruner.putSemicolonAfterCursor(fileObject, line, column);

        JavacTask task = createTask(Collections.singleton(fileObject), true);

        // Record timing
        EnumMap<TaskEvent.Kind, Map<URI, Profile>> profile = new EnumMap<>(TaskEvent.Kind.class);

        task.addTaskListener(new TaskListener() {
            @Override
            public void started(TaskEvent e) {
                if (e.getSourceFile() == null)
                    return;

                profile.computeIfAbsent(e.getKind(), newKind -> new HashMap<>())
                    .put(e.getSourceFile().toUri(), new Profile());
            }

            @Override
            public void finished(TaskEvent e) {
                if (e.getSourceFile() == null)
                    return;
                    
                profile.get(e.getKind())
                    .get(e.getSourceFile().toUri()).finished = Optional.of(Instant.now());
            }
        });

        try {
            Iterable<? extends CompilationUnitTree> parse = task.parse();
            CompilationUnitTree compilationUnit = parse.iterator().next();
            TreePruner pruner = new TreePruner(task);

            pruner.removeNonCursorMethodBodies(compilationUnit, line, column);

            if (pruneStatements) 
                pruner.removeStatementsAfterCursor(compilationUnit, line, column);

            try {
                Iterable<? extends Element> analyze = task.analyze();
            } catch (AssertionError e) {
                if (!catchJavacError(e))
                    throw e;
            }

            // Log timing
            profile.forEach((kind, timed) -> {
                long elapsed = timed.values().stream()
                        .mapToLong(p -> p.elapsed().toMillis())
                        .sum();

                LOG.info(String.format(
                        "%s\t%d ms\t%d files",
                        kind.name(),
                        elapsed,
                        timed.size()
                ));
            });

            Optional<TreePath> cursor = FindCursor.find(task, compilationUnit, line, column);

            return new FocusedResult(compilationUnit, cursor, task, classPathIndex);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Clear files and all their dependents, recompile, compileBatch the index, and report any errors.
     *
     * If these files reference un-compiled dependencies, those dependencies will also be parsed and compiled.
     */
    public BatchResult compileBatch(Map<URI, Optional<String>> files) {
        return compileBatch(files, (task, tree) -> {});
    }

    public BatchResult compileBatch(Map<URI, Optional<String>> files, BiConsumer<JavacTask, CompilationUnitTree> listener) {
        return doCompile(files, listener);
    }

    private static final Logger LOG = Logger.getLogger("main");
    /**
     * Where this javac looks for library .class files
     */
    public final Set<Path> classPath;
    /**
     * Where this javac looks for .java source files
     */
    public final Set<Path> sourcePath;
    /**
     * Where this javac places generated .class files
     */
    public final Path outputDirectory;

    /**
     * Javac tool creates a new Context every time we do createTask(...), so maintaining a reference to it doesn't really do anything
     */
    private final JavacTool javac = JavacTool.create();

    /*
    * JavacFileManager internally caches the location of classpath,
    * so we need separate file managers for batch and incremental mode.
    */

    /**
     * File manager that caches classpath = classpath
     */
    private final JavacFileManager batchFileManager = javac.getStandardFileManager(this::onError, null, Charset.defaultCharset());

    /**
     * File manager that caches classpath = (classpath, output-directory)
     */
    private final JavaFileManager incrementalFileManager = new IncrementalFileManager(javac.getStandardFileManager(this::onError, null, Charset.defaultCharset()));

    /**
     * javac isn't friendly to swapping out the error-reporting DiagnosticListener,
     * so we install this intermediate DiagnosticListener, which forwards to errorsDelegate
     */
    private void onError(Diagnostic<? extends JavaFileObject> diagnostic) {
        onErrorDelegate.report(diagnostic);
    }

    /**
     * Error reporting initially goes nowhere.
     * We will replace this with a function that collects errors so we can report all the errors associated with a file apply once.
     */
    private DiagnosticListener<JavaFileObject> onErrorDelegate = diagnostic -> {};

    public final ClassPathIndex classPathIndex;

    private JavacHolder(Set<Path> sourcePath, Set<Path> classPath, Path outputDirectory) {
        this.sourcePath = sourcePath;
        this.classPath = Collections.unmodifiableSet(classPath);
        this.outputDirectory = outputDirectory;
        this.classPathIndex = new ClassPathIndex(classPath);

        ensureOutputDirectory(outputDirectory);
    }

    private List<String> options(boolean incremental) {
        Iterable<Path> incrementalClassPath = incremental ? Iterables.concat(classPath, Collections.singleton(outputDirectory)) : classPath;

        return ImmutableList.of(
                "-classpath", Joiner.on(File.pathSeparator).join(incrementalClassPath),
                "-sourcepath", Joiner.on(File.pathSeparator).join(sourcePath),
                "-d", outputDirectory.toString(),
                // You would think we could do -Xlint:all,
                // but some lints trigger fatal errors in the presence of parse errors
                "-Xlint:cast",
                "-Xlint:deprecation",
                "-Xlint:empty",
                "-Xlint:fallthrough",
                "-Xlint:finally",
                "-Xlint:path",
                "-Xlint:unchecked",
                "-Xlint:varargs",
                "-Xlint:static"
        );
    }

    private static class Profile {
        Instant started = Instant.now();
        Optional<Instant> finished = Optional.empty();

        Duration elapsed() {
            return Duration.between(started, finished.orElse(started));
        }
    }

    private JavacTask createTask(Collection<JavaFileObject> files, boolean incremental) {
        JavaFileManager fileManager = incremental ? incrementalFileManager : batchFileManager;
        JavacTask result = javac.getTask(null, fileManager, this::onError, options(incremental), null, files);
        JavacTaskImpl impl = (JavacTaskImpl) result;

        // Better stack traces inside javac
        Options options = Options.instance(impl.getContext());

        options.put("dev", "");

        // Skip annotation processing
        JavaCompiler compiler = JavaCompiler.instance(impl.getContext());

        compiler.skipAnnotationProcessing = true;

        return result;
    }

    /** 
     * Ensure output directory exists 
     */
    private void ensureOutputDirectory(Path dir) {
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw ShowMessageException.error("Error created output directory " + dir, null);
            }
        }
        else if (!Files.isDirectory(dir))
            throw ShowMessageException.error("Output directory " + dir + " is not a directory", null);
    }

    // TODO this should return Optional.empty() file URI is not file: and text is empty
    private JavaFileObject findFile(URI file, Optional<String> text) {
        return text
                .map(content -> (JavaFileObject) new StringFileObject(content, file))
                .orElseGet(() -> batchFileManager.getRegularFile(Paths.get(file).toFile()));
    }

    private DiagnosticCollector<JavaFileObject> startCollectingErrors() {
        DiagnosticCollector<JavaFileObject> errors = new DiagnosticCollector<>();

        onErrorDelegate = error -> {
            if (error.getStartPosition() != Diagnostic.NOPOS)
                errors.report(error);
            else
                LOG.warning("Skipped " + error.getMessage(null));
        };
        return errors;
    }

    private void stopCollectingErrors() {
        onErrorDelegate = error -> {};
    }

    public ParseResult parse(URI file, Optional<String> textContent, DiagnosticListener<JavaFileObject> onError) {
        JavaFileObject object = findFile(file, textContent);
        JavacTask task = createTask(Collections.singleton(object), false);
        onErrorDelegate = onError;

        try {
            List<CompilationUnitTree> trees = Lists.newArrayList(task.parse());

            if (trees.isEmpty())
                throw new RuntimeException("Compiling " + file + " produced 0 results");
            else if (trees.size() == 1)
                return new ParseResult(task, trees.get(0));
            else
                throw new RuntimeException("Compiling " + file + " produced " + trees.size() + " results");
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            onErrorDelegate = error -> {};
        }
    }

    private BatchResult doCompile(Map<URI, Optional<String>> files, BiConsumer<JavacTask, CompilationUnitTree> forEach) {
        // TODO remove all URIs from fileManager
        
        List<JavaFileObject> objects = files
                .entrySet()
                .stream()
                .map(e -> findFile(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        JavacTask task = createTask(objects, false);

        try {
            DiagnosticCollector<JavaFileObject> errors = startCollectingErrors();
            Iterable<? extends CompilationUnitTree> parse = task.parse();

            // TODO minimize memory use during this process
            // Instead of doing parse-all / compileFileObjects-all,
            // queue all files, then do parse / compileFileObjects on each
            // If invoked correctly, javac should avoid reparsing the same file twice
            // Then, use the same mechanism as the desugar / generate phases to remove method bodies,
            // to reclaim memory as we go

            try {
                Iterable<? extends Element> analyze = task.analyze();
            } catch (AssertionError e) {
                if (!catchJavacError(e))
                    throw e;
            }

            parse.forEach(tree -> forEach.accept(task, tree));

            // So incremental compile can use these .class files
            Iterable<? extends JavaFileObject> output = task.generate();

            return new BatchResult(task, errors);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            stopCollectingErrors();
        }
    }

    private boolean catchJavacError(AssertionError e) {
        if (e.getStackTrace().length > 0 && e.getStackTrace()[0].getClassName().startsWith("com.sun.tools.javac")) {
            LOG.log(Level.WARNING, "Failed analyze phase", e);

            return true;
        }
        else return false;
    }
}