import com.google.common.base.Throwables;
import com.google.common.collect.AbstractIterator;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.Iterator;
import java.util.concurrent.*;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * An {@link java.util.Iterator} which eagerly fetches and buffers <i>N</i> elements from a source Iterator.
 * <p/>
 * This class supports source iterators with null elements.
 * <p/>
 * If the source Iterator's {@code hasNext} or {@code next} methods throw a {@link RuntimeException} or {@link Error},
 * it will be queued in the buffer and eventually thrown by {@link #hasNext()} or {@link #next()} at which point any
 * further attempts to use this iterator will result in an {@link IllegalStateException}.
 * <p/>
 * If either the internal buffer thread or the consumer thread (the {@link Thread} calling {@link #hasNext()} or {@link
 * #next()}) is seen to be interrupted, its interrupted status will be retained and no more elements will be returned.
 */
public class ExecutorServiceBufferedIterator<E> extends AbstractIterator<E> {

    private static final int ABORT_CHECK_FREQ_MS = 10000;
    private volatile boolean aborted = false;

    private final BlockingQueue<E> buffer;
    private final ExecutorService executor;
    private final Runnable bufferWriterTask;
    private boolean started = false;

    private static final Object END_MARKER = new Object();
    private static final Object NULL_MARKER = new Object();
    private volatile Throwable failedMarker = null;

    /**
     * Calls {@link #ExecutorServiceBufferedIterator(int, Iterator, ExecutorService)} with a single daemon thread
     * executor.
     * @param bufferSize The maximum number of elements to eagerly fetch from the source iterator. Note: one additional
     *                   element will be buffered between calling {@link #hasNext()} and {@link #next()}.
     * @param source     The source iterator.
     */
    public ExecutorServiceBufferedIterator(int bufferSize, final Iterator<E> source) {
        this(bufferSize, source, Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setDaemon(true).build()));
    }

    /**
     * Create a new {@link ExecutorServiceBufferedIterator}. Buffering will start when {@link #hasNext()} or {@link
     * #next()} are first called, or when {@link #prestartBuffering()} is called.
     *
     * @param bufferSize The maximum number of elements to eagerly fetch from the source iterator. Note: one additional
     *                   element will be buffered between calling {@link #hasNext()} and {@link #next()}.
     * @param source     The source iterator.
     * @param executor   Each [read element from source iterator, write to buffer] operation will be scheduled
     *                   independently on this {@link ExecutorService}.
     */
    @SuppressWarnings("unchecked")
    public ExecutorServiceBufferedIterator(int bufferSize, final Iterator<E> source, final ExecutorService executor) {
        checkArgument(bufferSize > 0, "bufferSize > 0");
        if (bufferSize == 1)
            this.buffer = new SynchronousQueue<E>();
        else
            this.buffer = new ArrayBlockingQueue<E>(bufferSize - 1);

        this.executor = executor;
        this.bufferWriterTask = new Runnable() {
            @Override
            public void run() {
                E onDeck;
                try {
                    if (source.hasNext()) {
                        E next = source.next();
                        if (next == null) {
                            onDeck = (E) NULL_MARKER;
                        } else {
                            onDeck = next;
                        }
                    } else {
                        onDeck = (E) END_MARKER;
                    }
                } catch (Throwable t) {
                    // Either from source.hasNext or source.next, we handle both the same
                    failedMarker = t;
                    onDeck = (E) failedMarker;
                }

                try {
                    boolean put = false;
                    while (!put) {
                        if (aborted)
                            return;
                        put = buffer.offer(onDeck, ABORT_CHECK_FREQ_MS, TimeUnit.MILLISECONDS);
                    }

                    if (onDeck == END_MARKER || onDeck == failedMarker)
                        return;

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    aborted = true;
                    return;
                }

                try {
                    executor.submit(this);
                } catch (RejectedExecutionException e) {
                    aborted = true;
                }
            }
        };
    }

    /**
     * You needn't call this because buffering will auto-start when this iterator is first used. This method exists for
     * users who wish to start buffering the source iterator before that point.
     * <p/>
     * This method is idempotent.
     */
    public void prestartBuffering() {
        if (!started) {
            executor.submit(bufferWriterTask);
            started = true;
        }
    }

    @Override
    protected E computeNext() {
        if (!started) {
            prestartBuffering();
        }

        E next = null;
        try {
            while (next == null) {
                if (aborted)
                    return endOfData();
                next = buffer.poll(ABORT_CHECK_FREQ_MS, TimeUnit.MILLISECONDS);
            }

            if (next == NULL_MARKER)
                return null;
            else if (next == END_MARKER)
                return endOfData();
            else if (next == failedMarker)
                throw Throwables.propagate(failedMarker);
            else
                return next;
        } catch (InterruptedException e) {
            aborted = true;
            Thread.currentThread().interrupt();
            return endOfData();
        }
    }
}
