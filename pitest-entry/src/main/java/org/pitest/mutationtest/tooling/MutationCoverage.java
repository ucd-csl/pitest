/*
 * Copyright 2010 Henry Coles
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.pitest.mutationtest.tooling;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.pitest.bytecode.analysis.ClassTree;
import org.pitest.classinfo.ClassByteArraySource;
import org.pitest.classinfo.ClassInfo;
import org.pitest.classinfo.ClassName;
import org.pitest.classinfo.HierarchicalClassId;
import org.pitest.classpath.ClassPathByteArraySource;
import org.pitest.classpath.ClassloaderByteArraySource;
import org.pitest.classpath.CodeSource;
import org.pitest.coverage.CoverageDatabase;
import org.pitest.coverage.CoverageGenerator;
import org.pitest.coverage.TestInfo;
import org.pitest.functional.FCollection;
import org.pitest.help.Help;
import org.pitest.help.PitHelpError;
import org.pitest.mutationtest.EngineArguments;
import org.pitest.mutationtest.HistoryStore;
import org.pitest.mutationtest.ListenerArguments;
import org.pitest.mutationtest.MutationConfig;
import org.pitest.mutationtest.MutationResultListener;
import org.pitest.mutationtest.build.MutationAnalysisUnit;
import org.pitest.mutationtest.build.MutationGrouper;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.build.MutationSource;
import org.pitest.mutationtest.build.MutationTestUnit;
import org.pitest.mutationtest.build.PercentAndConstantTimeoutStrategy;
import org.pitest.mutationtest.build.TestPrioritiser;
import org.pitest.mutationtest.build.WorkerFactory;
import org.pitest.mutationtest.config.ReportOptions;
import org.pitest.mutationtest.config.SettingsFactory;
import org.pitest.mutationtest.engine.Mutater;
import org.pitest.mutationtest.engine.MutationDetails;
import org.pitest.mutationtest.engine.MutationEngine;
import org.pitest.mutationtest.execute.MutationAnalysisExecutor;
import org.pitest.mutationtest.incremental.HistoryListener;
import org.pitest.mutationtest.statistics.MutationStatisticsListener;
import org.pitest.mutationtest.statistics.Score;
import org.pitest.util.Log;
import org.pitest.util.StringUtil;
import org.pitest.util.Timings;

import ie.ucd.csl.pitestHOM.pitestHOM.PitestHOMUtilities;

public class MutationCoverage {

  private static final int         MB  = 1024 * 1024;

  private static final Logger      LOG = Log.getLogger();
  private final ReportOptions      data;

  private final MutationStrategies strategies;
  private final Timings            timings;
  private final CodeSource         code;
  private final File               baseDir;
  private final SettingsFactory    settings;

  public MutationCoverage(final MutationStrategies strategies,
      final File baseDir, final CodeSource code, final ReportOptions data,
      final SettingsFactory settings, final Timings timings) {
    this.strategies = strategies;
    this.data = data;
    this.settings = settings;
    this.timings = timings;
    this.code = code;
    this.baseDir = baseDir;
  }

  public CombinedStatistics runReport() {

    Log.setVerbose(this.data.isVerbose());

    final Runtime runtime = Runtime.getRuntime();

    if (!this.data.isVerbose()) {
      LOG.info("Verbose logging is disabled. If you encounter a problem, please enable it before reporting an issue.");
    }

    LOG.fine("Running report with " + this.data);

    LOG.fine("System class path is " + System.getProperty("java.class.path"));
    LOG.fine("Maximum available memory is " + (runtime.maxMemory() / MB)
        + " mb");

    final long t0 = System.currentTimeMillis();

    verifyBuildSuitableForMutationTesting();

    checkExcludedRunners();

    final CoverageDatabase coverageData = coverage().calculateCoverage();

    LOG.fine("Used memory after coverage calculation "
        + ((runtime.totalMemory() - runtime.freeMemory()) / MB) + " mb");
    LOG.fine("Free Memory after coverage calculation "
        + (runtime.freeMemory() / MB) + " mb");

    final MutationStatisticsListener stats = new MutationStatisticsListener();

    final EngineArguments args = EngineArguments.arguments()
        .withExcludedMethods(this.data.getExcludedMethods())
        .withMutators(this.data.getMutators());
    final MutationEngine engine = this.strategies.factory().createEngine(args);

    final List<MutationResultListener> config = createConfig(t0, coverageData,
        stats, engine);

    history().initialize();


    recordClassPath(coverageData);

    LOG.fine("Used memory before analysis start "
        + ((runtime.totalMemory() - runtime.freeMemory()) / MB) + " mb");
    LOG.fine("Free Memory before analysis start " + (runtime.freeMemory() / MB)
        + " mb");

    final MutationAnalysisExecutor mae = new MutationAnalysisExecutor(
        numberOfThreads(), config);
    this.timings.registerStart(Timings.Stage.RUN_MUTATION_TESTS);
    mae.preRun();
    runHOMs(mae, coverageData, engine, args);
    mae.postRun();
    this.timings.registerEnd(Timings.Stage.RUN_MUTATION_TESTS);

    LOG.info("Completed in " + timeSpan(t0));

    printStats(stats);

    return new CombinedStatistics(stats.getStatistics(),
        coverageData.createSummary());

  }

  private void checkExcludedRunners() {
    final Collection<String> excludedRunners = this.data.getExcludedRunners();
    if (!excludedRunners.isEmpty()) {
      // Check whether JUnit4 is available or not
      try {
        Class.forName("org.junit.runner.RunWith");
      } catch (final ClassNotFoundException e) {
        // JUnit4 is not available on the classpath
        throw new PitHelpError(Help.NO_JUNIT_EXCLUDE_RUNNERS);
      }
    }
  }

private int numberOfThreads() {
    return Math.max(1, this.data.getNumberOfThreads());
  }

  private List<MutationResultListener> createConfig(final long t0,
      final CoverageDatabase coverageData,
      final MutationStatisticsListener stats, final MutationEngine engine) {
    final List<MutationResultListener> ls = new ArrayList<>();

    ls.add(stats);

    final ListenerArguments args = new ListenerArguments(
        this.strategies.output(), coverageData, new SmartSourceLocator(
            this.data.getSourceDirs()), engine, t0, this.data.isFullMutationMatrix());
    
    final MutationResultListener mutationReportListener = this.strategies
        .listenerFactory().getListener(this.data.getFreeFormProperties(), args);

    ls.add(mutationReportListener);
    ls.add(new HistoryListener(history()));

    if (!this.data.isVerbose()) {
      ls.add(new SpinnerListener(System.out));
    }
    return ls;
  }

  private void recordClassPath(final CoverageDatabase coverageData) {
    final Set<ClassName> allClassNames = getAllClassesAndTests(coverageData);
    final Collection<HierarchicalClassId> ids = FCollection.map(
        this.code.getClassInfo(allClassNames), ClassInfo.toFullClassId());
    history().recordClassPath(ids, coverageData);
  }

  private Set<ClassName> getAllClassesAndTests(
      final CoverageDatabase coverageData) {
    final Set<ClassName> names = new HashSet<>();
    for (final ClassName each : this.code.getCodeUnderTestNames()) {
      names.add(each);
      FCollection.mapTo(coverageData.getTestsForClass(each),
          TestInfo.toDefiningClassName(), names);
    }
    return names;
  }

  private void verifyBuildSuitableForMutationTesting() {
    this.strategies.buildVerifier().verify(this.code);
  }

  private void printStats(final MutationStatisticsListener stats) {
    final PrintStream ps = System.out;

    ps.println(StringUtil.separatorLine('='));
    ps.println("- Mutators");
    ps.println(StringUtil.separatorLine('='));
    for (final Score each : stats.getStatistics().getScores()) {
      each.report(ps);
      ps.println(StringUtil.separatorLine());
    }

    ps.println(StringUtil.separatorLine('='));
    ps.println("- Timings");
    ps.println(StringUtil.separatorLine('='));
    this.timings.report(ps);

    ps.println(StringUtil.separatorLine('='));
    ps.println("- Statistics");
    ps.println(StringUtil.separatorLine('='));
    stats.getStatistics().report(ps);
  }

  private String timeSpan(final long t0) {
    return "" + ((System.currentTimeMillis() - t0) / 1000) + " seconds";
  }

  private CoverageGenerator coverage() {
    return this.strategies.coverage();
  }

  private HistoryStore history() {
    return this.strategies.history();
  }

  // For reasons not yet understood classes from rt.jar are not resolved for some
  // projects during static analysis phase. For now fall back to the classloader when
  // a class not provided by project classpath
  private ClassByteArraySource fallbackToClassLoader(final ClassByteArraySource bas) {
    final ClassByteArraySource clSource = ClassloaderByteArraySource.fromContext();
    return clazz -> {
      final Optional<byte[]> maybeBytes = bas.getBytes(clazz);
      if (maybeBytes.isPresent()) {
        return maybeBytes;
      }
      LOG.log(Level.FINE, "Could not find " + clazz + " on classpath for analysis. Falling back to classloader");
      return clSource.getBytes(clazz);
    };
  }

  private void runHOMs(MutationAnalysisExecutor mae, final CoverageDatabase coverageData, final MutationEngine engine, EngineArguments args) {
    if (! this.data.getHom().isEmpty()) {
      final MutationConfig mutationConfig = new MutationConfig(engine, coverage()
              .getLaunchOptions());

      ClassByteArraySource bas = fallbackToClassLoader(new ClassPathByteArraySource(
              this.data.getClassPath()));

      TestPrioritiser testPrioritiser = this.settings.getTestPrioritiser()
              .makeTestPrioritiser(this.data.getFreeFormProperties(), this.code,
                      coverageData);

      MutationInterceptor interceptor = this.settings.getInterceptor()
              .createInterceptor(this.data, bas);

      final MutationSource source = new MutationSource(mutationConfig, testPrioritiser, bas, interceptor);

      final WorkerFactory wf = new WorkerFactory(this.baseDir, coverage()
        .getConfiguration(), mutationConfig, args,
        new PercentAndConstantTimeoutStrategy(this.data.getTimeoutFactor(),
            this.data.getTimeoutConstant()), this.data.isVerbose(), this.data.isFullMutationMatrix(),
            this.data.getClassPath().getLocalClassPath());
      
      final Mutater mutater = mutationConfig.createMutator(bas);

      final MutationGrouper grouper = this.settings.getMutationGrouper().makeFactory(
          this.data.getFreeFormProperties(), this.code,
          this.data.getNumberOfThreads(), this.data.getMutationUnitSize());

      PitestHOMUtilities pHOM = new PitestHOMUtilities(mae, wf, testPrioritiser, interceptor, mutater,
                                                       grouper, this.code.getCodeUnderTestNames());

      switch (this.data.getMutantProcessingMethod().toLowerCase()) {
        case "stream-batch":
          runMutantsStreamBatch(source, grouper, pHOM, mae, interceptor, bas);
          break;
        case "stream":
          runMutantsStream(source, wf, pHOM, mae, interceptor, bas);
          break;
        case "all":
        default:
          runMutantsAll(source, grouper, pHOM, mae, interceptor, bas);
          break;
      }


    }
  }

  private void runMutantsStream(MutationSource source, WorkerFactory wf, PitestHOMUtilities pHOM,
                                     MutationAnalysisExecutor mae, MutationInterceptor interceptor,
                                ClassByteArraySource bas) {
    boolean noMutationFound = true;
    for (ClassName c : this.code.getCodeUnderTestNames()) {

      final List<MutationDetails> mutations = new ArrayList<>(source.createMutations(c));
      if (!mutations.isEmpty()) {
        noMutationFound = false;
      }

      if (this.data.getHom().contains(1)) {
        for (MutationDetails detail : mutations) {
          List<ClassName> testNames = FCollection.map(detail.getTestsInOrder(), TestInfo.toDefiningClassName());
          List<MutationDetails> d = Collections.singletonList(detail);
          mae.run(Collections.singletonList(new MutationTestUnit(d, testNames, wf)));
        }
      }
      interceptor.begin(ClassTree.fromBytes(bas.getBytes(c.asJavaName()).get()));
      pHOM.runMutantsOfOrdersStream(mutations, this.data.getHom());
      interceptor.end();
    }

    if (noMutationFound) {
      if (this.data.shouldFailWhenNoMutations()) {
        throw new PitHelpError(Help.NO_MUTATIONS_FOUND);
      } else {
        LOG.warning(Help.NO_MUTATIONS_FOUND.toString());
      }
    }
  }

  private void runMutantsStreamBatch(MutationSource source, MutationGrouper grouper, PitestHOMUtilities pHOM,
                                     MutationAnalysisExecutor mae, MutationInterceptor interceptor, ClassByteArraySource bas) {
    boolean noMutationFound = true;
    for (ClassName c : this.code.getCodeUnderTestNames()) {

      final List<MutationDetails> mutations = new ArrayList<>(source.createMutations(c));
      if (!mutations.isEmpty()) {
        noMutationFound = false;
      }

      if (this.data.getHom().contains(1)) {
        final List<MutationAnalysisUnit> tus = new ArrayList<>();
        for (final Collection<MutationDetails> ms : grouper.groupMutations(this.code.getCodeUnderTestNames(), mutations)) {
          tus.add(pHOM.makeUnanalysedUnit(ms));
        }
        mae.run(tus);
      }
      interceptor.begin(ClassTree.fromBytes(bas.getBytes(c.asJavaName()).get()));
      pHOM.runMutantsOfOrdersBatchStream(mutations, this.data.getHom());
      interceptor.end();
    }

    if (noMutationFound) {
      if (this.data.shouldFailWhenNoMutations()) {
        throw new PitHelpError(Help.NO_MUTATIONS_FOUND);
      } else {
        LOG.warning(Help.NO_MUTATIONS_FOUND.toString());
      }
    }
  }

  private void runMutantsAll(MutationSource source, MutationGrouper grouper, PitestHOMUtilities pHOM,
                                     MutationAnalysisExecutor mae, MutationInterceptor interceptor, ClassByteArraySource bas) {
    boolean noMutationFound = true;
    final List<MutationDetails> mutants = new ArrayList<>();
    for (ClassName c : this.code.getCodeUnderTestNames()) {

      final List<MutationDetails> foms = new ArrayList<>(source.createMutations(c));
      if (!foms.isEmpty()) {
        noMutationFound = false;
      }

      if (this.data.getHom().contains(1)) {
        mutants.addAll(foms);
      }
      interceptor.begin(ClassTree.fromBytes(bas.getBytes(c.asJavaName()).get()));
      mutants.addAll(pHOM.makeMutantsOfOrders(foms, this.data.getHom()));
      interceptor.end();
    }

    if (noMutationFound) {
      if (this.data.shouldFailWhenNoMutations()) {
        throw new PitHelpError(Help.NO_MUTATIONS_FOUND);
      } else {
        LOG.warning(Help.NO_MUTATIONS_FOUND.toString());
      }
    }

    final List<MutationAnalysisUnit> tus = new ArrayList<>();
    for (final Collection<MutationDetails> ms : grouper.groupMutations(this.code.getCodeUnderTestNames(), mutants)) {
      tus.add(pHOM.makeUnanalysedUnit(ms));
    }
    mae.run(tus);
  }
}
