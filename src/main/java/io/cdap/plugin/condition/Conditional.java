/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.plugin.condition;

import com.google.common.base.Strings;
import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Macro;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.annotation.Plugin;
import io.cdap.cdap.api.plugin.PluginConfig;
import io.cdap.cdap.etl.api.Arguments;
import io.cdap.cdap.etl.api.FailureCollector;
import io.cdap.cdap.etl.api.PipelineConfigurer;
import io.cdap.cdap.etl.api.condition.Condition;
import io.cdap.cdap.etl.api.condition.ConditionContext;
import io.cdap.cdap.etl.api.condition.StageStatistics;
import io.cdap.cdap.etl.api.validation.InvalidConfigPropertyException;
import org.apache.commons.logging.LogFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class <code>Conditional</code> implements the condition plugin, where
 * conditions are evaluated as expressions. The values in the expression from the
 * previous plugins, runtime or globals are provided as maps to be utilized in the
 * expression.
 *
 * <p>Following are some of the examples of the expression.
 * <code>
 *   // Evaluates to 'true' if the runtime argument is set to 1.
 *   runtime['processing_path'] == 1
 *
 *   // Condition evaluates to 'true' if the number of errors generated by a plugin
 *   // named 'Data Quality' is greater than the value specified in the runtime.
 *   token['Data Quality']['error'] > runtime['max.error.supported']
 *
 *   // First finds the max of errors from two stages of pipeline and then
 *   // compares it against the value specified in the runtime.
 *   math:max(toDouble(token['DQ1']['error']), toDouble(token['DQ2']['error'])) > runtime['max.error.supported']
 * </code></p>
 */
@Plugin(type = Condition.PLUGIN_TYPE)
@Name("Conditional")
@Description("Controls the execution of the pipeline based on the jexl expression.")
public final class Conditional extends Condition {
  private ConditionConfig config;

  static {
    LogFactory.getFactory().setAttribute("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.SLF4JLog");
  }

  /**
   * Create a expression handler by registering functions
   * that can be used within the expression.
   */
  private final EL el = new EL(() -> {
    Map<String, Object> functions = new HashMap<>();
    functions.put(null, Global.class);
    functions.put("math", Math.class);
    return functions;
  });

  /**
   * Configures this plugin during deployment, compiles the expression to
   * check for any errors.
   *
   * <p>Note: that this will only check for the syntax of the expression.</p>
   *
   * @throws IllegalArgumentException if there are any issues with the expression.
   */
  @Override
  public void configurePipeline(PipelineConfigurer configurer) throws IllegalArgumentException {
    super.configurePipeline(configurer);
    FailureCollector collector = configurer.getStageConfigurer().getFailureCollector();
    config.validate(el, collector);

  }

  /**
   * Invoked once during the lifetime of a run to evaluate the expression. The
   * expression evaluated would result in either true or false.
   *
   * @param context of the run.
   * @return true if condition evaluates to true, false otherwise.
   * @throws Exception if there are any issue during the evaluation of expression.
   */
  @Override
  public boolean apply(ConditionContext context) throws Exception {
    FailureCollector collector = context.getFailureCollector();
    config.validate(el, collector);
    collector.getOrThrowException();

    Set<List<String>> variables = el.variables();
    Arguments arguments = context.getArguments();
    Map<String, StageStatistics> statistics = context.getStageStatistics();

    Map<String, Object> runtime = new HashMap<>();
    Map<String, Map<String, Object>> tokens = new HashMap<>();
    Map<String, Object> globals = new HashMap<>();
    for (List<String> variable : variables) {
      String type = variable.get(0);
      if (type.contentEquals("runtime")) {
        if (!arguments.has(variable.get(1))) {
          throw new Exception(
            String.format("Condition includes a runtime argument '%s' that does not exist.", variable.get(1))
          );
        }

        runtime.put(variable.get(1), arguments.get(variable.get(1)));
      } else if (type.contentEquals("token")) {
        String stage = variable.get(1);
        Map<String, Object> stats = new HashMap<>();
        StageStatistics stageStatistics = statistics.get(stage);
        if (stageStatistics == null) {
          // Statistics for the stage are unavailable possibly because the stage did not receive/emitted any record
          // or stage does not exists
          stats.put("input", 0);
          stats.put("output", 0);
          stats.put("error", 0);
        } else {
          stats.put("input", stageStatistics.getInputRecordsCount());
          stats.put("output", stageStatistics.getOutputRecordsCount());
          stats.put("error", stageStatistics.getErrorRecordsCount());
        }
        tokens.put(stage, stats);
      } else if (type.contentEquals("global")) {
        globals.put("pipeline", context.getPipelineName());
        globals.put("namespace", context.getNamespace());
        globals.put("logical_start_time", context.getLogicalStartTime());
        globals.put("plugin", context.getStageName());
      } else {
        throw new Exception(
          String.format("Invalid map variable '%s' specified. Valid map variables are " +
                          "'runtime', 'token' and 'global'.", type)
        );
      }
    }

    ELContext elCtx = new ELContext()
      .add("runtime", runtime)
      .add("token", tokens)
      .add("global", globals);
    ELResult result = el.execute(elCtx);
    return result.getBoolean();
  }

  /**
   * Configuration for this plugin.
   */
  public static final class ConditionConfig extends PluginConfig {
    static final String EXPRESSION = "expression";

    @Name(EXPRESSION)
    @Description("The conditions are specified as jexl expressions and the variables for " +
      "expression can include values specified as runtime arguments of the pipeline, token " +
      "from plugins prior to the condition and global that includes global information about " +
      "pipeline like pipeline name, logical start time. Example: ((token['Data Quality']['error'] " +
      "/ token['File']['output']) * 100) > runtime['error_percentage']")
    @Macro
    private final String expression;

    public ConditionConfig(String expression) {
      this.expression = expression;
    }

    String getExpression() {
      return expression;
    }

    public void validate(EL el, FailureCollector collector) {
      if (!containsMacro("expression") && !Strings.isNullOrEmpty(expression)) {
        try {
          el.compile(expression);
        } catch (ELException e) {
          collector.addFailure(String.format("Error encountered while compiling the expression : %s",
                                             e.getMessage()), null).withConfigProperty(ConditionConfig.EXPRESSION);
        }
      }
    }
  }
}

