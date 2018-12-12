package org.embulk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.util.Modules;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigLoader;
import org.embulk.config.ConfigSource;
import org.embulk.config.ModelManager;
import org.embulk.exec.BulkLoader;
import org.embulk.exec.ExecModule;
import org.embulk.exec.ExecutionResult;
import org.embulk.exec.ExtensionServiceLoaderModule;
import org.embulk.exec.GuessExecutor;
import org.embulk.exec.PartialExecutionException;
import org.embulk.exec.PreviewExecutor;
import org.embulk.exec.PreviewResult;
import org.embulk.exec.ResumeState;
import org.embulk.exec.SystemConfigModule;
import org.embulk.exec.TransactionStage;
import org.embulk.guice.LifeCycleInjector;
import org.embulk.jruby.JRubyScriptingModule;
import org.embulk.plugin.BuiltinPluginSourceModule;
import org.embulk.plugin.PluginClassLoaderModule;
import org.embulk.plugin.maven.MavenPluginSourceModule;
import org.embulk.spi.BufferAllocator;
import org.embulk.spi.ExecSession;

public class EmbulkEmbed {
    public static ConfigLoader newSystemConfigLoader() {
        return new ConfigLoader(new ModelManager(null, new ObjectMapper()));
    }

    public static class Bootstrap {
        private final ConfigLoader systemConfigLoader;

        private ConfigSource systemConfig;

        private final List<java.util.function.Function<? super List<Module>, ? extends Iterable<? extends Module>>> moduleOverrides;

        public Bootstrap() {
            this.systemConfigLoader = newSystemConfigLoader();
            this.systemConfig = systemConfigLoader.newConfigSource();
            this.moduleOverrides = new ArrayList<>();
        }

        public ConfigLoader getSystemConfigLoader() {
            return systemConfigLoader;
        }

        public Bootstrap setSystemConfig(ConfigSource systemConfig) {
            this.systemConfig = systemConfig.deepCopy();
            return this;
        }

        public Bootstrap addModules(Module... additionalModules) {
            return addModules(Arrays.asList(additionalModules));
        }

        public Bootstrap addModules(Iterable<? extends Module> additionalModules) {
            final ArrayList<Module> copyMutable = new ArrayList<>();
            for (Module module : additionalModules) {
                copyMutable.add(module);
            }
            final List<Module> copy = Collections.unmodifiableList(copyMutable);
            return overrideModules(modules -> Iterables.concat(modules, copy));
        }

        @Deprecated
        public Bootstrap overrideModules(Function<? super List<Module>, ? extends Iterable<? extends Module>> function) {
            moduleOverrides.add(function::apply);
            return this;
        }

        public EmbulkEmbed initialize() {
            return build(true);
        }

        public EmbulkEmbed initializeCloseable() {
            return build(false);
        }

        private EmbulkEmbed build(boolean destroyOnShutdownHook) {
            org.embulk.guice.InnerBootstrap bootstrap = new org.embulk.guice.InnerBootstrap()
                    .requireExplicitBindings(false)
                    .addModules(standardModuleList(systemConfig));

            for (java.util.function.Function<? super List<Module>, ? extends Iterable<? extends Module>> override : moduleOverrides) {
                bootstrap = bootstrap.overrideModules(override);
            }

            LifeCycleInjector injector;
            if (destroyOnShutdownHook) {
                injector = bootstrap.initialize();
            } else {
                injector = bootstrap.initializeCloseable();
            }

            return new EmbulkEmbed(systemConfig, injector);
        }
    }

    private final Injector injector;
    private final org.slf4j.Logger logger;
    private final BulkLoader bulkLoader;
    private final GuessExecutor guessExecutor;
    private final PreviewExecutor previewExecutor;

    private EmbulkEmbed(ConfigSource systemConfig, Injector injector) {
        this.injector = injector;
        this.logger = injector.getInstance(org.slf4j.ILoggerFactory.class).getLogger(EmbulkEmbed.class.getName());
        this.bulkLoader = injector.getInstance(BulkLoader.class);
        this.guessExecutor = injector.getInstance(GuessExecutor.class);
        this.previewExecutor = injector.getInstance(PreviewExecutor.class);
    }

    public Injector getInjector() {
        return injector;
    }

    public ModelManager getModelManager() {
        return injector.getInstance(ModelManager.class);
    }

    public BufferAllocator getBufferAllocator() {
        return injector.getInstance(BufferAllocator.class);
    }

    public ConfigLoader newConfigLoader() {
        return injector.getInstance(ConfigLoader.class);
    }

    public ConfigDiff guess(ConfigSource config) {
        this.logger.info("Started Embulk v" + EmbulkVersion.VERSION);
        ExecSession exec = newExecSession(config);
        try {
            return guessExecutor.guess(exec, config);
        } finally {
            exec.cleanup();
        }
    }

    public PreviewResult preview(ConfigSource config) {
        this.logger.info("Started Embulk v" + EmbulkVersion.VERSION);
        ExecSession exec = newExecSession(config);
        try {
            return previewExecutor.preview(exec, config);
        } finally {
            exec.cleanup();
        }
    }

    public ExecutionResult run(ConfigSource config) {
        this.logger.info("Started Embulk v" + EmbulkVersion.VERSION);
        ExecSession exec = newExecSession(config);
        try {
            return bulkLoader.run(exec, config);
        } catch (PartialExecutionException partial) {
            try {
                bulkLoader.cleanup(config, partial.getResumeState());
            } catch (Throwable ex) {
                partial.addSuppressed(ex);
            }
            throw partial;
        } finally {
            try {
                exec.cleanup();
            } catch (Exception ex) {
                // TODO add this exception to ExecutionResult.getIgnoredExceptions
                // or partial.addSuppressed
                ex.printStackTrace(System.err);
            }
        }
    }

    public ResumableResult runResumable(ConfigSource config) {
        this.logger.info("Started Embulk v" + EmbulkVersion.VERSION);
        ExecSession exec = newExecSession(config);
        try {
            ExecutionResult result;
            try {
                result = bulkLoader.run(exec, config);
            } catch (PartialExecutionException partial) {
                return new ResumableResult(partial);
            }
            return new ResumableResult(result);
        } finally {
            try {
                exec.cleanup();
            } catch (Exception ex) {
                // TODO add this exception to ExecutionResult.getIgnoredExceptions
                // or partial.addSuppressed
                ex.printStackTrace(System.err);
            }
        }
    }

    private ExecSession newExecSession(ConfigSource config) {
        ConfigSource execConfig = config.deepCopy().getNestedOrGetEmpty("exec");
        return ExecSession.builder(injector).fromExecConfig(execConfig).build();
    }

    public ResumeStateAction resumeState(ConfigSource config, ConfigSource resumeStateConfig) {
        this.logger.info("Started Embulk v" + EmbulkVersion.VERSION);
        ResumeState resumeState = resumeStateConfig.loadConfig(ResumeState.class);
        return new ResumeStateAction(config, resumeState);
    }

    public static class ResumableResult {
        private final ExecutionResult successfulResult;
        private final PartialExecutionException partialExecutionException;

        public ResumableResult(PartialExecutionException partialExecutionException) {
            this.successfulResult = null;
            if (partialExecutionException == null) {
                throw new NullPointerException();
            }
            this.partialExecutionException = partialExecutionException;
        }

        public ResumableResult(ExecutionResult successfulResult) {
            if (successfulResult == null) {
                throw new NullPointerException();
            }
            this.successfulResult = successfulResult;
            this.partialExecutionException = null;
        }

        public boolean isSuccessful() {
            return successfulResult != null;
        }

        public ExecutionResult getSuccessfulResult() {
            if (successfulResult == null) {
                throw new IllegalStateException();
            }
            return successfulResult;
        }

        public Throwable getCause() {
            if (partialExecutionException == null) {
                throw new IllegalStateException();
            }
            return partialExecutionException.getCause();
        }

        public ResumeState getResumeState() {
            if (partialExecutionException == null) {
                throw new IllegalStateException();
            }
            return partialExecutionException.getResumeState();
        }

        public TransactionStage getTransactionStage() {
            return partialExecutionException.getTransactionStage();
        }
    }

    public class ResumeStateAction {
        private final ConfigSource config;
        private final ResumeState resumeState;

        public ResumeStateAction(ConfigSource config, ResumeState resumeState) {
            this.config = config;
            this.resumeState = resumeState;
        }

        public ResumableResult resume() {
            ExecutionResult result;
            try {
                result = bulkLoader.resume(config, resumeState);
            } catch (PartialExecutionException partial) {
                return new ResumableResult(partial);
            }
            return new ResumableResult(result);
        }

        public void cleanup() {
            bulkLoader.cleanup(config, resumeState);
        }
    }

    public void destroy() {
        if (injector instanceof LifeCycleInjector) {
            LifeCycleInjector lifeCycleInjector = (LifeCycleInjector) injector;
            try {
                lifeCycleInjector.destroy();
            } catch (Exception ex) {
                if (ex instanceof RuntimeException) {
                    throw (RuntimeException) ex;
                }
                throw new RuntimeException(ex);
            }
        }
    }

    static List<Module> standardModuleList(ConfigSource systemConfig) {
        ArrayList<Module> moduleListBuilt = new ArrayList<>();
        moduleListBuilt.add(new SystemConfigModule(systemConfig));
        moduleListBuilt.add(new ExecModule());
        moduleListBuilt.add(new ExtensionServiceLoaderModule(systemConfig));
        moduleListBuilt.add(new PluginClassLoaderModule(systemConfig));
        moduleListBuilt.add(new BuiltinPluginSourceModule());
        moduleListBuilt.add(new MavenPluginSourceModule(systemConfig));
        moduleListBuilt.add(new JRubyScriptingModule(systemConfig));
        return Collections.unmodifiableList(moduleListBuilt);
    }
}
