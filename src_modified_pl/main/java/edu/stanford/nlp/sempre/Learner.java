package edu.stanford.nlp.sempre;

import ch.ethz.thesis.Derivation2Question;
import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import fig.basic.*;
import fig.exec.Execution;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Original code by Percy Liang, parts of this code have been modified for the paper
 * Neural Multi-Step Reasoning for Question Answering on Semi-Structured Tables
 * to persist paraphrases to disk.
 */

/**
 * The main learning loop.  Goes over a dataset multiple times, calling the
 * parser and updating parameters.
 *
 * @author Percy Liang
 */
public class Learner {
    public static class Options {
        @Option(gloss = "Number of iterations to train")
        public int maxTrainIters = 0;

        @Option(gloss = "When using mini-batch updates for SGD, this is the batch size")
        public int batchSize = 1;  // Default is SGD

        @Option(gloss = "Write predDerivations to examples file (huge)")
        public boolean outputPredDerivations = false;

        @Option(gloss = "Dump all features and compatibility scores")
        public boolean dumpFeaturesAndCompatibility = false;

        @Option(gloss = "Whether to add feedback")
        public boolean addFeedback = false;
        @Option(gloss = "Whether to sort on feedback")
        public boolean sortOnFeedback = true;

        @Option(gloss = "Verbosity")
        public int verbose = 0;

        @Option(gloss = "Initialize with these parameters")
        public List<Pair<String, Double>> initialization;

        @Option(gloss = "whether to update weights")
        public boolean updateWeights = true;
        @Option(gloss = "whether to check gradient")
        public boolean checkGradient = false;
    }

    public static Options opts = new Options();

    private Parser parser;
    private final Params params;
    private final Dataset dataset;
    private final PrintWriter eventsOut;  // For printing a machine-readable log
    private final List<SemanticFn> semFuncsToUpdate;

    public Learner(Parser parser, Params params, Dataset dataset) {
        this.parser = parser;
        this.params = params;
        this.dataset = dataset;
        this.eventsOut = IOUtils.openOutAppendEasy(Execution.getFile("learner.events"));
        if (opts.initialization != null)
            this.params.init(opts.initialization);
        // collect all semantic functions to update
        semFuncsToUpdate = new ArrayList<>();

        for (Rule rule : parser.grammar.getRules()) {
            SemanticFn currSemFn = rule.getSem();
            boolean toAdd = true;
            for (SemanticFn semFuncToUpdate : semFuncsToUpdate) {
                if (semFuncToUpdate.getClass().equals(currSemFn.getClass())) {
                    toAdd = false;
                    break;
                }
            }
            if (toAdd)
                semFuncsToUpdate.add(currSemFn);
        }
    }

    public void learn() {
        learn(opts.maxTrainIters, Maps.newHashMap());
    }

    /**
     * @param evaluations Evaluations per iteration per group.
     */
    public void learn(int numIters, Map<String, List<Evaluation>> evaluations) {
        LogInfo.begin_track("Learner.learn()");
        // if when we start we have parameters already - need to sort the semantic functions.
        if (!params.isEmpty())
            sortOnFeedback();
        // For each iteration, go through the groups and parse (updating if train).
        for (int iter = 0; iter <= numIters; iter++) {

            LogInfo.begin_track("Iteration %s/%s", iter, numIters);
            Execution.putOutput("iter", iter);

            // Averaged over all iterations
            // Group -> evaluation for that group.
            Map<String, Evaluation> meanEvaluations = Maps.newHashMap();

            // Clear
            for (String group : dataset.groups())
                meanEvaluations.put(group, new Evaluation());

            // Test and train
            for (String group : dataset.groups()) {
                boolean lastIter = (iter == numIters);
                boolean updateWeights = opts.updateWeights && group.equals("train") && !lastIter;  // Don't train on last iteration
                Evaluation eval = processExamples(
                        iter,
                        group,
                        dataset.examples(group),
                        updateWeights);
                MapUtils.addToList(evaluations, group, eval);
                meanEvaluations.get(group).add(eval);
                StopWatchSet.logStats();
            }

            // Write out parameters
            String path = Execution.getFile("params." + iter);
            if (path != null) {
                params.write(path);
                Utils.systemHard("ln -sf params." + iter + " " + Execution.getFile("params"));
            }

            // Write out examples and predictions
            if (opts.outputPredDerivations)
                for (String group : dataset.groups())
                    ExampleUtils.writeSDF(iter, group, meanEvaluations.get(group), dataset.examples(group), opts.outputPredDerivations);

            LogInfo.end_track();
        }
        LogInfo.end_track();
    }

    public void onlineLearnExample(Example ex) {
        LogInfo.begin_track("onlineLearnExample: %s derivations", ex.predDerivations.size());
        HashMap<String, Double> counts = new HashMap<>();
        for (Derivation deriv : ex.predDerivations)
            deriv.compatibility = parser.valueEvaluator.getCompatibility(ex.targetValue, deriv.value);
        ParserState.computeExpectedCounts(ex.predDerivations, counts);
        params.update(counts);
        LogInfo.end_track();
    }

    private Evaluation processExamples(int iter, String group,
                                       List<Example> examples,
                                       boolean computeExpectedCounts) {
        Evaluation evaluation = new Evaluation();

        try (FileWriter fw = new FileWriter("translated_" + group + "_iter_" + iter, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {
            if (examples.size() == 0)
                return evaluation;

            final String prefix = "iter=" + iter + "." + group;

            Execution.putOutput("group", group);
            LogInfo.begin_track_printAll(
                    "Processing %s: %s examples", prefix, examples.size());
            LogInfo.begin_track("Examples");

            Map<String, Double> counts = new HashMap<>();
            int batchSize = 0;
            for (int e = 0; e < examples.size(); e++) {

                Example ex = examples.get(e);

                LogInfo.begin_track_printAll(
                        "%s: example %s/%s: %s", prefix, e, examples.size(), ex.id);
                ex.log();
                Execution.putOutput("example", e);

                ParserState state = parseExample(params, ex, computeExpectedCounts);
                if (computeExpectedCounts) {
                    if (opts.checkGradient) {
                        LogInfo.begin_track("Checking gradient");
                        checkGradient(ex, state);
                        LogInfo.end_track();
                    }

                    SempreUtils.addToDoubleMap(counts, state.expectedCounts);

                    batchSize++;
                    if (batchSize >= opts.batchSize) {
                        // Gathered enough examples, update parameters
                        updateWeights(counts);
                        batchSize = 0;
                    }
                }
                // }

                LogInfo.logs("Current: %s", ex.evaluation.summary());
                evaluation.add(ex.evaluation);
                LogInfo.logs("Cumulative(%s): %s", prefix, evaluation.summary());

                printLearnerEventsIter(ex, iter, group);
                LogInfo.end_track();
                if (opts.addFeedback && computeExpectedCounts)
                    addFeedback(ex);

                for (Derivation deriv : ex.predDerivations) {
                    String question = ex.getUtterance();
                    String id = ex.getId();
                    String textualForm = Derivation2Question.transform(deriv, ex);
//                    String textualForm = deriv.getFormula().toString();
                    String label = (deriv.compatibility == 1.0) ? "true" : "false";
                    ListValue answer = (ListValue) deriv.getValue();
                    String answerString = answer.toString().replace("\n", " ");
                    out.println(id + "\t" + question + "\t" + textualForm + "\t" + label + "\t" + answerString);
                }

                // To save memory
                ex.predDerivations.clear();
            }

            if (computeExpectedCounts && batchSize > 0)
                updateWeights(counts);
            params.finalizeWeights();
            if (opts.sortOnFeedback && computeExpectedCounts)
                sortOnFeedback();

            LogInfo.end_track();
            logEvaluationStats(evaluation, prefix);
            printLearnerEventsSummary(evaluation, iter, group);
            LogInfo.end_track();

            /**
             * Close opened streams
             */
            out.close();
            bw.close();
            fw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return evaluation;
    }

    private void checkGradient(Example ex, ParserState state) {
        double eps = 1e-2;
        for (String feature : state.expectedCounts.keySet()) {
            LogInfo.begin_track("feature=%s", feature);
            double computedGradient = state.expectedCounts.get(feature);
            Params perturbedParams = this.params.copyParams();
            perturbedParams.getWeights().put(feature, perturbedParams.getWeight(feature) + eps);
            ParserState perturbedState = parseExample(perturbedParams, ex, true);
            double checkedGradient = (perturbedState.objectiveValue - state.objectiveValue) / eps;
            LogInfo.logs("Learner.checkGradient(): weight=%s, pertWeight=%s, obj=%s, pertObj=%s, feature=%s, computed=%s, checked=%s, diff=%s",
                    params.getWeight(feature), perturbedParams.getWeight(feature),
                    state.objectiveValue, perturbedState.objectiveValue,
                    feature,
                    computedGradient, checkedGradient, Math.abs(checkedGradient - computedGradient));
            LogInfo.end_track();
        }
    }

    private void sortOnFeedback() {
        for (SemanticFn semFn : semFuncsToUpdate) {
            semFn.sortOnFeedback(parser.getSearchParams(params));
        }
    }

    private void addFeedback(Example ex) {
        for (SemanticFn semFn : semFuncsToUpdate) {
            semFn.addFeedback(ex);
        }
    }

    private ParserState parseExample(Params params, Example ex, boolean computeExpectedCounts) {
        StopWatchSet.begin("Parser.parse");
        ParserState res = this.parser.parse(params, ex, computeExpectedCounts);
        StopWatchSet.end();
        return res;
    }

    private void updateWeights(Map<String, Double> counts) {
        StopWatchSet.begin("Learner.updateWeights");
        LogInfo.begin_track("Updating learner weights");
        double sum = 0;
        for (double v : counts.values()) sum += v * v;
        if (opts.verbose >= 2)
            SempreUtils.logMap(counts, "gradient");
        LogInfo.logs("L2 norm: %s", Math.sqrt(sum));
        params.update(counts);
        if (opts.verbose >= 2)
            params.log();
        counts.clear();
        LogInfo.end_track();
        StopWatchSet.end();
    }

    // Print summary over all examples
    private void logEvaluationStats(Evaluation evaluation, String prefix) {
        LogInfo.logs("Stats for %s: %s", prefix, evaluation.summary());
        // evaluation.add(LexiconFn.lexEval);
        evaluation.logStats(prefix);
        evaluation.putOutput(prefix);
    }

    private void printLearnerEventsIter(Example ex, int iter, String group) {
        if (eventsOut == null)
            return;
        List<String> fields = new ArrayList<>();
        fields.add("iter=" + iter);
        fields.add("group=" + group);
        fields.add("utterance=" + ex.utterance);
        fields.add("targetValue=" + ex.targetValue);
        if (ex.predDerivations.size() > 0) {
            Derivation deriv = ex.predDerivations.get(0);
            fields.add("predValue=" + deriv.value);
            fields.add("predFormula=" + deriv.formula);
        }
        fields.add(ex.evaluation.summary("\t"));
        eventsOut.println(Joiner.on('\t').join(fields));
        eventsOut.flush();

        // Print out features and the compatibility across all the derivations
        if (opts.dumpFeaturesAndCompatibility) {
            for (Derivation deriv : ex.predDerivations) {
                fields = new ArrayList<>();
                fields.add("iter=" + iter);
                fields.add("group=" + group);
                fields.add("utterance=" + ex.utterance);
                Map<String, Double> features = new HashMap<>();
                deriv.incrementAllFeatureVector(1, features);
                for (String f : features.keySet()) {
                    double v = features.get(f);
                    fields.add(f + "=" + v);
                }
                fields.add("comp=" + deriv.compatibility);
                eventsOut.println(Joiner.on('\t').join(fields));
            }
        }
    }

    private void printLearnerEventsSummary(Evaluation evaluation,
                                           int iter,
                                           String group) {
        if (eventsOut == null)
            return;
        List<String> fields = new ArrayList<>();
        fields.add("iter=" + iter);
        fields.add("group=" + group);
        fields.add(evaluation.summary("\t"));
        eventsOut.println(Joiner.on('\t').join(fields));
        eventsOut.flush();
    }
}
