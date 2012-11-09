package com.brianfromoregon.lang;

import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.collect.*;
import org.junit.Test;

import java.util.Date;
import java.util.Iterator;
import java.util.List;

import static junit.framework.Assert.*;

public class ExpiringIteratorTest {

    @Test public void expireOnNext() {
        Iterator<Supplier<Integer>> it = ExpiringIterator.expireOnNext(Iterators.forArray(1, 2, 3));
        assertEquals(Integer.valueOf(1), it.next().get());
        Supplier<Integer> two = it.next();
        assertEquals(Integer.valueOf(2), two.get());
        assertTrue(it.hasNext());
        assertEquals(Integer.valueOf(2), two.get());
        it.next();
        try {
            two.get();
            fail();
        } catch (ExpiringIterator.ElementExpiredException e) {
        }
    }

    @Test public void expireOnHasNext() {
        Iterator<Supplier<Integer>> it = ExpiringIterator.expireOnHasNext(Iterators.forArray(1, 2, 3));
        assertEquals(Integer.valueOf(1), it.next().get());
        Supplier<Integer> two = it.next();
        assertEquals(Integer.valueOf(2), two.get());
        assertTrue(it.hasNext());
        try {
            two.get();
            fail();
        } catch (ExpiringIterator.ElementExpiredException e) {
        }
    }

    @Test public void bufferReuse() {
        assertEquals(TestData, Lists.newArrayList(Iterators.transform(bufferReuseIterator(), new Function<Supplier<char[]>, String>() {
            @Override public String apply(Supplier<char[]> input) {
                return new String(input.get());
            }
        })));

        Iterator<Supplier<char[]>> it = bufferReuseIterator();
        for (int i = 0; it.hasNext(); i++) {
            String expected = TestData.get(i);
            String actual = new String(it.next().get());
            assertEquals(expected, actual);
        }

        List<Supplier<char[]>> refs = Lists.newArrayList(bufferReuseIterator());
        for (int i = 0; i < refs.size() - 1; i++) {
            try {
                refs.get(i).get();
                fail();
            } catch (ExpiringIterator.ElementExpiredException e) {
            }
        }
        assertEquals(TestData.get(TestData.size() - 1), new String(refs.get(refs.size() - 1).get()));
    }

    @Test public void objectPool() {
        for (int poolSize = 1; poolSize < 8; poolSize++) {
            List<Supplier<Date>> refs = Lists.newArrayList(datePoolIterator(poolSize, 6));
            int idx;
            for (idx = 0; idx < refs.size() - poolSize; idx++) {
                try {
                    refs.get(idx).get();
                    fail();
                } catch (ExpiringIterator.ElementExpiredException e) {
                }
            }
            for (; idx < refs.size(); idx++) {
                assertEquals(new Date(idx), refs.get(idx).get());
            }
        }
    }

    static final ImmutableList<String> TestData = ImmutableList.of("a", "bc", "de", "fgh", "ijk");

    private static Iterator<Supplier<char[]>> bufferReuseIterator() {
        return new ExpiringIterator<char[]>() {
            @Override protected Iterator<char[]> getUnderlying() {
                final Iterator<String> data = TestData.iterator();
                return new AbstractIterator<char[]>() {
                    char[] previous;

                    @Override protected char[] computeNext() {
                        if (!data.hasNext())
                            return endOfData();

                        String next = data.next();
                        char[] buf;
                        if (previous != null && next.length() == previous.length) {
                            buf = previous;
                            expire();
                        } else {
                            buf = new char[next.length()];
                            previous = buf;
                        }
                        next.getChars(0, next.length(), buf, 0);
                        return buf;
                    }
                };
            }
        };
    }

    private static Iterator<Supplier<Date>> datePoolIterator(final int poolSize, final int numElements) {
        final List<Date> pool = Lists.newArrayList();
        for (int i = 0; i < poolSize; i++)
            pool.add(new Date());
        return new ExpiringIterator<Date>() {
            @Override protected Iterator<Date> getUnderlying() {
                final Iterator<Integer> data = Ranges.closedOpen(0, numElements).asSet(DiscreteDomains.integers()).asList().iterator();
                return new AbstractIterator<Date>() {
                    Iterator<Date> poolCycler = Iterators.cycle(pool);

                    @Override protected Date computeNext() {
                        if (!data.hasNext())
                            return endOfData();

                        Date obj = poolCycler.next();
                        obj.setTime(data.next());
                        expire(poolSize - 1);
                        return obj;
                    }
                };
            }
        };
    }
}
