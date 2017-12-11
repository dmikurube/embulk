package org.embulk.jruby;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.ILoggerFactory;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Module;
import com.google.inject.Binder;
import com.google.inject.Scopes;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.ProvisionException;
import com.google.inject.spi.Dependency;
import com.google.inject.spi.ProviderWithDependencies;
import org.jruby.RubyInstanceConfig;
import org.jruby.RubyNil;
import org.jruby.RubyObject;
import org.jruby.RubyString;
import org.jruby.embed.LocalContextScope;
import org.jruby.embed.LocalVariableBehavior;
import org.jruby.embed.ScriptingContainer;
import org.jruby.util.cli.Options;
import org.embulk.plugin.PluginSource;
import org.embulk.config.ConfigSource;
import org.embulk.config.ModelManager;
import org.embulk.exec.ForSystemConfig;
import org.embulk.spi.BufferAllocator;

public class JRubyScriptingModule
        implements Module
{
    public JRubyScriptingModule(ConfigSource systemConfig)
    {
    }

    @Override
    public void configure(Binder binder)
    {
        binder.bind(ScriptingContainer.class).toProvider(ScriptingContainerProvider.class).in(Scopes.SINGLETON);

        Multibinder<PluginSource> multibinder = Multibinder.newSetBinder(binder, PluginSource.class);
        multibinder.addBinding().to(JRubyPluginSource.class);
    }

    private static class ScriptingContainerProvider
            implements ProviderWithDependencies<ScriptingContainer>
    {
        private final Injector injector;
        private final boolean useGlobalRubyRuntime;
        private final String gemHome;
        private final boolean useDefaultEmbulkGemHome;
        private final List<String> jrubyClasspath;
        private final List<String> jrubyLoadPath;
        private final List<String> jrubyOptions;
        private final String jrubyBundlerPluginSourceDirectory;

        @Inject
        public ScriptingContainerProvider(Injector injector, @ForSystemConfig ConfigSource systemConfig)
        {
            this.injector = injector;

            // use_global_ruby_runtime is valid only when it's guaranteed that just one Injector is
            // instantiated in this JVM.
            this.useGlobalRubyRuntime = systemConfig.get(boolean.class, "use_global_ruby_runtime", false);

            this.gemHome = systemConfig.get(String.class, "gem_home", null);
            this.useDefaultEmbulkGemHome =
                systemConfig.get(String.class, "jruby_use_default_embulk_gem_home", "false").equals("true");

            // TODO get jruby-home from systemConfig to call jruby.container.setHomeDirectory

            final List jrubyLoadPathNonGeneric = systemConfig.get(List.class, "jruby_load_path", null);
            final ArrayList<String> jrubyLoadPathBuilt = new ArrayList<String>();
            if (jrubyLoadPathNonGeneric != null) {
                for (final Object oneJRubyLoadPath : jrubyLoadPathNonGeneric) {
                    if (oneJRubyLoadPath instanceof String) {
                        jrubyLoadPathBuilt.add((String) oneJRubyLoadPath);
                    }
                    else {
                        // It should happen only in very irregular cases. Okay to create |Logger| every time.
                        Logger logger = injector.getInstance(ILoggerFactory.class).getLogger("init");
                        logger.warn("System config \"jruby_load_path\" contains non-String.");
                        jrubyLoadPathBuilt.add(oneJRubyLoadPath.toString());
                    }
                }
            }
            this.jrubyLoadPath = Collections.unmodifiableList(jrubyLoadPathBuilt);

            final List jrubyClasspathNonGeneric = systemConfig.get(List.class, "jruby_classpath", new ArrayList());
            final ArrayList<String> jrubyClasspathBuilt = new ArrayList<String>();
            if (jrubyClasspathNonGeneric != null) {
                for (final Object oneJRubyClasspath : jrubyClasspathNonGeneric) {
                    if (oneJRubyClasspath instanceof String) {
                        jrubyClasspathBuilt.add((String) oneJRubyClasspath);
                    }
                    else {
                        // It should happen only in very irregular cases. Okay to create |Logger| every time.
                        Logger logger = injector.getInstance(ILoggerFactory.class).getLogger("init");
                        logger.warn("System config \"jruby_classpath\" contains non-String.");
                        jrubyClasspathBuilt.add(oneJRubyClasspath.toString());
                    }
                }
            }
            this.jrubyClasspath = Collections.unmodifiableList(jrubyClasspathBuilt);

            final List jrubyOptionsNonGeneric = systemConfig.get(List.class, "jruby_command_line_options", null);
            final ArrayList<String> jrubyOptionsBuilt = new ArrayList<String>();
            if (jrubyOptionsNonGeneric != null) {
                for (final Object oneJRubyOption : jrubyOptionsNonGeneric) {
                    if (oneJRubyOption instanceof String) {
                        jrubyOptionsBuilt.add((String) oneJRubyOption);
                    }
                    else {
                        // It should happen only in very irregular cases. Okay to create |Logger| every time.
                        Logger logger = injector.getInstance(ILoggerFactory.class).getLogger("init");
                        logger.warn("System config \"jruby_command_line_options\" contains non-String.");
                        jrubyOptionsBuilt.add(oneJRubyOption.toString());
                    }
                }
            }
            this.jrubyOptions = Collections.unmodifiableList(jrubyOptionsBuilt);

            this.jrubyBundlerPluginSourceDirectory =
                systemConfig.get(String.class, "jruby_global_bundler_plugin_source_directory", null);
        }

        public ScriptingContainer get() throws ProvisionException
        {
            LocalContextScope scope = (useGlobalRubyRuntime ? LocalContextScope.SINGLETON : LocalContextScope.SINGLETHREAD);
            ScriptingContainer jruby = new ScriptingContainer(scope, LocalVariableBehavior.PERSISTENT);
            this.setGemVariables(jruby);

            final RubyInstanceConfig jrubyInstanceConfig = jruby.getProvider().getRubyInstanceConfig();
            for (final String jrubyOption : this.jrubyOptions) {
                try {
                    processJRubyOption(jrubyOption, jrubyInstanceConfig);
                }
                catch (UnrecognizedJRubyOptionException ex) {
                    final Logger logger = this.injector.getInstance(ILoggerFactory.class).getLogger("init");
                    logger.error("The \"-R\" option(s) are not recognized in Embulk: -R" + jrubyOption +
                                 ". Please add your requests at: https://github.com/embulk/embulk/issues/707", ex);
                    throw new RuntimeException(ex);

                }
                catch (NotWorkingJRubyOptionException ex) {
                    final Logger logger = this.injector.getInstance(ILoggerFactory.class).getLogger("init");
                    logger.warn("The \"-R\" option(s) do not work in Embulk: -R" + jrubyOption + ".", ex);
                }
            }

            setBundlerPluginSourceDirectory(jruby, this.jrubyBundlerPluginSourceDirectory);

            for (final String oneJRubyLoadPath : this.jrubyLoadPath) {
                // ruby script directory (use unshift to make it highest priority)
                jruby.put("__internal_load_path__", oneJRubyLoadPath);
                // TODO: Check if $LOAD_PATH already contains it.
                jruby.runScriptlet("$LOAD_PATH.unshift File.expand_path(__internal_load_path__)");
                jruby.remove("__internal_load_path__");
            }

            for (final String oneJRubyClasspath : this.jrubyClasspath) {
                jruby.put("__internal_classpath__", oneJRubyClasspath);
                // $CLASSPATH object doesn't have concat method
                // TODO: Check if $CLASSPATH already contains it.
                jruby.runScriptlet("$CLASSPATH << __internal_classpath__");
                jruby.remove("__internal_classpath__");
            }

            // Search embulk/java/bootstrap.rb from a $LOAD_PATH.
            // $LOAD_PATH is set by lib/embulk/command/embulk_run.rb if Embulk starts
            // using embulk-cli but it's not set if Embulk is embedded in an application.
            // Here adds this jar's internal resources to $LOAD_PATH for those applciations.

//            List<String> loadPaths = new ArrayList<String>(jruby.getLoadPaths());
//            String coreJarPath = JRubyScriptingModule.class.getProtectionDomain().getCodeSource().getLocation().getPath();
//            if (!loadPaths.contains(coreJarPath)) {
//                loadPaths.add(coreJarPath);
//            }
//            jruby.setLoadPaths(loadPaths);

            // load embulk.rb
            jruby.runScriptlet("require 'embulk'");

            // jruby searches embulk/java/bootstrap.rb from the beginning of $LOAD_PATH.
            jruby.runScriptlet("require 'embulk/java/bootstrap'");

            // TODO validate Embulk::Java::Injected::Injector doesn't exist? If it already exists,
            //      Injector is created more than once in this JVM although use_global_ruby_runtime
            //      is set to true.

            // set some constants
            jruby.callMethod(
                    jruby.runScriptlet("Embulk::Java::Injected"),
                    "const_set", "Injector", injector);
            jruby.callMethod(
                    jruby.runScriptlet("Embulk::Java::Injected"),
                    "const_set", "ModelManager", injector.getInstance(ModelManager.class));
            jruby.callMethod(
                    jruby.runScriptlet("Embulk::Java::Injected"),
                    "const_set", "BufferAllocator", injector.getInstance(BufferAllocator.class));

            // initialize logger
            jruby.callMethod(
                    jruby.runScriptlet("Embulk"),
                    "logger=",
                        jruby.callMethod(
                            jruby.runScriptlet("Embulk::Logger"),
                            "new", injector.getInstance(ILoggerFactory.class).getLogger("ruby")));

            return jruby;
        }

        public Set<Dependency<?>> getDependencies()
        {
            // get() depends on other modules
            return ImmutableSet.of(
                Dependency.get(Key.get(ModelManager.class)),
                Dependency.get(Key.get(BufferAllocator.class)));
        }

        private static final class UnrecognizedJRubyOptionException extends Exception {}
        private static final class NotWorkingJRubyOptionException extends Exception {}
        private static final class UnrecognizedJRubyLoadPathException extends Exception {
            public UnrecognizedJRubyLoadPathException(final String message)
            {
                super(message);
            }

            public UnrecognizedJRubyLoadPathException(final String message, final Throwable cause)
            {
                super(message, cause);
            }
        }
        private static final class LocationNotFoundException extends Exception {
            public LocationNotFoundException(final String message)
            {
                super(message);
            }

            public LocationNotFoundException(final String message, final Throwable cause)
            {
                super(message, cause);
            }
        }

        private void setGemVariables(final ScriptingContainer jruby)
        {
            final Logger logger = this.injector.getInstance(ILoggerFactory.class).getLogger("init");

            final boolean hasBundleGemfile = definedBundleGemfile(jruby);
            if (hasBundleGemfile) {
                logger.warn("BUNDLE_GEMFILE has already been set: \"" + getBundleGemfile(jruby) + "\"");
            }

            if (this.jrubyBundlerPluginSourceDirectory != null) {
                final String gemfilePath = this.buildGemfilePath(this.jrubyBundlerPluginSourceDirectory);
                if (hasBundleGemfile) {
                    logger.warn("BUNDLE_GEMFILE is being overwritten: \"" + gemfilePath + "\"");
                } else {
                    logger.info("BUNDLE_GEMFILE is being set: \"" + gemfilePath + "\"");
                }
                setBundleGemfile(jruby, gemfilePath);
                logger.info("Gem's home and path are being cleared.");
                clearGemPaths(jruby);
                logger.debug("Gem.paths.home = \"" + getGemHome(jruby) + "\"");
                logger.debug("Gem.paths.path = " + getGemPath(jruby) + "");
            } else {
                if (hasBundleGemfile) {
                    logger.warn("BUNDLE_GEMFILE is being unset.");
                    unsetBundleGemfile(jruby);
                }
                if (this.gemHome != null) {
                    // The system config "gem_home" is always prioritized.
                    //
                    // Overwrites GEM_HOME and GEM_PATH. GEM_PATH becomes same with GEM_HOME. Therefore
                    // with this code, there're no ways to set extra GEM_PATHs in addition to GEM_HOME.
                    // Here doesn't modify ENV['GEM_HOME'] so that a JVM process can create multiple
                    // JRubyScriptingModule instances. However, because Gem loads ENV['GEM_HOME'] when
                    // Gem.clear_paths is called, applications may use unexpected GEM_HOME if clear_path
                    // is used.
                    logger.info("Gem's home and path are set by system config \"gem_home\": \"" + this.gemHome + "\"");
                    setGemPaths(jruby, this.gemHome);
                    logger.debug("Gem.paths.home = \"" + getGemHome(jruby) + "\"");
                    logger.debug("Gem.paths.path = " + getGemPath(jruby) + "");
                } else if (this.useDefaultEmbulkGemHome) {
                    // NOTE: Same done in "gem", "exec", and "irb" subcommands.
                    // Remember to update |org.embulk.cli.EmbulkRun| as well when these environment variables are change
                    final String defaultGemHome = this.buildDefaultGemPath(jruby);
                    logger.info("Gem's home and path are set by default: \"" + defaultGemHome + "\"");
                    setGemPaths(jruby, defaultGemHome);
                    logger.debug("Gem.paths.home = \"" + getGemHome(jruby) + "\"");
                    logger.debug("Gem.paths.path = " + getGemPath(jruby) + "");
                } else {
                    logger.info("Gem's home and path are not managed.");
                    logger.info("Gem.paths.home = \"" + getGemHome(jruby) + "\"");
                    logger.info("Gem.paths.path = " + getGemPath(jruby) + "");
                }
            }
        }

        private void setBundlerPluginSourceDirectory(final ScriptingContainer jruby, final String directory)
        {
            if (directory != null) {
                // bundler is included in embulk-core.jar
                jruby.runScriptlet("require 'bundler'");

                // TODO: Remove the monkey patch once the issue is fixed on Bundler or JRuby side.
                // @see <a href="https://github.com/bundler/bundler/issues/4565">Bundler::SharedHelpers.clean_load_path does cleanup the default load_path on jruby - Issue #4565 - bundler/bundler</a>
                final String monkeyPatchOnSharedHelpersCleanLoadPath =
                    "begin\n" +
                    "  require 'bundler/shared_helpers'\n" +
                    "  module Bundler\n" +
                    "    module DisableCleanLoadPath\n" +
                    "      def clean_load_path\n" +
                    "        # Do nothing.\n" +
                    "      end\n" +
                    "    end\n" +
                    "    module SharedHelpers\n" +
                    "      def included(bundler)\n" +
                    "        bundler.send :include, DisableCleanLoadPath\n" +
                    "      end\n" +
                    "    end\n" +
                    "  end\n" +
                    "rescue LoadError\n" +
                    "  # Ignore LoadError.\n" +
                    "end\n";
                jruby.runScriptlet(monkeyPatchOnSharedHelpersCleanLoadPath);

                // Bundler.load.setup_environment was called in Bundler 1.10.6.
                jruby.runScriptlet("Bundler::SharedHelpers.set_bundle_environment");

                jruby.runScriptlet("require 'bundler/setup'");
                // since here, `require` may load files of different (newer) embulk versions
                // especially following 'embulk/command/embulk_main'.

                // NOTE: It is intentionally not done by building a Ruby statement string from |directory|.
                // It can cause insecure injections.
                //
                // add bundle directory path to load local plugins at ./embulk
                jruby.put("__internal_bundler_plugin_source_directory__", directory);
                jruby.runScriptlet("$LOAD_PATH << File.expand_path(__internal_bundler_plugin_source_directory__)");
                jruby.remove("__internal_bundler_plugin_source_directory__");
            }
            else {
                // NOTE: The path from |buildJRubyLoadPath()| is added in $LOAD_PATH just in case.
                // Though it is not mandatory just to run "embulk_main.rb", it may be required in later steps.
                //
                // NOTE: It is intentionally not done by building a Ruby statement string from |buildJRubyLoadPath()|.
                // It can cause insecure injections.
                //
                // NOTE: It was written in Ruby as follows:
                //   $LOAD_PATH << File.expand_path('../../', File.dirname(__FILE__))
                final String jrubyLoadPath;
                try {
                    jrubyLoadPath = buildJRubyLoadPath();
                }
                catch (UnrecognizedJRubyLoadPathException ex) {
                    final Logger logger = this.injector.getInstance(ILoggerFactory.class).getLogger("init");
                    logger.error("Failed to retrieve Embulk's location.", ex);
                    throw new RuntimeException(ex);
                }
                jruby.put("__internal_load_path__", jrubyLoadPath);
                jruby.runScriptlet("$LOAD_PATH << File.expand_path(__internal_load_path__)");
                jruby.remove("__internal_load_path__");
            }
        }

        private static void processJRubyOption(final String jrubyOption, final RubyInstanceConfig jrubyInstanceConfig)
                throws UnrecognizedJRubyOptionException, NotWorkingJRubyOptionException
        {
            if (jrubyOption.charAt(0) != '-') {
                throw new UnrecognizedJRubyOptionException();
            }

            for (int index = 1; index < jrubyOption.length(); ++index) {
                switch (jrubyOption.charAt(index)) {
                case '-':
                    if (jrubyOption.equals("--dev")) {
                        // They are not all of "--dev", but they are most possible configurations after JVM boot.
                        Options.COMPILE_INVOKEDYNAMIC.force("false");  // NOTE: Options is global.
                        jrubyInstanceConfig.setCompileMode(RubyInstanceConfig.CompileMode.OFF);
                        return;
                    }
                    else if (jrubyOption.equals("--client")) {
                        throw new NotWorkingJRubyOptionException();
                    }
                    else if (jrubyOption.equals("--server")) {
                        throw new NotWorkingJRubyOptionException();
                    }
                    throw new UnrecognizedJRubyOptionException();
                default:
                    throw new UnrecognizedJRubyOptionException();
                }
            }
        }

        private static String getGemHome(final ScriptingContainer jruby) {
            return jruby.callMethod(getGemPaths(jruby), "home", String.class);
        }

        private static String getGemPath(final ScriptingContainer jruby) {
            final List gemPath = jruby.callMethod(getGemPaths(jruby), "path", List.class);
            return gemPath.toString();
        }

        private static RubyObject getGemPaths(final ScriptingContainer jruby) {
            return jruby.callMethod(jruby.runScriptlet("Gem"), "paths", RubyObject.class);
        }

        private static void clearGemPaths(final ScriptingContainer jruby) {
            jruby.callMethod(jruby.runScriptlet("Gem"), "use_paths", (RubyNil)null, (RubyNil)null);
        }

        private static void setGemPaths(final ScriptingContainer jruby, final String gemPath) {
            final RubyString gemPathRuby = RubyString.newString(jruby.getProvider().getRuntime(), gemPath);
            jruby.callMethod(jruby.runScriptlet("Gem"), "use_paths", gemPathRuby, gemPathRuby);
        }

        private String buildDefaultGemPath(final ScriptingContainer jruby) throws ProvisionException {
            final String rubyEngine = jruby.callMethod(jruby.runScriptlet("Gem"), "ruby_engine", String.class);
            final String rubyVersion = jruby.callMethod(
                jruby.runScriptlet("RbConfig::CONFIG"), "fetch", "ruby_version", String.class);
            return this.buildEmbulkHome().resolve(rubyEngine).resolve(rubyVersion).toString();
        }

        private static boolean definedBundleGemfile(final ScriptingContainer jruby) {
            return jruby.callMethod(jruby.runScriptlet("ENV"), "has_key?", "BUNDLE_GEMFILE", Boolean.class);
        }

        private static String getBundleGemfile(final ScriptingContainer jruby) {
            return jruby.callMethod(jruby.runScriptlet("ENV"), "fetch", "BUNDLE_GEMFILE", String.class);
        }

        private static void setBundleGemfile(final ScriptingContainer jruby, final String gemfilePath) {
            jruby.callMethod(jruby.runScriptlet("ENV"), "store", "BUNDLE_GEMFILE", gemfilePath);
        }

        private static void unsetBundleGemfile(final ScriptingContainer jruby) {
            jruby.callMethod(jruby.runScriptlet("ENV"), "delete", "BUNDLE_GEMFILE");
        }

        private String buildGemfilePath(final String bundleDirectoryString)
                throws ProvisionException {
            final Path bundleDirectory;
            try {
                bundleDirectory = Paths.get(bundleDirectoryString);
            } catch (InvalidPathException ex) {
                throw new ProvisionException("Bundle directory is invalid: \"" + bundleDirectoryString + "\"", ex);
            }
            return bundleDirectory.toAbsolutePath().resolve("Gemfile").toString();
        }

        private Path buildEmbulkHome() throws ProvisionException {
            final String userHomeProperty = System.getProperty("user.home");

            final Path embulkHomeFromLocation = getEmbulkHomeFromLocation();
            if (embulkHomeFromLocation != null) {
                if (userHomeProperty != null) {
                    // Getting a full path of user home directory intentionally here and later again.
                    // It is redundant, but easier to remove this if-block later which is only logging a warning.
                    Path userHomeTemporary;
                    try {
                        userHomeTemporary = Paths.get(userHomeProperty).toAbsolutePath();
                    }
                    catch (Exception ex) {
                        userHomeTemporary = null;
                    }
                    if (userHomeTemporary != null && !userHomeTemporary.equals(embulkHomeFromLocation)) {
                        final Logger logger = this.injector.getInstance(ILoggerFactory.class).getLogger("init");
                        logger.warn("Embulk's jar executed is located out of ~/.embulk/bin. The directory is considered as Embulk's home: \"" + embulkHomeFromLocation.toString() + "\"");
                    }
                }
                return embulkHomeFromLocation;
            }

            if (userHomeProperty == null) {
                throw new ProvisionException("User home directory is not set in Java properties.");
            }

            final Path userHome;
            try {
                userHome = Paths.get(userHomeProperty);
            } catch (InvalidPathException ex) {
                throw new ProvisionException("User home directory is invalid: \"" + userHomeProperty + "\"", ex);
            }

            return userHome.toAbsolutePath().resolve(".embulk");
        }

        /**
         * Returns a path to be added in JRuby's $LOAD_PATH.
         *
         * In case Embulk runs from the Embulk JAR file (normal case):
         *     "file:/some/directory/embulk.jar!"
         *
         * In case Embulk runs out of a JAR file (irregular case):
         *     "/some/directory"
         */
        private static String buildJRubyLoadPath()
                throws UnrecognizedJRubyLoadPathException
        {
            final Path locationPath;
            try {
                locationPath = findEmbulkLocation();
            } catch (LocationNotFoundException ex) {
                throw new UnrecognizedJRubyLoadPathException("Invalid location.", ex);
            }

            if (Files.isDirectory(locationPath)) {  // Out of a JAR file
                System.err.println("[WARN] Embulk looks running out of the Embulk jar file. It is unsupported.");
                return locationPath.toString();
            }

            // TODO: Consider checking the file is really a JAR file.
            return locationPath.toUri().toString() + "!";  // Inside the Embulk JAR file
        }

        private static Path getEmbulkHomeFromLocation() {
            final Path locationPath;
            try {
                locationPath = findEmbulkLocation();
            } catch (LocationNotFoundException ex) {
                return null;
            }

            if (Files.isDirectory(locationPath)) {
                // If Embulk runs out of a JAR file, Embulk home is not available from the code source location.
                return null;
            }

            return locationPath.getParent().toAbsolutePath();
        }

        private static Path findEmbulkLocation() throws LocationNotFoundException {
            final ProtectionDomain protectionDomain;
            try {
                protectionDomain = JRubyScriptingModule.class.getProtectionDomain();
            }
            catch (SecurityException ex) {
                throw new LocationNotFoundException("Failed to achieve ProtectionDomain", ex);
            }

            final CodeSource codeSource = protectionDomain.getCodeSource();
            if (codeSource == null) {
                throw new LocationNotFoundException("Failed to achieve CodeSource");
            }

            final URL locationUrl = codeSource.getLocation();
            if (locationUrl == null) {
                throw new LocationNotFoundException("Failed to achieve location");
            }
            else if (!locationUrl.getProtocol().equals("file")) {
                throw new LocationNotFoundException("Invalid location: " + locationUrl.toString());
            }

            final Path locationPath;
            try {
                locationPath = Paths.get(locationUrl.toURI());
            }
            catch (URISyntaxException ex) {
                throw new LocationNotFoundException("Invalid location: " + locationUrl.toString(), ex);
            }

            return locationPath;
        }
    }
}
