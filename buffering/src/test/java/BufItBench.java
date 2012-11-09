import com.google.caliper.Param;
import com.google.caliper.Runner;
import com.google.caliper.SimpleBenchmark;
import com.google.common.collect.AbstractIterator;

import java.util.Iterator;

// See my run @ http://microbenchmarks.appspot.com/run/brianfromoregon@gmail.com/BufItBench
public class BufItBench extends SimpleBenchmark {
    static enum WhoIsFaster {
        PRODUCER, CONSUMER, SAME
    }

    @Param(value = {"1", "10"})
    int bufferSize;

    @Param
    WhoIsFaster whoIsFaster;

    public void timeThreadFactory(int reps) {
        Load load = new Load(reps, whoIsFaster) {
            @Override
            protected Iterator<Integer> buffer(Iterator<Integer> source) {
                return new ThreadFactoryBufferedIterator<Integer>(bufferSize, source);
            }
        };
        load.run();
    }

    public void timeExecutorService(int reps) {
        Load load = new Load(reps, whoIsFaster) {
            @Override
            protected Iterator<Integer> buffer(Iterator<Integer> source) {
                return new ExecutorServiceBufferedIterator<Integer>(bufferSize, source);
            }
        };
        load.run();
    }

    public static void main(String[] args) {
        Runner.main(BufItBench.class, "--vm", "/jdk/sunjdk1.6.0_24/bin/java -server");
    }

    abstract static class Load {
        final Iterator<Integer> source;
        final WhoIsFaster whoIsFaster;
        final Runnable yielder;

        public Load(final int values, WhoIsFaster whoIsFaster) {
            this.whoIsFaster = whoIsFaster;
            yielder = new Runnable() {
                @Override
                public void run() {
                    Thread.yield();
                }
            };
            source = buffer(new AbstractIterator<Integer>() {
                int i = 0;

                @Override
                protected Integer computeNext() {
                    if (i >= values)
                        return endOfData();
                    else {
                        if (Load.this.whoIsFaster == WhoIsFaster.CONSUMER)
                            yielder.run();
                        return i++;
                    }
                }
            });
        }

        public void run() {
            while (source.hasNext()) {
                if (whoIsFaster == WhoIsFaster.PRODUCER)
                    yielder.run();
                source.next();
            }
        }

        protected abstract Iterator<Integer> buffer(Iterator<Integer> source);
    }
}
