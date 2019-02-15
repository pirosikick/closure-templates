/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.passes;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.base.internal.TriState;
import com.google.template.soy.conformance.ValidatedConformanceConfig;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.logging.ValidatedLoggingConfig;
import com.google.template.soy.shared.SoyGeneralOptions;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.TemplateRegistry;
import com.google.template.soy.types.SoyTypeRegistry;
import java.util.Map;

/**
 * Configures all compiler passes.
 *
 * <p>This arranges all compiler passes into four phases.
 *
 * <ul>
 *   <li>The single file passes. This includes AST rewriting passes such as {@link
 *       ResolveExpressionTypesPass} and {@link RewriteGenderMsgsPass} and other kinds of validation
 *       that doesn't require information about the full file set.
 *   <li>Cross template checking passes. This includes AST validation passes like the {@link
 *       CheckVisibilityPass}. Passes should run here if they need to check the relationships
 *       between templates.
 *   <li>The autoescaper. This runs in its own special phase because it can do special things like
 *       create synthetic templates and add them to the tree.
 *   <li>Simplification passes. This includes tree simplification passes like the optimizer. These
 *       should run last so that they can simplify code generated by any earlier pass.
 * </ul>
 *
 * <p>The reason things have been divided in this way is partially to create consistency and also to
 * enable other compiler features. For example, for in process (server side) compilation we can
 * cache the results of the single file passes to speed up edit-refresh flows. Also, theoretically,
 * we could run the single file passes for each file in parallel.
 *
 * <p>A note on ordering. There is no real structure to the ordering of the passes beyond what is
 * documented in comments. Many passes do rely on running before/after a different pass (e.g. {@link
 * ResolveExpressionTypesPass} needs to run after {@link ResolveNamesPass}), but there isn't any
 * dependency system in place.
 */
public final class PassManager {

  /**
   * Pass continuation rules.
   *
   * <p>These rules are used when running compile passes. You can stop compilation either before or
   * after a pass. By default, compilation continues after each pass without stopping.
   */
  public enum PassContinuationRule {
    STOP_BEFORE_PASS,
    STOP_AFTER_PASS,
  }

  @VisibleForTesting final ImmutableList<CompilerFilePass> singleFilePasses;
  @VisibleForTesting final ImmutableList<CompilerFileSetPass> crossTemplateCheckingPasses;

  private PassManager(
      ImmutableList<CompilerFilePass> singleFilePasses,
      ImmutableList<CompilerFileSetPass> crossTemplateCheckingPasses) {
    this.singleFilePasses = singleFilePasses;
    this.crossTemplateCheckingPasses = crossTemplateCheckingPasses;
  }

  public void runSingleFilePasses(SoyFileNode file, IdGenerator nodeIdGen) {
    for (CompilerFilePass pass : singleFilePasses) {
      pass.run(file, nodeIdGen);
    }
  }

  /**
   * Runs all the fileset passes including the autoescaper and optimization passes if configured.
   */
  public void runWholeFilesetPasses(SoyFileSetNode soyTree, TemplateRegistry templateRegistry) {
    ImmutableList<SoyFileNode> sourceFiles = ImmutableList.copyOf(soyTree.getChildren());
    IdGenerator idGenerator = soyTree.getNodeIdGenerator();
    for (CompilerFileSetPass pass : crossTemplateCheckingPasses) {
      CompilerFileSetPass.Result result = pass.run(sourceFiles, idGenerator, templateRegistry);
      if (result == CompilerFileSetPass.Result.STOP) {
        break;
      }
    }
  }

  /** A builder for configuring the pass manager. */
  public static final class Builder {
    private SoyTypeRegistry registry;
    // TODO(lukes): combine with the print directive map
    private PluginResolver pluginResolver;
    private ImmutableMap<String, ? extends SoyPrintDirective> soyPrintDirectives;
    private ErrorReporter errorReporter;
    private SoyGeneralOptions options;
    private boolean allowUnknownGlobals;
    private boolean allowV1Expression;
    private boolean disableAllTypeChecking;
    private boolean desugarHtmlNodes = true;
    private boolean optimize = true;
    private ValidatedConformanceConfig conformanceConfig = ValidatedConformanceConfig.EMPTY;
    private ValidatedLoggingConfig loggingConfig = ValidatedLoggingConfig.EMPTY;
    private boolean autoescaperEnabled = true;
    private boolean addHtmlAttributesForDebugging = true;
    private final Map<String, PassContinuationRule> passContinuationRegistry = Maps.newHashMap();
    private boolean building;

    public Builder setErrorReporter(ErrorReporter errorReporter) {
      this.errorReporter = checkNotNull(errorReporter);
      return this;
    }

    public Builder setSoyPrintDirectiveMap(
        ImmutableMap<String, ? extends SoyPrintDirective> printDirectives) {
      this.soyPrintDirectives = checkNotNull(printDirectives);
      return this;
    }

    public Builder setTypeRegistry(SoyTypeRegistry registry) {
      this.registry = checkNotNull(registry);
      return this;
    }

    public Builder setPluginResolver(PluginResolver pluginResolver) {
      this.pluginResolver = pluginResolver;
      return this;
    }

    public Builder setGeneralOptions(SoyGeneralOptions options) {
      this.options = options;
      return this;
    }

    /**
     * Disables all the passes which enforce and rely on type information.
     *
     * <p>This should only be used for things like message extraction which doesn't tend to be
     * configured with a type registry.
     */
    public Builder disableAllTypeChecking() {
      this.disableAllTypeChecking = true;
      return this;
    }

    /**
     * Allows unknown global references.
     *
     * <p>This option is only available for backwards compatibility with legacy js only templates
     * and for parseinfo generation.
     */
    public Builder allowUnknownGlobals() {
      this.allowUnknownGlobals = true;
      return this;
    }

    /**
     * Allows v1Expression().
     *
     * <p>This option is only available for backwards compatibility with legacy JS only templates.
     */
    public Builder allowV1Expression() {
      this.allowV1Expression = true;
      return this;
    }

    /**
     * Whether to turn all the html nodes back into raw text nodes before code generation.
     *
     * <p>The default is {@code true}.
     */
    public Builder desugarHtmlNodes(boolean desugarHtmlNodes) {
      this.desugarHtmlNodes = desugarHtmlNodes;
      return this;
    }

    /**
     * Whether to run any of the optimization passes.
     *
     * <p>The default is {@code true}.
     */
    public Builder optimize(boolean optimize) {
      this.optimize = optimize;
      return this;
    }

    public Builder addHtmlAttributesForDebugging(boolean addHtmlAttributesForDebugging) {
      this.addHtmlAttributesForDebugging = addHtmlAttributesForDebugging;
      return this;
    }

    /** Configures this passmanager to run the conformance pass using the given config object. */
    public Builder setConformanceConfig(ValidatedConformanceConfig conformanceConfig) {
      this.conformanceConfig = checkNotNull(conformanceConfig);
      return this;
    }

    public Builder setLoggingConfig(ValidatedLoggingConfig loggingConfig) {
      this.loggingConfig = checkNotNull(loggingConfig);
      return this;
    }

    /**
     * Can be used to enable/disable the autoescaper.
     *
     * <p>The autoescaper is enabled by default.
     */
    public Builder setAutoescaperEnabled(boolean autoescaperEnabled) {
      this.autoescaperEnabled = autoescaperEnabled;
      return this;
    }

    /**
     * Registers a pass continuation rule.
     *
     * <p>By default, compilation continues after each pass. You can stop compilation before or
     * after any pass. This is useful for testing, or for running certain kinds of passes, such as
     * conformance-only compilations.
     *
     * <p>This method overwrites any previously registered rule.
     *
     * @param passName the pass name is derived from the pass class name. For example, the {@link
     *     ResolveNamesPass} is named "ResolveNames". See {@link CompilerFilePass#name()}.
     */
    public Builder addPassContinuationRule(String passName, PassContinuationRule rule) {
      checkNotNull(rule);
      passContinuationRegistry.put(passName, rule);
      return this;
    }

    public PassManager build() {
      // Single file passes
      // These passes perform tree rewriting and all compiler checks that don't require information
      // about callees.
      // Note that we try to run all of the single file passes to report as many errors as possible,
      // meaning that errors reported in earlier passes do not prevent running subsequent passes.
      building = true;
      ImmutableList.Builder<CompilerFilePass> singleFilePassesBuilder = ImmutableList.builder();
      // needs to come early so that it is consistently enforced
      addPass(
          new EnforceExperimentalFeaturesPass(options.getExperimentalFeatures(), errorReporter),
          singleFilePassesBuilder);
      // needs to come early since it is necessary to create template metadata objects for
      // header compilation
      addPass(new ResolveHeaderParamTypesPass(registry, errorReporter), singleFilePassesBuilder);
      addPass(new BasicHtmlValidationPass(errorReporter), singleFilePassesBuilder);
      // needs to come before SoyConformancePass
      addPass(new ResolvePluginsPass(pluginResolver, registry), singleFilePassesBuilder);
      // The check conformance pass needs to run on the rewritten html nodes, so it must
      // run after HtmlRewritePass
      addPass(new SoyConformancePass(conformanceConfig, errorReporter), singleFilePassesBuilder);
      // needs to run after htmlrewriting, before resolvenames and autoescaping
      addPass(new ContentSecurityPolicyNonceInjectionPass(errorReporter), singleFilePassesBuilder);
      // Needs to run after HtmlRewritePass since it produces the HtmlTagNodes that we use
      // to create placeholders.
      addPass(new InsertMsgPlaceholderNodesPass(errorReporter), singleFilePassesBuilder);
      addPass(new RewriteRemaindersPass(errorReporter), singleFilePassesBuilder);
      addPass(new RewriteGenderMsgsPass(errorReporter), singleFilePassesBuilder);
      // Needs to come after any pass that manipulates msg placeholders.
      addPass(new CalculateMsgSubstitutionInfoPass(errorReporter), singleFilePassesBuilder);
      addPass(new CheckNonEmptyMsgNodesPass(errorReporter), singleFilePassesBuilder);
      // Run before the RewriteGlobalsPass as it removes some globals.
      addPass(new VeRewritePass(errorReporter), singleFilePassesBuilder);
      addPass(
          new RewriteGlobalsPass(registry, options.getCompileTimeGlobals(), errorReporter),
          singleFilePassesBuilder);
      // needs to happen after rewrite globals
      addPass(new XidPass(errorReporter), singleFilePassesBuilder);
      // Needs to be before ResolveNamesPass.
      addPass(new V1ExpressionPass(allowV1Expression, errorReporter), singleFilePassesBuilder);
      addPass(new ResolveNamesPass(errorReporter), singleFilePassesBuilder);
      // needs to be after ResolveNames and MsgsPass
      addPass(new MsgWithIdFunctionPass(errorReporter), singleFilePassesBuilder);
      // can run anywhere
      addPass(new CheckEscapingSanityFilePass(errorReporter), singleFilePassesBuilder);
      // The StrictHtmlValidatorPass needs to run after ResolveNames.
      addPass(new StrictHtmlValidationPass(errorReporter), singleFilePassesBuilder);

      if (addHtmlAttributesForDebugging) {
        // needs to run after MsgsPass (so we don't mess up the auto placeholder naming algorithm)
        // and before ResolveExpressionTypesPass (since we insert expressions).
        addPass(new AddDebugAttributesPass(), singleFilePassesBuilder);
      }
      if (!disableAllTypeChecking) {
        addPass(new CheckDeclaredTypesPass(errorReporter), singleFilePassesBuilder);
        addPass(
            new ResolveExpressionTypesPass(registry, errorReporter, loggingConfig),
            singleFilePassesBuilder);
        addPass(new VeLogRewritePass(), singleFilePassesBuilder);
        // needs to run after both resolve types and htmlrewrite pass
        addPass(new VeLogValidationPass(errorReporter, registry), singleFilePassesBuilder);
      }
      addPass(new ResolvePackageRelativeCssNamesPass(errorReporter), singleFilePassesBuilder);
      if (!allowUnknownGlobals) {
        // Must come after RewriteGlobalsPass since that is when values are substituted.
        // We should also run after the ResolveNamesPass which checks for global/param ambiguity and
        // may issue better error messages.
        addPass(new CheckGlobalsPass(errorReporter), singleFilePassesBuilder);
      }
      addPass(
          new ValidateAliasesPass(registry, errorReporter, options, loggingConfig),
          singleFilePassesBuilder);
      // If requiring strict autoescaping, check and enforce it.
      if (options.isStrictAutoescapingRequired() == TriState.ENABLED) {
        addPass(new AssertStrictAutoescapingPass(errorReporter), singleFilePassesBuilder);
      }
      // Needs to run after HtmlRewritePass.
      addPass(new KeyCommandPass(errorReporter, disableAllTypeChecking), singleFilePassesBuilder);
      // Needs to run after HtmlRewritePass and StrictHtmlValidationPass (for single root
      // validation).
      addPass(new SoyElementPass(errorReporter), singleFilePassesBuilder);

      // Cross template checking passes

      // Fileset passes run on all sources files and have access to a template registry so they can
      // examine information about dependencies. These are naturally more expensive and should be
      // reserved for checks that require transitive call information (or full delegate sets).
      // Notably, the results of these passes cannot be cached in the AST cache.  So minimize their
      // use.
      ImmutableList.Builder<CompilerFileSetPass> crossTemplateCheckingPassesBuilder =
          ImmutableList.builder();
      addPass(new CheckTemplateHeaderVarsPass(errorReporter), crossTemplateCheckingPassesBuilder);
      if (!disableAllTypeChecking) {
        addPass(new CheckTemplateCallsPass(errorReporter), crossTemplateCheckingPassesBuilder);
      }
      addPass(new CheckTemplateVisibilityPass(errorReporter), crossTemplateCheckingPassesBuilder);
      addPass(new CheckDelegatesPass(errorReporter), crossTemplateCheckingPassesBuilder);
      // If disallowing external calls, perform the check.
      if (options.allowExternalCalls() == TriState.DISABLED) {
        addPass(new StrictDepsPass(errorReporter), crossTemplateCheckingPassesBuilder);
      }

      if (autoescaperEnabled) {
        addPass(
            new AutoescaperPass(errorReporter, soyPrintDirectives),
            crossTemplateCheckingPassesBuilder);
        // Relies on information from the autoescaper and valid type information
        if (!disableAllTypeChecking) {
          addPass(
              new CheckBadContextualUsagePass(errorReporter), crossTemplateCheckingPassesBuilder);
        }
      }

      // Simplification Passes.
      // These tend to simplify or canonicalize the tree in order to simplify the task of code
      // generation.

      if (desugarHtmlNodes) {
        // always desugar before the end since the backends (besides incremental dom) cannot handle
        // the nodes.
        addPass(new DesugarHtmlNodesPass(), crossTemplateCheckingPassesBuilder);
      }
      // TODO(lukes): there should only be one way to disable the optimizer, not 2
      if (optimize && options.isOptimizerEnabled()) {
        addPass(new OptimizationPass(), crossTemplateCheckingPassesBuilder);
      }
      // DesugarHtmlNodesPass may chop up RawTextNodes, and OptimizationPass may produce additional
      // RawTextNodes. Stich them back together here.
      addPass(new CombineConsecutiveRawTextNodesPass(), crossTemplateCheckingPassesBuilder);
      building = false;
      if (!passContinuationRegistry.isEmpty()) {
        throw new IllegalStateException(
            "The following continuation rules don't match any pass: " + passContinuationRegistry);
      }
      return new PassManager(
          singleFilePassesBuilder.build(), crossTemplateCheckingPassesBuilder.build());
    }

    <T extends CompilerPass> void addPass(T pass, ImmutableList.Builder<T> builder) {
      PassContinuationRule rule = passContinuationRegistry.remove(pass.name());
      if (!building) {
        return;
      }
      if (rule == null) {
        builder.add(pass);
        return;
      }
      switch (rule) {
        case STOP_AFTER_PASS:
          builder.add(pass);
          // fall-through
        case STOP_BEFORE_PASS:
          building = false;
          return;
      }
      throw new AssertionError("unhandled rule: " + rule);
    }
  }
}
