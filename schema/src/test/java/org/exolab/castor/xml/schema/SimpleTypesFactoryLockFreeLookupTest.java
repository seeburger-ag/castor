/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.exolab.castor.xml.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.beans.BeanInfo;
import java.beans.IndexedPropertyDescriptor;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;

import org.exolab.castor.xml.schema.simpletypes.factory.Type;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Verifies improvement <b>F1</b>: {@link SimpleTypesFactory#getBuiltInTypeName(int)} is served from
 * a lock-free lookup table instead of the process-wide {@code synchronized}
 * {@link java.util.Hashtable} {@code _typesByCode}.
 * <p>
 * <b>Why this matters.</b> {@code String Schema.getBuiltInTypeName(int)} accidentally matches the
 * JavaBeans <em>indexed getter</em> pattern, and there is no companion {@code String[]
 * getBuiltInTypeName()}. Bean-introspecting XPath engines (Apache Commons JXPath, for example)
 * therefore have to <em>guess</em> the property length by calling the indexed getter repeatedly
 * until it throws - up to {@code UNKNOWN_LENGTH_MAX_COUNT == 16000} times. Castor's getter never
 * throws (it returns {@code null} for unknown codes), so the full 16000 iterations run, each one
 * acquiring the same global monitor. With several worker threads this degenerates into a lock
 * convoy in which every thread is {@code BLOCKED} on {@code Hashtable.get}.
 * <p>
 * The tests below
 * <ol>
 * <li>pin down the JavaBeans mis-detection that creates the hot loop in the first place,</li>
 * <li>prove that the lock-free table is <em>behaviourally identical</em> to the map lookup, and</li>
 * <li>show the actual improvement: replaying the 16000-call probe from several threads no longer
 * produces <em>any</em> monitor contention, and is dramatically faster.</li>
 * </ol>
 */
public class SimpleTypesFactoryLockFreeLookupTest {

  /** Mirrors {@code org.apache.commons.jxpath.util.ValueUtils.UNKNOWN_LENGTH_MAX_COUNT}. */
  private static final int JXPATH_PROBE_LENGTH = 16000;

  /** Number of concurrent workers replaying the probe. */
  private static final int THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());

  /** Probe repetitions per worker and measurement run. */
  private static final int ROUNDS = 10;

  /** Number of measurement runs; the fastest one counts. */
  private static final int MEASUREMENTS = 3;

  private static SimpleTypesFactory factory;

  /** The pre-F1 implementation: boxed key + {@code synchronized} {@link java.util.Hashtable}. */
  private static final NameLookup LEGACY_HASHTABLE_LOOKUP = new NameLookup() {
    public String name(final int code) {
      Type type = factory.getType(code);
      if (type == null) {
        return null;
      }
      return type.getName();
    }
  };

  /** The F1 implementation: bounds check + array read, no locking, no allocation. */
  private static final NameLookup LOCK_FREE_LOOKUP = new NameLookup() {
    public String name(final int code) {
      return factory.getBuiltInTypeName(code);
    }
  };

  @BeforeClass
  public static void createFactory() {
    // -- {@link Schema} and {@link SimpleTypesFactory} initialise each other; Schema has to win
    // -- that race, because its static initialiser creates the SimpleTypesFactory instance that
    // -- the whole JVM shares (and that instance needs Schema's BUILD_IN_SCHEMA to exist).
    new Schema();
    factory = Schema.getTypeFactory();
    assertNotNull("Schema must expose its shared SimpleTypesFactory", factory);
  }

  // ------------------------------------------------------------------------------------------
  // 1. Root cause: why the 16000-call probe exists at all.
  // ------------------------------------------------------------------------------------------

  /**
   * {@code Schema.getBuiltInTypeName(int)} is seen by {@link Introspector} as an indexed property
   * <em>without</em> a non-indexed read method, and the getter signals "unknown code" by returning
   * {@code null} rather than by throwing. Both facts together are what forces a bean-introspecting
   * XPath engine into the full 16000-iteration probe for every visited {@link Schema} node.
   */
  @Test
  public void schemaExposesBuiltInTypeNameAsUnboundedIndexedProperty() throws Exception {
    IndexedPropertyDescriptor descriptor = findIndexedProperty(Schema.class, "builtInTypeName");

    assertNotNull("Schema.getBuiltInTypeName(int) should be introspected as an indexed property",
        descriptor);
    assertNotNull("indexed read method expected", descriptor.getIndexedReadMethod());
    assertNull("no array getter exists, so the JXPath fast path is unavailable",
        descriptor.getReadMethod());

    // -- the getter never throws, so a "probe until it fails" loop can never terminate early.
    Schema schema = new Schema();
    assertNull(schema.getBuiltInTypeName(JXPATH_PROBE_LENGTH - 1));
    assertNull(schema.getBuiltInTypeName(JXPATH_PROBE_LENGTH));
    assertNull(schema.getBuiltInTypeName(Integer.MAX_VALUE));
  }

  // ------------------------------------------------------------------------------------------
  // 2. Correctness: the lock-free table must be indistinguishable from the map lookup.
  // ------------------------------------------------------------------------------------------

  /** The array lookup must return exactly what the {@code Hashtable} lookup returned. */
  @Test
  public void lockFreeLookupIsEquivalentToHashtableLookup() {
    for (int code = -1024; code <= JXPATH_PROBE_LENGTH; code++) {
      assertEquals("mismatch for type code " + code, LEGACY_HASHTABLE_LOOKUP.name(code),
          LOCK_FREE_LOOKUP.name(code));
    }
  }

  /** Spot checks for well-known codes, plus the non-built-in and out-of-range codes. */
  @Test
  public void lockFreeLookupResolvesKnownTypeCodes() {
    assertEquals("string", factory.getBuiltInTypeName(SimpleTypesFactory.STRING_TYPE));
    assertEquals("integer", factory.getBuiltInTypeName(SimpleTypesFactory.INTEGER_TYPE));
    assertEquals("boolean", factory.getBuiltInTypeName(SimpleTypesFactory.BOOLEAN_TYPE));
    assertEquals("anySimpleType",
        factory.getBuiltInTypeName(SimpleTypesFactory.ANYSIMPLETYPE_TYPE));

    assertNull("USER_TYPE is not a built-in type",
        factory.getBuiltInTypeName(SimpleTypesFactory.USER_TYPE));
    assertNull("INVALID_TYPE is not a built-in type",
        factory.getBuiltInTypeName(SimpleTypesFactory.INVALID_TYPE));
    assertNull(factory.getBuiltInTypeName(Integer.MIN_VALUE));
    assertNull(factory.getBuiltInTypeName(Integer.MAX_VALUE));
  }

  /** The lookup table must survive additional factory instantiations. */
  @Test
  public void lockFreeLookupSurvivesFactoryReinitialisation() {
    SimpleTypesFactory other = new SimpleTypesFactory();
    assertEquals("string", other.getBuiltInTypeName(SimpleTypesFactory.STRING_TYPE));
    assertEquals("string", factory.getBuiltInTypeName(SimpleTypesFactory.STRING_TYPE));
  }

  // ------------------------------------------------------------------------------------------
  // 3. The improvement: no monitor contention, and far higher throughput.
  // ------------------------------------------------------------------------------------------

  /**
   * Replays the JXPath probe ({@value #JXPATH_PROBE_LENGTH} calls) concurrently and compares the
   * pre-F1 {@code Hashtable} path against the F1 lock-free path.
   * <p>
   * The decisive, timing-independent assertion is the monitor-contention count: the F1 path must
   * not block a single time, because it never enters a {@code synchronized} region.
   */
  @Test
  public void lockFreeLookupRemovesMonitorContention() throws Exception {
    // -- warm up both paths so the comparison is not dominated by interpretation / JIT.
    measure(LEGACY_HASHTABLE_LOOKUP);
    measure(LOCK_FREE_LOOKUP);

    Result legacy = measureBest(LEGACY_HASHTABLE_LOOKUP);
    Result lockFree = measureBest(LOCK_FREE_LOOKUP);

    report(legacy, lockFree);

    assertEquals("both strategies must resolve the same names", legacy.resolved, lockFree.resolved);

    assertEquals("F1 must never block on a monitor - it contains no synchronized region", 0L,
        lockFree.blockedCount);

    if (THREADS > 1 && Runtime.getRuntime().availableProcessors() > 1) {
      assertTrue("the pre-F1 Hashtable path is expected to show monitor contention, but blocked "
          + legacy.blockedCount + " times", legacy.blockedCount > 0L);

      assertTrue("F1 is expected to be faster than the synchronized Hashtable lookup, but took "
          + nanosToMillis(lockFree.elapsedNanos) + " ms vs. "
          + nanosToMillis(legacy.elapsedNanos) + " ms",
          lockFree.elapsedNanos < legacy.elapsedNanos);
    }
  }

  // ------------------------------------------------------------------------------------------
  // Harness
  // ------------------------------------------------------------------------------------------

  /** A strategy for resolving a built-in type code to its name. */
  private interface NameLookup {
    String name(int code);
  }

  /** Outcome of one or more measurement runs. */
  private static final class Result {
    private final long elapsedNanos;
    private final long blockedCount;
    private final long resolved;

    Result(final long elapsedNanos, final long blockedCount, final long resolved) {
      this.elapsedNanos = elapsedNanos;
      this.blockedCount = blockedCount;
      this.resolved = resolved;
    }
  }

  /** Runs {@link #MEASUREMENTS} measurements, keeping the fastest time and all blocked counts. */
  private static Result measureBest(final NameLookup lookup) throws Exception {
    long bestNanos = Long.MAX_VALUE;
    long blocked = 0L;
    long resolved = -1L;

    for (int i = 0; i < MEASUREMENTS; i++) {
      Result run = measure(lookup);
      bestNanos = Math.min(bestNanos, run.elapsedNanos);
      blocked += run.blockedCount;
      resolved = run.resolved;
    }
    return new Result(bestNanos, blocked, resolved);
  }

  /**
   * Lets {@link #THREADS} workers replay the probe {@link #ROUNDS} times each, measuring wall clock
   * time and the number of times the workers had to block on a monitor.
   */
  private static Result measure(final NameLookup lookup) throws Exception {
    final CyclicBarrier started = new CyclicBarrier(THREADS + 1);
    final CyclicBarrier finished = new CyclicBarrier(THREADS + 1);
    final AtomicLong blockedCount = new AtomicLong();
    final AtomicLong resolved = new AtomicLong();
    final AtomicLong failures = new AtomicLong();

    Thread[] workers = new Thread[THREADS];
    for (int i = 0; i < THREADS; i++) {
      workers[i] = new Thread(new Runnable() {
        public void run() {
          ThreadMXBean threads = ManagementFactory.getThreadMXBean();
          long threadId = Thread.currentThread().getId();
          try {
            started.await();

            long blockedBefore = blockedCount(threads, threadId);
            long hits = 0L;
            for (int round = 0; round < ROUNDS; round++) {
              for (int code = 0; code < JXPATH_PROBE_LENGTH; code++) {
                if (lookup.name(code) != null) {
                  hits++; // -- consume the result so it cannot be optimised away
                }
              }
            }
            long blockedAfter = blockedCount(threads, threadId);

            blockedCount.addAndGet(blockedAfter - blockedBefore);
            resolved.addAndGet(hits);
            finished.await();
          } catch (Exception e) {
            failures.incrementAndGet();
          }
        }
      }, "F1-probe-" + i);
      workers[i].setDaemon(true);
      workers[i].start();
    }

    started.await();
    long start = System.nanoTime();
    finished.await();
    long elapsed = System.nanoTime() - start;

    for (int i = 0; i < THREADS; i++) {
      workers[i].join();
    }
    assertEquals("worker threads must not fail", 0L, failures.get());

    return new Result(elapsed, blockedCount.get(), resolved.get());
  }

  private static long blockedCount(final ThreadMXBean threads, final long threadId) {
    ThreadInfo info = threads.getThreadInfo(threadId);
    return info == null ? 0L : info.getBlockedCount();
  }

  private static IndexedPropertyDescriptor findIndexedProperty(final Class<?> beanClass,
      final String propertyName) throws Exception {
    BeanInfo beanInfo = Introspector.getBeanInfo(beanClass);
    for (PropertyDescriptor descriptor : beanInfo.getPropertyDescriptors()) {
      if (propertyName.equals(descriptor.getName())
          && descriptor instanceof IndexedPropertyDescriptor) {
        return (IndexedPropertyDescriptor) descriptor;
      }
    }
    return null;
  }

  private static String nanosToMillis(final long nanos) {
    return String.format("%.3f", Double.valueOf(nanos / 1000000.0d));
  }

  private static void report(final Result legacy, final Result lockFree) {
    long calls = (long) THREADS * ROUNDS * JXPATH_PROBE_LENGTH;
    StringBuilder out = new StringBuilder(512);
    out.append("\n--- F1: SimpleTypesFactory.getBuiltInTypeName(int) --------------------\n");
    out.append("  workers .................. ").append(THREADS).append('\n');
    out.append("  lookups per run .......... ").append(calls).append('\n');
    out.append("  before (Hashtable) ....... ").append(nanosToMillis(legacy.elapsedNanos))
        .append(" ms, blocked on monitor ").append(legacy.blockedCount).append(" times\n");
    out.append("  after  (lock-free array) . ").append(nanosToMillis(lockFree.elapsedNanos))
        .append(" ms, blocked on monitor ").append(lockFree.blockedCount).append(" times\n");
    if (lockFree.elapsedNanos > 0L) {
      out.append("  speed-up ................. ")
          .append(String.format("%.1f", Double.valueOf((double) legacy.elapsedNanos
              / (double) lockFree.elapsedNanos)))
          .append("x\n");
    }
    out.append("-----------------------------------------------------------------------");
    System.out.println(out);
  }
}



