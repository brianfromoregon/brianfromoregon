import com.google.common.base.Throwables;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.junit.Test;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;

import static org.junit.Assert.*;

public class ThreadFactoryBufferedIteratorTest {


    @Test(expected = IllegalArgumentException.class)
    public void size_zero_buffer() {
        drainTest(100, 0);
    }

    @Test
    public void size_one_buffer() {
        drainTest(100, 1);
    }

    @Test
    public void size_two_buffer() {
        drainTest(100, 2);
    }

    @Test
    public void buffer_bigger_than_source() {
        drainTest(2, 10);
    }

    @Test
    public void null_element() {
        List<Integer> source = Lists.newArrayList(1, null, 3);

        assertEquals(source, Lists.newArrayList(new ThreadFactoryBufferedIterator<Integer>(2, source.iterator())));
    }

    @Test(expected = NullPointerException.class)
    public void source_throws_exception_first() {
        new ThreadFactoryBufferedIterator<Integer>(3, new ThrowingIterator<Integer>(0, new IncIterator(1), new NullPointerException())).next();
    }

    @Test
    public void source_throws_exception_later() {
        IncIterator inc = new IncIterator(20);
        int idx = 5;
        ThreadFactoryBufferedIterator<Integer> instance = new ThreadFactoryBufferedIterator<Integer>(2, new ThrowingIterator<Integer>(idx, inc, new NullPointerException()));
        for (int i = 0; i < idx; i++)
            assertEquals("Incorrect order", i + 1, (int) instance.next());

        try {
            instance.next();
            fail("Didn't throw");
        } catch (NullPointerException e) {
        }

        // Verify it stopped buffering
        Thread.yield();
        assertEquals(idx + 2, inc.next);
    }

    @Test
    public void source_throws_error() throws Exception {
        final SynchronousQueue<Throwable> q = new SynchronousQueue<Throwable>();
        Thread.UncaughtExceptionHandler handler = new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    q.put(e);
                } catch (InterruptedException e1) {
                }
            }
        };
        new ThreadFactoryBufferedIterator<Integer>(3, new ThrowingIterator<Integer>(0, new IncIterator(1), new IllegalAccessError()), new ThreadFactoryBuilder().setUncaughtExceptionHandler(handler).build()).startBufferThread();
        assertEquals(IllegalAccessError.class, q.take().getClass());
    }

    static class SimpleThreadFactory implements ThreadFactory {
        public Thread thread;

        @Override
        public Thread newThread(Runnable r) {
            thread = new Thread(r);
            return thread;
        }
    }

    void drainTest(int sourceCount, int bufSize) {
        IncIterator inc = new IncIterator(sourceCount);
        Iterator<Integer> instance = new ThreadFactoryBufferedIterator<Integer>(bufSize, inc);

        int next;
        int count = 0;
        while (instance.hasNext()) {
            Thread.yield();
            int sourceNext = inc.next;
            next = instance.next();
            int buffered = sourceNext - next - 1;
            int maxBuffered = bufSize;
            assertTrue(String.format("Too many buffered, expected max %d but was %d", maxBuffered, buffered), buffered <= maxBuffered);
            assertEquals("Not in order", ++count, next);
        }

        assertEquals("Incorrect count", sourceCount, count);
    }

    // Iterates from 1 to n
    static class IncIterator extends AbstractIterator<Integer> {
        final int n;
        public volatile int next = 1;

        IncIterator(int n) {
            this.n = n;
        }

        @Override
        protected Integer computeNext() {
            if (next <= n)
                return next++;

            return endOfData();
        }
    }

    static class ThrowingIterator<T> implements Iterator<T> {
        final int throwIndex;
        final Iterator<T> source;
        final Throwable toThrow;
        int index = 0;

        ThrowingIterator(int throwIndex, Iterator<T> source, Throwable toThrow) {
            this.throwIndex = throwIndex;
            this.source = source;
            this.toThrow = toThrow;
        }

        @Override
        public boolean hasNext() {
            return source.hasNext();
        }

        @Override
        public T next() {
            if (index++ == throwIndex)
                Throwables.propagate(toThrow);
            return source.next();
        }

        @Override
        public void remove() {
            source.remove();
        }
    }

//    class MyThreadFactory implements ThreadFactory {
//        Thread thread;
//
//        @Override
//        public Thread newThread(Runnable r) {
//            return thread = new Thread(r);
//        }
//    }
}
