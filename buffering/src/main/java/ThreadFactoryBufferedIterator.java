import com.google.common.collect.AbstractIterator;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.Iterator;
import java.util.concurrent.*;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * An {@link Iterator} which eagerly fetches and buffers <i>N</i> elements from a source Iterator.
 * <p/>
 * This class supports source iterators with null elements.
 * <p/>
 * If the source Iterator's {@code hasNext} or {@code next} methods throw an Exception, that Exception will be queued in
 * the buffer and eventually thrown by {@link #hasNext()} or {@link #next()}. Any further attempts to use this iterator
 * will result in an {@link IllegalStateException}.
 * <p/>
 * If the {@link Thread} calling {@link #hasNext()} or {@link #next()} is seen to be interrupted, the interrupted status
 * will be retained, no more elements will be returned, and {@link #dispose()} will be called. If the internal buffer
 * thread is interrupted, the effect will be equivalent to {@link #dispose()} being called.
 */
public class ThreadFactoryBufferedIterator<E> extends AbstractIterator<E> {
    private final BlockingQueue<E> buffer;
    private final Thread bufferWriterThread;
    private boolean started = false;

    private static final Object END_MARKER = new Object();
    private static final Object NULL_MARKER = new Object();
    private volatile RuntimeException exceptionMarker = null;


    /**
     * Calls {@link #ThreadFactoryBufferedIterator(int, java.util.Iterator, java.util.concurrent.ThreadFactory)} with a
     * {@link ThreadFactory} that creates daemon threads.
     *
     * @param bufferSize The maximum number of elements to eagerly fetch from the source iterator on a background
     *                   thread. Note: one additional element will be buffered between calling {@link #hasNext()} and
     *                   {@link #next()}.
     * @param source     The source iterator.
     */
    public ThreadFactoryBufferedIterator(int bufferSize, final Iterator<E> source) {
        this(bufferSize, source, new ThreadFactoryBuilder().setDaemon(true).build());
    }

    /**
     * Create a new BufferedIterator. The buffer thread will be started when {@link #hasNext()} or {@link #next()} are
     * first called, or when {@link #startBufferThread()} is called.
     *
     * @param bufferSize    The maximum number of elements to eagerly fetch from the source iterator on a background
     *                      thread. Note: one additional element will be buffered between calling {@link #hasNext()} and
     *                      {@link #next()}.
     * @param source        The source iterator.
     * @param threadFactory A single thread from {@code threadFactory} will be created and used by this class. An {@link
     *                      Error} thrown by the source iterator will percolate to the {@link Thread} created by this
     *                      factory.
     */
    @SuppressWarnings("unchecked")
    public ThreadFactoryBufferedIterator(int bufferSize, final Iterator<E> source, ThreadFactory threadFactory) {
        checkArgument(bufferSize > 0, "bufferSize > 0");
        if (bufferSize == 1)
            this.buffer = new SynchronousQueue<E>();
        else
            this.buffer = new ArrayBlockingQueue<E>(bufferSize - 1);

        Runnable bufferWriter = new Runnable() {
            @Override
            public void run() {
                while (true) {
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
                    } catch (RuntimeException e) {
                        // Either from source.hasNext or source.next, we handle both the same
                        exceptionMarker = e;
                        onDeck = (E) exceptionMarker;
                    }

                    try {
                        buffer.put(onDeck);
                        if (onDeck == END_MARKER || onDeck == exceptionMarker)
                            return;

                    } catch (InterruptedException e) {
                        try {
                            buffer.clear();
                            buffer.offer((E) END_MARKER, 1000, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                        }

                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        };

        bufferWriterThread = threadFactory.newThread(bufferWriter);
        bufferWriterThread.setName("BufferedIterator buffer writer");
    }

    /**
     * You needn't call this because buffering will auto-start when this iterator is first used. This method exists for
     * users who wish to force the internal thread to start buffering the source iterator before that point.
     * <p/>
     * This method is idempotent.
     */
    public void startBufferThread() {
        if (!started) {
            bufferWriterThread.start();
            started = true;
        }
    }

    /**
     * Calling this method is normally unnecessary because the buffer thread will stop when the source iterator is
     * empty. This method exists for users who wish to stop the buffer thread and empty the buffer prematurely.
     */
    public void dispose() {
        bufferWriterThread.interrupt();
    }

    @Override
    protected E computeNext() {
        if (!started) {
            startBufferThread();
        }

        E next;
        try {
            next = buffer.take();
            if (next == NULL_MARKER)
                return null;
            else if (next == END_MARKER)
                return endOfData();
            else if (next == exceptionMarker)
                throw exceptionMarker;
            else
                return next;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dispose();
            return endOfData();
        }
    }
}
