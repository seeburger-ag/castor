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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.exolab.castor.xml.schema.simpletypes.factory.Type;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Verifies improvement <b>F2</b> for {@link SimpleTypesFactory}:
 * <ol>
 * <li>the built-in type definitions are loaded <em>exactly once</em> per classloader, instead of
 * being re-unmarshalled and re-published by every single constructor call, and</li>
 * <li>the two static type tables are {@link java.util.concurrent.ConcurrentHashMap}s, so the
 * name-based lookups that run during schema parsing no longer serialise on a process-wide
 * monitor.</li>
 * </ol>
 *
 * <h2>Why the single-init guard is a correctness fix, not just an optimisation</h2>
 *
 * Before F2 the constructor unconditionally ran {@code loadTypesDefinitions()}, which re-creates
 * every {@link Type} <em>and</em> its associated {@link SimpleType} and re-puts them into the
 * shared static maps. Any schema that already held a reference to a built-in
 * {@link SimpleType} was therefore silently left pointing at an instance that the factory no
 * longer knew about, breaking identity comparisons. {@link #definitionsAreLoadedOnlyOnce()} pins
 * that down.
 *
 * <h2>Why the lock had to become static</h2>
 *
 * {@code loadTypesDefinitions()} used to be declared {@code private synchronized}, which locks the
 * <em>instance</em>. Since the state it mutates is {@code static}, two threads constructing two
 * different factories shared no lock at all and could mutate - and iterate - the static maps
 * concurrently. {@link #concurrentConstructionIsSafeAndLoadsOnce()} exercises exactly that race.
 */
public class SimpleTypesFactoryInitialisationTest {

  /** Number of concurrent workers. */
  private static final int THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());

  /** Lookups per worker in the contention measurement. */
  private static final int LOOKUPS_PER_THREAD = 200000;

  /** Number of repeated constructions used to show that re-construction is cheap. */
  private static final int CONSTRUCTIONS = 500;

  /** A representative selection of built-in type names, resolved in a tight loop. */
  private static final String[] TYPE_NAMES = {"string", "integer", "boolean", "decimal", "date",
      "anySimpleType", "token", "long", "short", "byte"};

  private static SimpleTypesFactory factory;

  @BeforeClass
  public static void createFactory() {
    // -- Schema and SimpleTypesFactory initialise each other; Schema has to win that race,
    // -- because its static initialiser creates the SimpleTypesFactory instance shared by the
    // -- whole JVM (and that instance needs Schema's BUILD_IN_SCHEMA to already exist).
    new Schema();
    factory = Schema.getTypeFactory();
    assertNotNull("Schema must expose its shared SimpleTypesFactory", factory);
  }

  // --------------------------------------------------------------------------------------------
  // 1. Single initialisation
  // --------------------------------------------------------------------------------------------

  /**
   * Constructing further factories must not rebuild the shared type definitions.
   * <p>
   * This is the decisive F2 assertion and it is completely deterministic: before F2 every
   * constructor call replaced the shared {@link Type} and {@link SimpleType} instances, so the
   * {@code assertSame} checks below failed.
   */
  @Test
  public void definitionsAreLoadedOnlyOnce() {
    Type typeBefore = factory.getType(SimpleTypesFactory.STRING_TYPE);
    SimpleType simpleTypeBefore = factory.getBuiltInType("string");
    String nameBefore = factory.getBuiltInTypeName(SimpleTypesFactory.STRING_TYPE);
    assertNotNull("precondition: the built-in types must be loaded", typeBefore);
    assertNotNull("precondition: the built-in types must be loaded", simpleTypeBefore);

    SimpleTypesFactory other = new SimpleTypesFactory();

    assertSame("constructing another factory must not replace the shared Type instance",
        typeBefore, factory.getType(SimpleTypesFactory.STRING_TYPE));
    assertSame("constructing another factory must not replace the shared SimpleType instance",
        simpleTypeBefore, factory.getBuiltInType("string"));
    assertSame("the new factory must see exactly the same shared instances", simpleTypeBefore,
        other.getBuiltInType("string"));
    assertSame("the lock-free name table must not be rebuilt with fresh strings", nameBefore,
        factory.getBuiltInTypeName(SimpleTypesFactory.STRING_TYPE));
  }

  /**
   * Because loading happens once, constructing additional factories is essentially free. Before F2
   * every construction re-read and re-unmarshalled two property files.
   */
  @Test
  public void repeatedConstructionIsCheap() {
    // -- warm up
    for (int i = 0; i < 50; i++) {
      new SimpleTypesFactory();
    }

    long start = System.nanoTime();
    for (int i = 0; i < CONSTRUCTIONS; i++) {
      new SimpleTypesFactory();
    }
    long elapsedNanos = System.nanoTime() - start;
    long perConstructionNanos = elapsedNanos / CONSTRUCTIONS;

    System.out.println("\n--- F2: SimpleTypesFactory construction ------------------------------");
    System.out.println("  constructions ............ " + CONSTRUCTIONS);
    System.out.println("  total .................... " + nanosToMillis(elapsedNanos) + " ms");
    System.out.println("  per construction ......... " + perConstructionNanos + " ns");
    System.out.println("----------------------------------------------------------------------");

    // -- An XML unmarshal of the built-in type list cannot possibly complete in a microsecond,
    // -- so this bound proves the load path is not being re-entered. It is generous enough to
    // -- stay reliable on a loaded CI machine.
    assertTrue("constructing a factory must not reload the type definitions, but took "
        + perConstructionNanos + " ns", perConstructionNanos < 100000L);
  }

  /**
   * Many threads constructing factories at the same time must neither fail nor corrupt the shared
   * state.
   * <p>
   * Before F2 this was a genuine race: {@code loadTypesDefinitions()} synchronized on {@code this},
   * so concurrent constructions mutated the static maps through different locks while
   * {@code rebuildTypeNameTable()} iterated them.
   */
  @Test
  public void concurrentConstructionIsSafeAndLoadsOnce() throws Exception {
    final SimpleType expected = factory.getBuiltInType("string");
    final CyclicBarrier ready = new CyclicBarrier(THREADS);
    final AtomicLong failures = new AtomicLong();
    final AtomicReference<Throwable> firstFailure = new AtomicReference<Throwable>();

    Thread[] workers = new Thread[THREADS];
    for (int i = 0; i < THREADS; i++) {
      workers[i] = new Thread(new Runnable() {
        public void run() {
          try {
            ready.await();
            for (int n = 0; n < 200; n++) {
              SimpleTypesFactory local = new SimpleTypesFactory();
              if (local.getBuiltInType("string") != expected) {
                throw new IllegalStateException("shared SimpleType instance was replaced");
              }
              if (!"string".equals(local.getBuiltInTypeName(SimpleTypesFactory.STRING_TYPE))) {
                throw new IllegalStateException("lock-free name table was corrupted");
              }
            }
          } catch (Throwable t) {
            failures.incrementAndGet();
            firstFailure.compareAndSet(null, t);
          }
        }
      }, "F2-init-" + i);
      workers[i].setDaemon(true);
      workers[i].start();
    }
    for (int i = 0; i < THREADS; i++) {
      workers[i].join();
    }

    if (failures.get() > 0L) {
      throw new AssertionError("concurrent construction failed " + failures.get() + " times, first: "
          + firstFailure.get(), firstFailure.get());
    }
  }

  // --------------------------------------------------------------------------------------------
  // 2. Lock-free name lookups
  // --------------------------------------------------------------------------------------------

  /**
   * {@link SimpleTypesFactory#getBuiltInType(String)} resolves through {@code _typesByName}, which
   * F2 turned into a {@link java.util.concurrent.ConcurrentHashMap}. Reading it concurrently must
   * therefore not block on a monitor even once.
   * <p>
   * This is the name-based counterpart to the code-based lookup covered by
   * {@link SimpleTypesFactoryLockFreeLookupTest}; together they cover both static tables.
   */
  @Test
  public void builtInTypeLookupByNameIsLockFree() throws Exception {
    Result warmUp = measureNameLookups();
    assertTrue("precondition: the warm-up must resolve types", warmUp.resolved > 0L);

    Result result = measureNameLookups();

    System.out.println("\n--- F2: SimpleTypesFactory.getBuiltInType(String) --------------------");
    System.out.println("  workers .................. " + THREADS);
    System.out.println("  lookups .................. " + result.resolved);
    System.out.println("  elapsed .................. " + nanosToMillis(result.elapsedNanos) + " ms");
    System.out.println("  blocked on monitor ....... " + result.blockedCount + " times");
    System.out.println("----------------------------------------------------------------------");

    assertEquals("the ConcurrentHashMap lookup must never block on a monitor", 0L,
        result.blockedCount);
    assertEquals("every lookup must have resolved a type",
        (long) THREADS * LOOKUPS_PER_THREAD, result.resolved);
  }

  /** The switch to {@link java.util.concurrent.ConcurrentHashMap} must not change behaviour. */
  @Test
  public void nameLookupBehaviourIsUnchanged() {
    for (String name : TYPE_NAMES) {
      SimpleType type = factory.getBuiltInType(name);
      assertNotNull("built-in type '" + name + "' must resolve", type);
      assertEquals(name, type.getName());
    }
    assertEquals("unknown names must still yield null", null,
        factory.getBuiltInType("no-such-built-in-type"));
  }

  // --------------------------------------------------------------------------------------------
  // Harness
  // --------------------------------------------------------------------------------------------

  /** Outcome of a measurement run. */
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

  /** Resolves built-in types by name from {@link #THREADS} threads, counting monitor blocks. */
  private static Result measureNameLookups() throws Exception {
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
            for (int n = 0; n < LOOKUPS_PER_THREAD; n++) {
              if (factory.getBuiltInType(TYPE_NAMES[n % TYPE_NAMES.length]) != null) {
                hits++; // -- consume the result so it cannot be optimised away
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
      }, "F2-name-lookup-" + i);
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

  private static String nanosToMillis(final long nanos) {
    return String.format("%.3f", Double.valueOf(nanos / 1000000.0d));
  }
}

