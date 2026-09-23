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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

/**
 * Verifies improvement <b>F2c</b>: the per-instance lookup tables of {@link Schema} are
 * {@link java.util.concurrent.ConcurrentHashMap}s rather than {@link java.util.Hashtable}s, so the
 * resolution methods that dominate schema parsing no longer acquire a monitor.
 * <p>
 * Nine fields were converted: {@code _attributeGroups}, {@code _attributes}, {@code _complexTypes},
 * {@code _elements}, {@code _groups}, {@code _redefineSchemas}, {@code _importedSchemas},
 * {@code _cachedincludedSchemas} and {@code _simpleTypes}.
 * <p>
 * <b>Null handling is unchanged.</b> {@link java.util.Hashtable} rejects {@code null} keys and
 * values just like {@link java.util.concurrent.ConcurrentHashMap}, so no call site changes
 * behaviour. {@link #nullNamesAreStillRejected()} pins the externally visible contract.
 */
public class SchemaConcurrentMapTest {

  /** Number of concurrent workers. */
  private static final int THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());

  /** Resolution rounds per worker. */
  private static final int ROUNDS = 20000;

  /** How many declarations of each kind the fixture schema holds. */
  private static final int FIXTURE_SIZE = 20;

  /**
   * Target namespace of the fixture.
   * <p>
   * Note that {@link Schema#Schema()} binds the <em>XML Schema</em> namespace to the default
   * prefix, so the single-argument {@link Schema#getSimpleType(String)} would resolve an unprefixed
   * name against that namespace and throw for a user-defined type. User-defined simple types must
   * therefore be looked up with {@link Schema#getSimpleType(String, String)} and this namespace,
   * which is exactly what {@link Schema#addSimpleType(SimpleType)} does internally.
   */
  private static final String TARGET_NS = "http://castor.exolab.org/f2c-test";

  private Schema schema;

  @Before
  public void buildSchema() throws Exception {
    schema = new Schema();
    schema.setTargetNamespace(TARGET_NS);

    for (int i = 0; i < FIXTURE_SIZE; i++) {
      ComplexType complexType = schema.createComplexType("complexType" + i);
      schema.addComplexType(complexType);

      ElementDecl element = new ElementDecl(schema);
      element.setName("element" + i);
      element.setTypeReference("string");
      schema.addElementDecl(element);

      AttributeDecl attribute = new AttributeDecl(schema, "attribute" + i);
      schema.addAttribute(attribute);

      ModelGroup group = new ModelGroup("group" + i, schema);
      schema.addModelGroup(group);

      AttributeGroupDecl attributeGroup = new AttributeGroupDecl(schema);
      attributeGroup.setName("attributeGroup" + i);
      schema.addAttributeGroup(attributeGroup);

      SimpleType simpleType = schema.createSimpleType("simpleType" + i, "string", "restriction");
      schema.addSimpleType(simpleType);
    }
  }

  // --------------------------------------------------------------------------------------------
  // 1. Behaviour must be unchanged
  // --------------------------------------------------------------------------------------------

  /** Every declaration added to the fixture must resolve, and unknown names must yield null. */
  @Test
  public void declarationsStillResolve() {
    for (int i = 0; i < FIXTURE_SIZE; i++) {
      assertNotNull("complexType" + i, schema.getComplexType("complexType" + i));
      assertNotNull("element" + i, schema.getElementDecl("element" + i));
      assertNotNull("attribute" + i, schema.getAttribute("attribute" + i));
      assertNotNull("group" + i, schema.getModelGroup("group" + i));
      assertNotNull("attributeGroup" + i, schema.getAttributeGroup("attributeGroup" + i));
      assertNotNull("simpleType" + i, schema.getSimpleType("simpleType" + i, TARGET_NS));
    }

    assertNull(schema.getComplexType("nope"));
    assertNull(schema.getElementDecl("nope"));
    assertNull(schema.getAttribute("nope"));
    assertNull(schema.getModelGroup("nope"));
    assertNull(schema.getAttributeGroup("nope"));
    assertNull(schema.getSimpleType("nope", TARGET_NS));
  }

  /** The collection accessors must still report every declaration exactly once. */
  @Test
  public void collectionAccessorsAreComplete() {
    assertEquals(FIXTURE_SIZE, schema.getComplexTypes().size());
    assertEquals(FIXTURE_SIZE, schema.getElementDecls().size());
    assertEquals(FIXTURE_SIZE, schema.getAttributes().size());
    assertEquals(FIXTURE_SIZE, schema.getModelGroups().size());
    assertEquals(FIXTURE_SIZE, schema.getAttributeGroups().size());
    assertEquals(FIXTURE_SIZE, schema.getSimpleTypes().size());
  }

  /**
   * {@link Schema#getSimpleTypes()} walks the simple-type table while re-putting resolved
   * "deferred" types into it. That used to rely on the deliberately non-fail-fast
   * {@code Hashtable.elements()}; it now relies on the weakly consistent iteration of
   * {@link java.util.concurrent.ConcurrentHashMap}. Either way it must not throw and must be
   * repeatable.
   */
  @Test
  public void getSimpleTypesIsStableWhenItRewritesTheTable() {
    Collection<SimpleType> first = schema.getSimpleTypes();
    Collection<SimpleType> second = schema.getSimpleTypes();

    assertEquals(FIXTURE_SIZE, first.size());
    assertEquals(first.size(), second.size());
    assertSame("repeated lookups must return the same instances",
        schema.getSimpleType("simpleType0", TARGET_NS),
        schema.getSimpleType("simpleType0", TARGET_NS));
  }

  /** Removal must still work through the converted maps. */
  @Test
  public void removalStillWorks() {
    ElementDecl element = schema.getElementDecl("element0");
    assertNotNull(element);
    assertTrue(schema.removeElement(element));
    assertNull(schema.getElementDecl("element0"));

    ComplexType complexType = schema.getComplexType("complexType0");
    assertNotNull(complexType);
    assertTrue(schema.removeComplexType(complexType));
    assertNull(schema.getComplexType("complexType0"));

    SimpleType simpleType = schema.getSimpleType("simpleType0", TARGET_NS);
    assertNotNull(simpleType);
    assertTrue(schema.removeSimpleType(simpleType));
    assertNull(schema.getSimpleType("simpleType0", TARGET_NS));
  }

  /**
   * The accessors guard against {@code null} themselves and throw
   * {@link IllegalArgumentException}; they never let a {@code null} key reach the map. That
   * contract is identical before and after the conversion.
   */
  @Test
  public void nullNamesAreStillRejected() {
    assertThrowsIllegalArgument("getComplexType", new Runnable() {
      public void run() {
        schema.getComplexType(null);
      }
    });
    assertThrowsIllegalArgument("getElementDecl", new Runnable() {
      public void run() {
        schema.getElementDecl(null);
      }
    });
    assertThrowsIllegalArgument("getAttribute", new Runnable() {
      public void run() {
        schema.getAttribute(null);
      }
    });
    assertThrowsIllegalArgument("getModelGroup", new Runnable() {
      public void run() {
        schema.getModelGroup(null);
      }
    });
    assertThrowsIllegalArgument("getAttributeGroup", new Runnable() {
      public void run() {
        schema.getAttributeGroup(null);
      }
    });
    assertThrowsIllegalArgument("getSimpleType", new Runnable() {
      public void run() {
        schema.getSimpleType(null);
      }
    });
  }

  // --------------------------------------------------------------------------------------------
  // 2. The improvement: no monitor contention
  // --------------------------------------------------------------------------------------------

  /**
   * Resolving declarations concurrently must not block on a monitor even once.
   * <p>
   * Note this measures the *shared* `Schema` instance; before F2c every one of these lookups
   * entered a `synchronized` `Hashtable` method.
   */
  @Test
  public void concurrentResolutionIsLockFree() throws Exception {
    measure(); // -- warm up

    Result result = measure();

    System.out.println("\n--- F2c: Schema declaration lookups ----------------------------------");
    System.out.println("  workers .................. " + THREADS);
    System.out.println("  lookups .................. " + result.resolved);
    System.out.println("  elapsed .................. " + nanosToMillis(result.elapsedNanos) + " ms");
    System.out.println("  blocked on monitor ....... " + result.blockedCount + " times");
    System.out.println("----------------------------------------------------------------------");

    assertEquals("the ConcurrentHashMap-backed lookups must never block on a monitor", 0L,
        result.blockedCount);
    assertEquals("every lookup must have resolved a declaration",
        (long) THREADS * ROUNDS * 6L, result.resolved);
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

  /**
   * Resolves declarations by name from {@link #THREADS} threads, counting monitor blocks.
   * <p>
   * Uses a start latch plus {@link Thread#join()} rather than a pair of barriers: a barrier-based
   * handshake deadlocks the main thread if a worker dies before reaching the second barrier, which
   * turns any assertion problem into a hung build instead of a failing test.
   */
  private Result measure() throws Exception {
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch ready = new CountDownLatch(THREADS);
    final AtomicLong blockedCount = new AtomicLong();
    final AtomicLong resolved = new AtomicLong();
    final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

    Thread[] workers = new Thread[THREADS];
    for (int i = 0; i < THREADS; i++) {
      workers[i] = new Thread(new Runnable() {
        public void run() {
          ThreadMXBean threads = ManagementFactory.getThreadMXBean();
          long threadId = Thread.currentThread().getId();
          try {
            ready.countDown();
            start.await();

            long blockedBefore = blockedCount(threads, threadId);
            long hits = 0L;
            for (int round = 0; round < ROUNDS; round++) {
              String suffix = String.valueOf(round % FIXTURE_SIZE);
              if (schema.getComplexType("complexType" + suffix) != null) {
                hits++;
              }
              if (schema.getElementDecl("element" + suffix) != null) {
                hits++;
              }
              if (schema.getAttribute("attribute" + suffix) != null) {
                hits++;
              }
              if (schema.getModelGroup("group" + suffix) != null) {
                hits++;
              }
              if (schema.getAttributeGroup("attributeGroup" + suffix) != null) {
                hits++;
              }
              if (schema.getSimpleType("simpleType" + suffix, TARGET_NS) != null) {
                hits++;
              }
            }
            long blockedAfter = blockedCount(threads, threadId);

            blockedCount.addAndGet(blockedAfter - blockedBefore);
            resolved.addAndGet(hits);
          } catch (Throwable t) {
            failure.compareAndSet(null, t);
          }
        }
      }, "F2c-lookup-" + i);
      workers[i].setDaemon(true);
      workers[i].start();
    }

    ready.await();
    long begin = System.nanoTime();
    start.countDown();
    for (int i = 0; i < THREADS; i++) {
      workers[i].join(TimeUnit.MINUTES.toMillis(2));
    }
    long elapsed = System.nanoTime() - begin;

    for (int i = 0; i < THREADS; i++) {
      assertTrue("worker " + i + " did not finish in time", !workers[i].isAlive());
    }
    if (failure.get() != null) {
      throw new AssertionError("a worker thread failed: " + failure.get(), failure.get());
    }

    return new Result(elapsed, blockedCount.get(), resolved.get());
  }

  private static long blockedCount(final ThreadMXBean threads, final long threadId) {
    ThreadInfo info = threads.getThreadInfo(threadId);
    return info == null ? 0L : info.getBlockedCount();
  }

  private static void assertThrowsIllegalArgument(final String what, final Runnable action) {
    try {
      action.run();
      fail(what + "(null) must throw IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      // -- expected
    }
  }

  private static String nanosToMillis(final long nanos) {
    return String.format("%.3f", Double.valueOf(nanos / 1000000.0d));
  }
}







