/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.direct;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import org.apache.beam.runners.core.GroupAlsoByWindowsDoFn;
import org.apache.beam.runners.core.GroupByKeyViaGroupByKeyOnly;
import org.apache.beam.runners.core.GroupByKeyViaGroupByKeyOnly.GroupAlsoByWindow;
import org.apache.beam.runners.core.GroupByKeyViaGroupByKeyOnly.GroupByKeyOnly;
import org.apache.beam.runners.core.ReduceFnRunner;
import org.apache.beam.runners.core.SystemReduceFn;
import org.apache.beam.runners.core.triggers.ExecutableTriggerStateMachine;
import org.apache.beam.runners.core.triggers.TriggerStateMachines;
import org.apache.beam.runners.direct.DirectExecutionContext.DirectStepContext;
import org.apache.beam.runners.direct.DirectGroupByKey.DirectGroupAlsoByWindow;
import org.apache.beam.runners.direct.DirectRunner.CommittedBundle;
import org.apache.beam.runners.direct.DirectRunner.UncommittedBundle;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.transforms.Aggregator;
import org.apache.beam.sdk.transforms.AppliedPTransform;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.Sum;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.util.KeyedWorkItem;
import org.apache.beam.sdk.util.TimerInternals;
import org.apache.beam.sdk.util.TimerInternals.TimerData;
import org.apache.beam.sdk.util.WindowTracing;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowingInternals;
import org.apache.beam.sdk.util.WindowingStrategy;
import org.apache.beam.sdk.util.state.CopyOnAccessInMemoryStateInternals;
import org.apache.beam.sdk.util.state.StateInternals;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.joda.time.Instant;

/**
 * The {@link DirectRunner} {@link TransformEvaluatorFactory} for the
 * {@link GroupByKeyOnly} {@link PTransform}.
 */
class GroupAlsoByWindowEvaluatorFactory implements TransformEvaluatorFactory {
  private final EvaluationContext evaluationContext;

  GroupAlsoByWindowEvaluatorFactory(EvaluationContext evaluationContext) {
    this.evaluationContext = evaluationContext;
  }

  @Override
  public <InputT> TransformEvaluator<InputT> forApplication(
      AppliedPTransform<?, ?, ?> application,
      CommittedBundle<?> inputBundle) {
    @SuppressWarnings({"cast", "unchecked", "rawtypes"})
    TransformEvaluator<InputT> evaluator =
        createEvaluator(
            (AppliedPTransform) application, (CommittedBundle) inputBundle);
    return evaluator;
  }

  @Override
  public void cleanup() {}

  private <K, V> TransformEvaluator<KeyedWorkItem<K, V>> createEvaluator(
      AppliedPTransform<
              PCollection<KeyedWorkItem<K, V>>,
              PCollection<KV<K, Iterable<V>>>,
              DirectGroupAlsoByWindow<K, V>>
          application,
      CommittedBundle<KeyedWorkItem<K, V>> inputBundle) {
    return new GroupAlsoByWindowEvaluator<>(
        evaluationContext, inputBundle, application);
  }

  /**
   * A transform evaluator for the pseudo-primitive {@link GroupAlsoByWindow}. Windowing is ignored;
   * all input should be in the global window since all output will be as well.
   *
   * @see GroupByKeyViaGroupByKeyOnly
   */
  private static class GroupAlsoByWindowEvaluator<K, V>
      implements TransformEvaluator<KeyedWorkItem<K, V>> {
    private final EvaluationContext evaluationContext;
    private final AppliedPTransform<
        PCollection<KeyedWorkItem<K, V>>, PCollection<KV<K, Iterable<V>>>,
        DirectGroupAlsoByWindow<K, V>>
        application;

    private final DirectStepContext stepContext;
    private @SuppressWarnings("unchecked") final WindowingStrategy<?, BoundedWindow>
        windowingStrategy;

    private final Collection<UncommittedBundle<?>> outputBundles;
    private final ImmutableList.Builder<WindowedValue<KeyedWorkItem<K, V>>> unprocessedElements;
    private final AggregatorContainer.Mutator aggregatorChanges;

    private final SystemReduceFn<K, V, Iterable<V>, Iterable<V>, BoundedWindow> reduceFn;
    private final Aggregator<Long, Long> droppedDueToClosedWindow;
    private final Aggregator<Long, Long> droppedDueToLateness;

    public GroupAlsoByWindowEvaluator(
        final EvaluationContext evaluationContext,
        CommittedBundle<KeyedWorkItem<K, V>> inputBundle,
        final AppliedPTransform<
                PCollection<KeyedWorkItem<K, V>>,
                PCollection<KV<K, Iterable<V>>>,
                DirectGroupAlsoByWindow<K, V>> application) {
      this.evaluationContext = evaluationContext;
      this.application = application;

      stepContext = evaluationContext
          .getExecutionContext(application, inputBundle.getKey())
          .getOrCreateStepContext(
              evaluationContext.getStepName(application), application.getTransform().getName());
      windowingStrategy =
          (WindowingStrategy<?, BoundedWindow>)
              application.getTransform().getInputWindowingStrategy();

      outputBundles = new ArrayList<>();
      unprocessedElements = ImmutableList.builder();
      aggregatorChanges = evaluationContext.getAggregatorMutator();

      Coder<V> valueCoder =
          application.getTransform().getValueCoder(inputBundle.getPCollection().getCoder());
      reduceFn = SystemReduceFn.buffering(valueCoder);
      droppedDueToClosedWindow = aggregatorChanges.createSystemAggregator(stepContext,
          GroupAlsoByWindowsDoFn.DROPPED_DUE_TO_CLOSED_WINDOW_COUNTER,
          new Sum.SumLongFn());
      droppedDueToLateness = aggregatorChanges.createSystemAggregator(stepContext,
          GroupAlsoByWindowsDoFn.DROPPED_DUE_TO_LATENESS_COUNTER,
          new Sum.SumLongFn());
    }

    @Override
    public void processElement(WindowedValue<KeyedWorkItem<K, V>> element) throws Exception {
      KeyedWorkItem<K, V> workItem = element.getValue();
      K key = workItem.key();

      UncommittedBundle<KV<K, Iterable<V>>> bundle =
          evaluationContext.createBundle(application.getOutput());
      outputBundles.add(bundle);
      CopyOnAccessInMemoryStateInternals<K> stateInternals =
          (CopyOnAccessInMemoryStateInternals<K>) stepContext.stateInternals();
      DirectTimerInternals timerInternals = stepContext.timerInternals();
      ReduceFnRunner<K, V, Iterable<V>, BoundedWindow> reduceFnRunner =
          new ReduceFnRunner<>(
              key,
              windowingStrategy,
              ExecutableTriggerStateMachine.create(
                  TriggerStateMachines.stateMachineForTrigger(windowingStrategy.getTrigger())),
              stateInternals,
              timerInternals,
              new DirectWindowingInternals<>(bundle),
              droppedDueToClosedWindow,
              reduceFn,
              evaluationContext.getPipelineOptions());

      // Drop any elements within expired windows
      reduceFnRunner.processElements(
          dropExpiredWindows(key, workItem.elementsIterable(), timerInternals));
      for (TimerData timer : workItem.timersIterable()) {
        reduceFnRunner.onTimer(timer);
      }
      reduceFnRunner.persist();
    }

    @Override
    public TransformResult finishBundle() throws Exception {
      // State is initialized within the constructor. It can never be null.
      CopyOnAccessInMemoryStateInternals<?> state = stepContext.commitState();
      return StepTransformResult.withHold(application, state.getEarliestWatermarkHold())
          .withState(state)
          .addOutput(outputBundles)
          .withTimerUpdate(stepContext.getTimerUpdate())
          .withAggregatorChanges(aggregatorChanges)
          .addUnprocessedElements(unprocessedElements.build())
          .build();
    }

    /**
     * Returns an {@code Iterable<WindowedValue<InputT>>} that only contains non-late input
     * elements.
     */
    public Iterable<WindowedValue<V>> dropExpiredWindows(
        final K key, Iterable<WindowedValue<V>> elements, final TimerInternals timerInternals) {
      return FluentIterable.from(elements)
          .transformAndConcat(
              // Explode windows to filter out expired ones
              new Function<WindowedValue<V>, Iterable<WindowedValue<V>>>() {
                @Override
                public Iterable<WindowedValue<V>> apply(WindowedValue<V> input) {
                  return input.explodeWindows();
                }
              })
          .filter(
              new Predicate<WindowedValue<V>>() {
                @Override
                public boolean apply(WindowedValue<V> input) {
                  BoundedWindow window = Iterables.getOnlyElement(input.getWindows());
                  boolean expired =
                      window
                          .maxTimestamp()
                          .plus(windowingStrategy.getAllowedLateness())
                          .isBefore(timerInternals.currentInputWatermarkTime());
                  if (expired) {
                    // The element is too late for this window.
                    droppedDueToLateness.addValue(1L);
                    WindowTracing.debug(
                        "GroupAlsoByWindow: Dropping element at {} for key: {}; "
                            + "window: {} since it is too far behind inputWatermark: {}",
                        input.getTimestamp(),
                        key,
                        window,
                        timerInternals.currentInputWatermarkTime());
                  }
                  // Keep the element if the window is not expired.
                  return !expired;
                }
              });
    }
  }

  private static class DirectWindowingInternals<K, V>
      implements WindowingInternals<Object, KV<K, Iterable<V>>> {
    private final UncommittedBundle<KV<K, Iterable<V>>> bundle;

    private DirectWindowingInternals(
        UncommittedBundle<KV<K, Iterable<V>>> bundle) {
      this.bundle = bundle;
    }

    @Override
    public StateInternals<?> stateInternals() {
      throw new UnsupportedOperationException(
          String.format(
              "%s should use the %s it is provided rather than the contents of %s",
              ReduceFnRunner.class.getSimpleName(),
              StateInternals.class.getSimpleName(),
              getClass().getSimpleName()));
    }

    @Override
    public void outputWindowedValue(
        KV<K, Iterable<V>> output,
        Instant timestamp,
        Collection<? extends BoundedWindow> windows,
        PaneInfo pane) {
      bundle.add(WindowedValue.of(output, timestamp, windows, pane));
    }

    @Override
    public TimerInternals timerInternals() {
      throw new UnsupportedOperationException(
          String.format(
              "%s should use the %s it is provided rather than the contents of %s",
              ReduceFnRunner.class.getSimpleName(),
              TimerInternals.class.getSimpleName(),
              getClass().getSimpleName()));
    }

    @Override
    public Collection<? extends BoundedWindow> windows() {
      throw new IllegalArgumentException(
          String.format(
              "%s should not access Windows via %s.windows(); "
                  + "it should instead inspect the window of the input elements",
              GroupAlsoByWindowEvaluator.class.getSimpleName(),
              WindowingInternals.class.getSimpleName()));
    }

    @Override
    public PaneInfo pane() {
      throw new IllegalArgumentException(
          String.format(
              "%s should not access Windows via %s.windows(); "
                  + "it should instead inspect the window of the input elements",
              GroupAlsoByWindowEvaluator.class.getSimpleName(),
              WindowingInternals.class.getSimpleName()));
    }

    @Override
    public <T> void writePCollectionViewData(
        TupleTag<?> tag, Iterable<WindowedValue<T>> data, Coder<T> elemCoder) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T sideInput(PCollectionView<T> view, BoundedWindow mainInputWindow) {
      throw new UnsupportedOperationException();
    }
  }
}
