package com.brianfromoregon.lang;

import com.google.common.base.Supplier;
import com.google.common.collect.ForwardingIterator;
import com.google.common.collect.UnmodifiableIterator;

import java.util.Iterator;

/**
 * This is used as an attempt to detect misuse of Iterators which modify/reuse the same instances for their returned
 * elements. Because elements are eventually mutated, it's a problem if a user keeps a reference to one of them
 * expecting that it will represent some previous state when actually it's been mutated to represent some newer state.
 * <p/>
 * Elements from another {@link Iterator} are wrapped in a {@link Supplier} which, on a call
 * to {@link Supplier#get()}, will first verify that the element is not expired before returning it. If the element is
 * expired when {@link Supplier#get()} is called, a {@link ElementExpiredException} is thrown.
 * <p/>
 * <em>When</em> an element becomes expired is controlled by the subclass of {@link ExpiringIterator} via calls to
 * {@link #expire()} and {@link #expire(int)}
 * <p/>
 * This class does not prevent misuse, it just provides a facility for detecting it. The user is still able to misuse
 * the underlying iterator by eagerly calling {@link Supplier#get()} and then holding a reference to (and eventually reading) the returned element
 * after it's been expired. Don't do that, it defeats the purpose! The proper way to use this class is to only ever hold a reference to the Supplier
 * rather than the element inside the Supplier. Only just before you need to read the element should you access it with
 * {@link Supplier#get()}. Once accessed, do not keep a reference to the element! If you need to access it again later,
 * call {@link Supplier#get()} again in the same fashion.
 * <p/>
 * Stems from <a href="http://stackoverflow.com/questions/11893479/an-iterator-which-mutates-and-returns-the-same-object-bad-practice">this SO question</a>
 */
public abstract class ExpiringIterator<T> extends UnmodifiableIterator<Supplier<T>> {

    /**
     * An exception indicating that an element was accessed which was already reclaimed for reuse by the iterator. The
     * fix is to access the element value sooner after it's retrieved from next().
     */
    public static class ElementExpiredException extends RuntimeException {
    }

    /**
     * Convenience function to expire all returned elements whenever next is called.
     */
    public static <T> Iterator<Supplier<T>> expireOnNext(final Iterator<T> source) {
        return new ExpiringIterator<T>() {
            @Override protected Iterator<T> getUnderlying() {
                return new ForwardingIterator<T>() {
                    @Override protected Iterator<T> delegate() {
                        return source;
                    }

                    @Override public T next() {
                        expire();
                        return super.next();
                    }
                };
            }
        };
    }

    /**
     * Convenience function to expire all returned elements whenever hasNext is called.
     */
    public static <T> Iterator<Supplier<T>> expireOnHasNext(final Iterator<T> source) {
        return new ExpiringIterator<T>() {
            @Override protected Iterator<T> getUnderlying() {
                return new ForwardingIterator<T>() {
                    @Override protected Iterator<T> delegate() {
                        return source;
                    }

                    @Override public boolean hasNext() {
                        expire();
                        return super.hasNext();
                    }
                };
            }
        };
    }

    private Iterator<T> source;

    /**
     * Returns the actual elements to be wrapped in Suppliers. Calls to next/hasNext on the returned object should call
     * the expire overloads to control expiration.
     * <p/>
     * Will be called once.
     */
    protected abstract Iterator<T> getUnderlying();

    private int expired = -1;
    private int lastId = -1;

    /**
     * Expires all elements previously returned from this.next(). Equivalent to {@code expire(0)}.
     * <p/>
     * To be called from the source iterator's hasNext or next.
     */
    protected final void expire() {
        expire(0);
    }

    /**
     * Expires all but the past {@code n} elements previously returned from this.next(). For example {@code expire(2)}
     * could be used if you return objects from a cycling pool of size 3.
     * <p/>
     * To be called from the source iterator's hasNext() or next().
     */
    protected final void expire(int n) {
        expired = lastId - n;
    }

    @Override public Supplier<T> next() {
        if (source == null)
            source = getUnderlying();

        T next = source.next();
        return new ExpiringRef<T>(++lastId, next);
    }

    @Override public boolean hasNext() {
        if (source == null)
            source = getUnderlying();

        return source.hasNext();
    }

    private class ExpiringRef<T> implements Supplier<T> {
        final int id;
        final T ref;

        private ExpiringRef(int id, T ref) {
            this.id = id;
            this.ref = ref;
        }

        @Override public T get() {
            if (isExpired())
                throw new ElementExpiredException();

            return ref;
        }

        @Override public String toString() {
            return String.format("%s{expired=%s, value=%s}", getClass().getSimpleName(), isExpired(), ref);
        }

        private boolean isExpired() {
            return expired >= id;
        }
    }
}
