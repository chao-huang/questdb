package com.questdb.cairo.pool;

import com.questdb.cairo.*;
import com.questdb.cairo.pool.ex.EntryLockedException;
import com.questdb.cairo.pool.ex.EntryUnavailableException;
import com.questdb.cairo.pool.ex.PoolClosedException;
import com.questdb.factory.configuration.JournalStructure;
import com.questdb.misc.Chars;
import com.questdb.std.str.LPSZ;
import com.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class WriterPoolTest extends AbstractCairoTest {

    private static final DefaultCairoConfiguration CONFIGURATION = new DefaultCairoConfiguration(root);

    @Before
    public void setUpInstance() throws Exception {
        createTable();
    }

    @Test
    public void testAllocateAndClear() throws Exception {
        assertWithPool(pool -> {
            int n = 2;
            final CyclicBarrier barrier = new CyclicBarrier(n);
            final CountDownLatch halt = new CountDownLatch(n);
            final AtomicInteger errors = new AtomicInteger();
            final AtomicInteger writerCount = new AtomicInteger();
            new Thread(() -> {
                try {
                    for (int i = 0; i < 1000; i++) {
                        try (TableWriter ignored = pool.writer("z")) {
                            writerCount.incrementAndGet();
                        } catch (EntryUnavailableException ignored) {
                        }

                        if (i == 1) {
                            barrier.await();
                        } else {
                            LockSupport.parkNanos(1L);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    errors.incrementAndGet();
                } finally {
                    halt.countDown();
                    TableUtils.freeThreadLocals();
                }
            }).start();

            new Thread(() -> {
                try {
                    barrier.await();

                    for (int i = 0; i < 1000; i++) {
                        pool.releaseInactive();
                        LockSupport.parkNanos(1L);
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                    errors.incrementAndGet();
                } finally {
                    halt.countDown();
                    TableUtils.freeThreadLocals();
                }
            }).start();

            halt.await();

            Assert.assertTrue(writerCount.get() > 0);
            Assert.assertEquals(0, errors.get());
        });
    }

    @Test
    public void testClosedPoolLock() throws Exception {
        assertWithPool(pool -> {
            class X implements PoolListener {
                short ev = -1;

                @Override
                public boolean onEvent(byte factoryType, long thread, CharSequence name, short event, short segment, short position) {
                    this.ev = event;
                    return false;
                }
            }
            X x = new X();
            pool.setPoolListner(x);
            pool.close();
            try {
                pool.lock("x");
                Assert.fail();
            } catch (PoolClosedException ignored) {
            }
            Assert.assertEquals(PoolListener.EV_POOL_CLOSED, x.ev);
        });
    }

    @Test
    public void testFactoryCloseBeforeRelease() throws Exception {
        assertWithPool(pool -> {
            TableWriter x;

            x = pool.writer("z");
            try {
                Assert.assertEquals(0, pool.countFreeWriters());
                Assert.assertNotNull(x);
                Assert.assertTrue(x.isOpen());
                Assert.assertTrue(x == pool.writer("z"));
                pool.close();
            } finally {
                x.close();
            }

            Assert.assertFalse(x.isOpen());
            try {
                pool.writer("z");
                Assert.fail();
            } catch (PoolClosedException ignored) {
            }
        });
    }

    @Test
    public void testLockNonExisting() throws Exception {
        assertWithPool(pool -> {
            pool.lock("z");

            try {
                pool.writer("z");
                Assert.fail();
            } catch (EntryLockedException ignored) {
            }

            pool.unlock("z");

            try (TableWriter wx = pool.writer("z")) {
                Assert.assertNotNull(wx);
            }
        });
    }

    @Test
    public void testLockUnlock() throws Exception {

        TableUtils.create(FilesFacadeImpl.INSTANCE, root, new JournalStructure("x").$date("ts").$().build(), 509);
        TableUtils.create(FilesFacadeImpl.INSTANCE, root, new JournalStructure("y").$date("ts").$().build(), 509);

        assertWithPool(pool -> {
            TableWriter wx = pool.writer("x");
            Assert.assertNotNull(wx);
            Assert.assertTrue(wx.isOpen());

            TableWriter wy = pool.writer("y");
            Assert.assertNotNull(wy);
            Assert.assertTrue(wy.isOpen());

            try {

                // check that lock is successful
                pool.lock("x");

                // check that writer x is closed and writer y is open (lock must not spill out to other writers)
                Assert.assertFalse(wx.isOpen());
                Assert.assertTrue(wy.isOpen());

                // check that when name is locked writers are not created
                try {
                    pool.writer("x");
                    Assert.fail();
                } catch (EntryLockedException ignored) {

                }

                final CountDownLatch done = new CountDownLatch(1);
                final AtomicBoolean result = new AtomicBoolean();

                // have new thread try to allocated this writers
                new Thread(() -> {
                    try (TableWriter ignored = pool.writer("x")) {
                        result.set(false);
                    } catch (EntryUnavailableException ignored) {
                        result.set(true);
                    } catch (CairoException e) {
                        e.printStackTrace();
                        result.set(false);
                    }
                    done.countDown();
                    TableUtils.freeThreadLocals();
                }).start();

                Assert.assertTrue(done.await(1, TimeUnit.SECONDS));
                Assert.assertTrue(result.get());

                pool.unlock("x");

                wx = pool.writer("x");
                Assert.assertNotNull(wx);
                Assert.assertTrue(wx.isOpen());

                try {
                    // unlocking writer that has not been locked must produce exception
                    // and not affect open writer
                    pool.unlock("x");
                    Assert.fail();
                } catch (IllegalStateException ignored) {
                }

                Assert.assertTrue(wx.isOpen());

            } finally {
                wx.close();
                wy.close();
            }
        });
    }

    @Test
    public void testNewLock() throws Exception {
        assertWithPool(pool -> {
            pool.lock("z");
            try {
                pool.writer("z");
                Assert.fail();
            } catch (EntryLockedException ignored) {
            }
            pool.unlock("z");
        });
    }

    @Test
    public void testOneThreadGetRelease() throws Exception {

        assertWithPool(pool -> {
            TableWriter x;
            TableWriter y;

            x = pool.writer("z");
            try {
                Assert.assertEquals(0, pool.countFreeWriters());
                Assert.assertNotNull(x);
                Assert.assertTrue(x.isOpen());
                Assert.assertTrue(x == pool.writer("z"));
            } finally {
                x.close();
            }

            Assert.assertEquals(1, pool.countFreeWriters());

            y = pool.writer("z");
            try {
                Assert.assertNotNull(y);
                Assert.assertTrue(y.isOpen());
                Assert.assertTrue(y == x);
            } finally {
                y.close();
            }

            Assert.assertEquals(1, pool.countFreeWriters());
        });
    }

    @Test
    public void testTwoThreadsRaceToAllocate() throws Exception {
        assertWithPool(pool -> {
            for (int k = 0; k < 1000; k++) {
                int n = 2;
                final CyclicBarrier barrier = new CyclicBarrier(n);
                final CountDownLatch halt = new CountDownLatch(n);
                final AtomicInteger errors = new AtomicInteger();
                final AtomicInteger writerCount = new AtomicInteger();

                for (int i = 0; i < n; i++) {
                    new Thread(() -> {
                        try {
                            barrier.await();
                            try (TableWriter w = pool.writer("z")) {
                                writerCount.incrementAndGet();
                                populate(w);
                            } catch (EntryUnavailableException ignored) {
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            errors.incrementAndGet();
                        } finally {
                            halt.countDown();
                            TableUtils.freeThreadLocals();
                        }
                    }).start();
                }

                halt.await();

                // this check is unreliable on slow build servers
                // it is very often the case that there are limited number of cores
                // available and threads execute sequentially rather than
                // simultaneously. We should check that none of the threads
                // receive error.
                Assert.assertTrue(writerCount.get() > 0);
                Assert.assertEquals(0, errors.get());
                Assert.assertEquals(1, pool.countFreeWriters());
            }
        });
    }

    @Test
    public void testTwoThreadsRaceToAllocateAndLock() throws Exception {
        assertWithPool(pool -> {
            for (int k = 0; k < 1000; k++) {
                int n = 2;
                final CyclicBarrier barrier = new CyclicBarrier(n);
                final CountDownLatch halt = new CountDownLatch(n);
                final AtomicInteger errors = new AtomicInteger();
                final AtomicInteger writerCount = new AtomicInteger();

                for (int i = 0; i < n; i++) {
                    new Thread(() -> {
                        try {
                            barrier.await();
                            try (TableWriter w = pool.writer("z")) {
                                writerCount.incrementAndGet();
                                populate(w);

                                Assert.assertTrue(w == pool.writer("z"));

                                // lock frees up writer, make sure on next iteration threads have something to compete for
                                pool.lock("z");
                                pool.unlock("z");
                            } catch (EntryUnavailableException ignored) {
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            errors.incrementAndGet();
                        } finally {
                            halt.countDown();
                            TableUtils.freeThreadLocals();
                        }
                    }).start();
                }

                halt.await();

                // this check is unreliable on slow build servers
                // it is very often the case that there are limited number of cores
                // available and threads execute sequentially rather than
                // simultaneously. We should check that none of the threads
                // receive error.
                Assert.assertTrue(writerCount.get() > 0);
                Assert.assertEquals(0, errors.get());
                Assert.assertEquals(0, pool.countFreeWriters());
            }
        });
    }

    @Test
    public void testTwoThreadsRaceToLock() throws Exception {
        assertWithPool(pool -> {
            for (int k = 0; k < 1000; k++) {
                int n = 2;
                final CyclicBarrier barrier = new CyclicBarrier(n);
                final CountDownLatch halt = new CountDownLatch(n);
                final AtomicInteger errors = new AtomicInteger();
                final AtomicInteger writerCount = new AtomicInteger();

                for (int i = 0; i < n; i++) {
                    new Thread(() -> {
                        try {
                            barrier.await();
                            try {
                                pool.lock("z");
                                LockSupport.parkNanos(1);
                                pool.unlock("z");
                            } catch (EntryUnavailableException ignored) {
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            errors.incrementAndGet();
                        } finally {
                            halt.countDown();
                            TableUtils.freeThreadLocals();
                        }
                    }).start();
                }

                halt.await();

                // this check is unreliable on slow build servers
                // it is very often the case that there are limited number of cores
                // available and threads execute sequentially rather than
                // simultaneously. We should check that none of the threads
                // receive error.
                Assert.assertTrue(writerCount.get() == 0);
                Assert.assertEquals(0, errors.get());
                Assert.assertEquals(0, pool.countFreeWriters());
            }
        });
    }

    @Test
    public void testUnlockNonExisting() throws Exception {
        assertWithPool(pool -> {
            class X implements PoolListener {
                short ev = -1;

                @Override
                public boolean onEvent(byte factoryType, long thread, CharSequence name, short event, short segment, short position) {
                    this.ev = event;
                    return false;
                }
            }
            X x = new X();
            pool.setPoolListner(x);
            pool.unlock("x");
            Assert.assertEquals(PoolListener.EV_NOT_LOCKED, x.ev);
        });
    }

    @Test
    public void testWriterDoubleClose() throws Exception {
        assertWithPool(pool -> {
            class X implements PoolListener {
                short ev = -1;

                @Override
                public boolean onEvent(byte factoryType, long thread, CharSequence name, short event, short segment, short position) {
                    this.ev = event;
                    return false;
                }
            }
            X x = new X();
            pool.setPoolListner(x);
            TableWriter w = pool.writer("z");
            Assert.assertNotNull(w);
            Assert.assertEquals(1, pool.getBusyCount());
            w.close();
            Assert.assertEquals(PoolListener.EV_RETURN, x.ev);
            Assert.assertEquals(0, pool.getBusyCount());

            w.close();
            Assert.assertEquals(PoolListener.EV_UNEXPECTED_CLOSE, x.ev);
            Assert.assertEquals(0, pool.getBusyCount());
        });
    }

    @Test
    public void testWriterOpenFailOnce() throws Exception {

        final TestFilesFacade ff = new TestFilesFacade() {
            int count = 1;

            @Override
            public long openRW(LPSZ name) {
                if (Chars.endsWith(name, "z.lock") && count-- > 0) {
                    return -1;
                }
                return super.openRW(name);
            }

            @Override
            public boolean wasCalled() {
                return count <= 0;
            }
        };

        DefaultCairoConfiguration configuration = new DefaultCairoConfiguration(root) {
            @Override
            public FilesFacade getFilesFacade() {
                return ff;
            }
        };

        assertWithPool(pool -> {
            try {
                pool.writer("z");
                Assert.fail();
            } catch (CairoException ignore) {
            }

            // writer has to fail again if called before
            // release() invocation
            try {
                pool.writer("z");
                Assert.fail();
            } catch (CairoException ignore) {
            }

            Assert.assertEquals(1, pool.size());
            Assert.assertEquals(1, pool.getBusyCount());

            pool.releaseInactive();
            Assert.assertEquals(0, pool.size());

            // try again
            TableWriter w = pool.writer("z");
            Assert.assertEquals(1, pool.getBusyCount());
            w.close();
        }, configuration);

        Assert.assertTrue(ff.wasCalled());

    }

    private void assertWithPool(PoolAwareCode code, CairoConfiguration configuration) throws Exception {
        TableUtils.freeThreadLocals();
        TestUtils.assertMemoryLeak(() -> {
            try (WriterPool pool = new WriterPool(configuration)) {
                code.run(pool);
                TableUtils.freeThreadLocals();
            }
        });
    }

    private void assertWithPool(PoolAwareCode code) throws Exception {
        assertWithPool(code, CONFIGURATION);
    }

    private void createTable() {
        TableUtils.create(FilesFacadeImpl.INSTANCE, root, new JournalStructure("z").$date("ts").$().build(), 509);
    }

    private void populate(TableWriter w) {
        long start = w.getMaxTimestamp();
        for (int i = 0; i < 1000; i++) {
            w.newRow(start + i).append();
            w.commit();
        }
    }

    private interface PoolAwareCode {
        void run(WriterPool pool) throws Exception;
    }
}