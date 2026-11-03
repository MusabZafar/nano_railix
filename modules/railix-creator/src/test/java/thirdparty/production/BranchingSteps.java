package thirdparty.production;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CountDownLatch;

/** Independently packaged Steps exercising the public continuation and data contracts. */
public final class BranchingSteps {
    private BranchingSteps() { }

    public static java.util.List<StepDefinition> definitions() {
        final var input = StepDefinition.Input.matcherGroups(StepDefinition.Input.option("literal")
                .input("value", StepDefinition.Input.json(ValueShape.ANY)).fromOwned("value"))
                .withAuthoredOutcomes()
                .routeInput("copies", StepDefinition.Input.json(ValueShape.NUMBER).defaultValue(RailixValue.number(1))
                        .validation(Integer.class, value -> value > 0 ? java.util.Optional.empty()
                                : java.util.Optional.of("Copies must be positive.")));
        return java.util.List.of(StepDefinition.named("test.authored-routes", "1").primaryOutcome("otherwise")
                .input("destinations", input).withContinuations().implementation(AuthoredRoutes.class));
    }

    public static final class AuthoredRoutes {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            for (final var route : input.routes("destinations")) {
                final int copies = ((RailixValue.NumberValue) route.configuration().value("copies")).value().intValueExact();
                for (int index = 0; index < copies; index++) input.continuation(route.outcome()).run(StepInput.ContextMode.SHARED);
            }
            return StepResult.continued();
        }
    }

    public static final class Write {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).write("field", configuration.value("value"));
        }
    }

    public static final class InvalidOutput {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    public static final class AtomicWrite {
        private static final CyclicBarrier READY = new CyclicBarrier(2);

        public static StepResult process(final StepInput configuration, final StepInput input)
                throws InterruptedException {
            try {
                READY.await(5, TimeUnit.SECONDS);
            } catch (final BrokenBarrierException | TimeoutException failure) {
                throw new IllegalStateException("Both independent paths must run concurrently.", failure);
            }
            return StepResult.outcome(input.primaryOutcome())
                    .write("left", configuration.value("value"))
                    .write("right", configuration.value("value"))
                    .write("unique", configuration.value("value"));
        }
    }

    public static final class Fork {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            final StepInput.ContextMode mode = configuration.option("context").equals("copy")
                    ? StepInput.ContextMode.COPY : StepInput.ContextMode.SHARED;
            if (configuration.option("execution").equals("parallel")) {
                input.continuation("left").start(mode);
                input.continuation("right").start(mode);
            } else {
                input.continuation("left").run(mode);
                input.continuation("right").run(mode);
            }
            return StepResult.continued();
        }
    }

    /** Controlled real work which deliberately finishes cleanup after interruption. */
    public static final class Hold {
        public static final CountDownLatch ENTERED = new CountDownLatch(1);
        public static final CountDownLatch RELEASE = new CountDownLatch(1);
        public static final CountDownLatch CANCELLED = new CountDownLatch(1);
        public static final java.util.concurrent.atomic.AtomicBoolean CLOSED = new java.util.concurrent.atomic.AtomicBoolean();

        public static StepResult process(final StepInput configuration, final StepInput input) {
            ENTERED.countDown();
            boolean interrupted = false;
            while (RELEASE.getCount() != 0) {
                try { RELEASE.await(); }
                catch (final InterruptedException cancellation) { interrupted = true; }
            }
            if (CLOSED.get()) throw new IllegalStateException("Resource closed during active work.");
            if (interrupted) Thread.currentThread().interrupt();
            return StepResult.outcome(input.primaryOutcome());
        }

        public static StepResult post(final StepInput configuration, final StepInput input) {
            CLOSED.set(true);
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    public static final class CancelFork {
        public static StepResult process(final StepInput configuration, final StepInput input) throws InterruptedException {
            final var work = input.continuation("left").start(StepInput.ContextMode.SHARED);
            if (!Hold.ENTERED.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Path never started.");
            work.cancel(true);
            Hold.CANCELLED.countDown();
            input.continuation("right").run(StepInput.ContextMode.SHARED);
            return StepResult.continued();
        }
    }

    public static final class LateFork {
        private static final java.util.concurrent.atomic.AtomicReference<StepInput.Continuation> RETAINED =
                new java.util.concurrent.atomic.AtomicReference<>();

        public static StepResult process(final StepInput configuration, final StepInput input) {
            if (RailixValue.string("capture").equals(input.value("mode"))) {
                RETAINED.set(input.continuation("next"));
                return StepResult.outcome("next");
            }
            RETAINED.get().run(StepInput.ContextMode.SHARED);
            return StepResult.continued();
        }
    }

    public static final class Read {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", input.value("source"));
        }
    }

    public static final class ProgramFork {
        public static StepResult process(final StepInput configuration, final StepInput input) throws InterruptedException {
            try { input.continuation("update").start(StepInput.ContextMode.SHARED).get(); }
            catch (final java.util.concurrent.ExecutionException failure) { throw new IllegalStateException(failure); }
            return input.run("steps").writeWhenPresent("field");
        }
    }
}
