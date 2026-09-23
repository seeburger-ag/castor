# Thread Dump Analysis - JXPath / Castor Blocking (`dump2.txt`)

_Analysis date: 2026-09-22, updated 2026-09-23. JVM: Java 21.0.12. Container: Apache Karaf / Felix._

_Sources inspected: `commons-jxpath` (SEEBURGER fork) and `castor` (this repository)._

_Thread dump analysed: `dump2.txt` (kept in the `commons-jxpath` working copy)._

## 0. Status at a glance

| Fix | Repository | Commit | State |
|---|---|---|---|
| **F1** - lock-free `getBuiltInTypeName` | `castor` | `8fa4a3f3a` | **Committed and verified** (see 5a) |
| **F3a** - hide the property from JXPath | `commons-jxpath` (branch `1.4-seeburger`) | `5ed7f7f4` | **Committed and verified** (see 5b) |
| **F2** - `ConcurrentHashMap` + single init | `castor` | working tree | **Implemented and verified** (see 5c) |
| F2c - de-`Hashtable` the `Schema` fields | `castor` | - | Deferred, see 5c |
| F4 - JXPath probe hardening | `commons-jxpath` | - | Open |
| F5 - expression / model redesign | Engine | - | Open |

Both working trees are clean apart from the F2 change; the only untracked files are `dump2.txt` in
`commons-jxpath` and the new F2 test in `castor`.

## 1. Summary

All JXPath-related threads in `dump2.txt` are blocked on **one single monitor**:

```
<0x00000003e973c7c8> (a java.util.Hashtable)
```

That `Hashtable` is `org.exolab.castor.xml.schema.SimpleTypesFactory._typesByCode`, a
**`static`** field, that is, exactly one instance per classloader, guarded by the intrinsic lock
of `Hashtable`.

This is **monitor contention (a lock convoy), not a deadlock**. The lock owner has the same
stack as the waiters and is making progress; all other worker threads serialize behind it.

| Item | Value |
|---|---|
| Contended monitor | `0x00000003e973c7c8` (`java.util.Hashtable`) |
| Real identity of the monitor | `SimpleTypesFactory._typesByCode` (**static**) |
| Lock owner | `dump2.txt:7244` (`- locked ...`), same JXPath/Castor code path |
| Blocked threads | 20+ BPEL/JMS workers |
| Blocked thread lines | 2964, 6119, 7318, 7394, 7470, 7546, 7622, 7698, 7774, 7967, 8043, 8119, 8195, 8271, 8347, 8423, 8499, 8575, 8651, 8727, ... |
| Deadlock | **No** |
| Amplification | **16000 locked lookups per length probe**, probe repeated per node scan (see section 4) |

## 2. Representative blocked stack (`dump2.txt:2964` ff.)

```
java.lang.Thread.State: BLOCKED (on object monitor)
  at java.util.Hashtable.get(Hashtable.java:380)
  - waiting to lock <0x00000003e973c7c8> (a java.util.Hashtable)
  at org.exolab.castor.xml.schema.SimpleTypesFactory.getType(SimpleTypesFactory.java:372)
  at org.exolab.castor.xml.schema.SimpleTypesFactory.getBuiltInTypeName(SimpleTypesFactory.java:222)
  at org.exolab.castor.xml.schema.Schema.getBuiltInTypeName(Schema.java:867)
  at java.lang.invoke.LambdaForm$DMH/0x00000007cd954000.invokeVirtual(java.base@21.0.12/LambdaForm$DMH)
  at java.lang.invoke.LambdaForm$MH/0x00000007cee53800.invoke(java.base@21.0.12/LambdaForm$MH)
  at java.lang.invoke.Invokers$Holder.invokeExact_MT(java.base@21.0.12/Invokers$Holder)
  at jdk.internal.reflect.DirectMethodHandleAccessor.invokeImpl(java.base@21.0.12/DirectMethodHandleAccessor.java:154)
  at jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
  at java.lang.reflect.Method.invoke(java.base@21.0.12/Method.java:580)
  at org.apache.commons.jxpath.util.ValueUtils.getIndexedPropertyLength(ValueUtils.java:122)
  at org.apache.commons.jxpath.ri.model.beans.BeanPropertyPointer.getLength(BeanPropertyPointer.java:202)
  at org.apache.commons.jxpath.ri.model.beans.PropertyIterator.getLength(PropertyIterator.java:318)
  at org.apache.commons.jxpath.ri.model.beans.PropertyIterator.setPositionAllProperties(PropertyIterator.java:206)
  at org.apache.commons.jxpath.ri.model.beans.PropertyIterator.setPosition(PropertyIterator.java:139)
  at org.apache.commons.jxpath.ri.axes.DescendantContext.nextNode(DescendantContext.java:114)
  at org.apache.commons.jxpath.ri.EvalContext.nextSet(EvalContext.java:350)
  at org.apache.commons.jxpath.ri.axes.ChildContext.getSingleNodePointer(ChildContext.java:70)
  at org.apache.commons.jxpath.ri.compiler.Path.searchForPath(Path.java:201)
  at org.apache.commons.jxpath.ri.compiler.Path.getSingleNodePointerForSteps(Path.java:176)
  at org.apache.commons.jxpath.ri.compiler.LocationPath.computeValue(LocationPath.java:87)
  at org.apache.commons.jxpath.ri.compiler.CoreFunction.functionStartsWith(CoreFunction.java:545)
  at org.apache.commons.jxpath.ri.compiler.CoreFunction.computeValue(CoreFunction.java:268)
  at org.apache.commons.jxpath.ri.compiler.CoreOperationCompare.equal(CoreOperationCompare.java:81)
  at org.apache.commons.jxpath.ri.compiler.CoreOperationCompare.computeValue(CoreOperationCompare.java:60)
  at org.apache.commons.jxpath.ri.JXPathContextReferenceImpl.getValue(JXPathContextReferenceImpl.java:404/450/439)
  at com.seeburger.engine.expr.jxpath.CustomUtilFunctions.postEvaluateXPath(CustomUtilFunctions.java:2068)
  at com.seeburger.engine.expr.jxpath.CustomUtilFunctions.evalExpression(CustomUtilFunctions.java:1885)
  at com.seeburger.engine.expr.jxpath.CustomUtilFunctions.expr(CustomUtilFunctions.java:1798)
  at com.seeburger.engine.expr.jxpath.JXPathExpressionParser.evaluateExpression(JXPathExpressionParser.java:368)
  at com.seeburger.engine.model.impl.base.AbstractBPELObject.evaluateXPathExpression(AbstractBPELObject.java:412)
  at com.seeburger.engine.model.impl.activity.AssignImpl.extractFromData(AssignImpl.java:399)
  at com.seeburger.engine.model.impl.activity.AssignImpl.processCopy(AssignImpl.java:300)
  at com.seeburger.engine.model.impl.activity.AssignImpl.execute(AssignImpl.java:184)
  at com.seeburger.engine.model.impl.base.ExecutionQueue2.execute(ExecutionQueue2.java:213)
  at com.seeburger.engine.model.impl.base.BPELObjectExecutor.startObjectExecution(BPELObjectExecutor.java:195)
  at com.seeburger.engine.model.impl.base.BusinessProcessImpl.messageReceived(BusinessProcessImpl.java:784)
  at com.seeburger.engine.managers.impl.ProcessManager.createBusinessProcess(ProcessManager.java:660)
  at com.seeburger.engine.managers.impl.CorrelationManager.findRootProcess(CorrelationManager.java:2128)
  at com.seeburger.engine.managers.impl.CorrelationManager.incomingMessage(CorrelationManager.java:347)
  at com.seeburger.engine.BusinessProcessEngineBase.createBusinessProcess(BusinessProcessEngineBase.java:115)
  at com.seeburger.engine.util.TransactionUtil.transactional(TransactionUtil.java:126)
  at com.seeburger.engine.BusinessProcessEngineService.createBusinessProcess(BusinessProcessEngineService.java:1378)
  at com.seeburger.engine.jms.ProcessCorrelationBase.processMsg(ProcessCorrelationBase.java:191)
```

> **Correction to the first pass of this analysis:** there are *no* application frames between
> `Schema.getBuiltInTypeName` and `Method.invoke`, only JDK reflection glue. JXPath calls the
> Castor method **directly by reflection**.

## 3. Castor source evidence

### 3.1 `SimpleTypesFactory`, static `Hashtable` lookups

`castor/schema/src/main/java/org/exolab/castor/xml/schema/SimpleTypesFactory.java`
(line numbers below are those of the **original, pre-F1** file, matching the thread dump)

```java
// line 143
private static Map<String, Type> _typesByName = new Hashtable<String, Type>();

// line 148  <-- THIS is monitor 0x00000003e973c7c8
private static Map<Integer, Type> _typesByCode = new Hashtable<Integer, Type>();

// line 157
public SimpleTypesFactory() {
    loadTypesDefinitions();              // re-runs on EVERY instantiation
}

// line 221
public String getBuiltInTypeName(final int builtInTypeCode) {
    Type type = getType(builtInTypeCode);
    if (type == null) { return null; }   // <-- NEVER throws for unknown codes
    return type.getName();
}

// line 371
private Type getType(final int typeCode) {
    return _typesByCode.get(Integer.valueOf(typeCode));   // synchronized Hashtable.get
}

// line 379
private synchronized void loadTypesDefinitions() { ... }  // XML unmarshalling, mutates static maps
```

| # | Finding | Impact |
|---|---|---|
| C1 | `_typesByCode` / `_typesByName` are **static `Hashtable`s**, so `get()` is `synchronized` on a single JVM-wide object. | Global serialization point for *all* threads doing schema work. |
| C2 | `getBuiltInTypeName(int)` **returns `null`** instead of throwing for out-of-range codes. | Defeats the loop-termination contract of JXPath (section 4), giving 16000 iterations instead of about 45. |
| C3 | `loadTypesDefinitions()` runs in the **constructor**, re-unmarshalling `SimpleTypes.properties` and re-putting into the *static* maps on every `new SimpleTypesFactory()`. | Wasted work; concurrent structural mutation of shared static state. |
| C4 | Type codes are a small, fixed range (`-1` to `ANYSIMPLETYPE_TYPE = 100`) yet are looked up via boxed `Integer` keys in a hash map. | A plain array lookup would be O(1) **and** lock-free. |

### 3.2 `Schema`, the reflection entry point

`castor/schema/src/main/java/org/exolab/castor/xml/schema/Schema.java`

```java
// line 94  -- one factory for the whole JVM
private static SimpleTypesFactory simpleTypesFactory = new SimpleTypesFactory();

// line 866 -- signature `String getXxx(int)` == JavaBeans INDEXED GETTER pattern
public String getBuiltInTypeName(int builtInTypeCode) {
    return simpleTypesFactory.getBuiltInTypeName(builtInTypeCode);
}
```

`Schema` additionally uses `Hashtable` for `_attributeGroups`, `_attributes` and others
(lines 108-114). Those are *per-instance* locks and contribute far less, but are the same
anti-pattern.

### 3.3 `Schema` is reachable from almost every schema node

`public Schema getSchema()` exists on `XMLType.java:166`, `ElementDecl.java:400`,
`AttributeDecl.java:292`, `ModelGroup.java:356`, `AttributeGroupDecl.java:218`,
`Wildcard.java:182`.

So a `//` (descendant) traversal reaches a `Schema` node from **every** element, attribute, type
and group it visits, and the object graph is **cyclic**
(`Schema -> ElementDecl -> Schema -> ...`).

### 3.4 Other `getXxx(int)` methods (benign)

`Group.getParticle(int)`, `ContentModelGroupImpl.getParticle(int)` (line 309) and
`ComplexType.getParticle(int)` delegate to `Vector.elementAt(index)`, which **does** throw
`ArrayIndexOutOfBoundsException`. The probe loop of JXPath therefore terminates correctly after
`size()+1` calls: inefficient, but not pathological.

## 4. Root cause - the pathological interaction

### 4.1 JavaBeans mis-detection

`java.beans.Introspector` sees `String Schema.getBuiltInTypeName(int)` and classifies
`builtInTypeName` as an **indexed property** with:

- indexed read method = `getBuiltInTypeName(int)`
- **non-indexed read method = `null`** (there is no `String[] getBuiltInTypeName()`)

### 4.2 The "guess the length" fallback in JXPath

`commons-jxpath/src/main/java/org/apache/commons/jxpath/util/ValueUtils.java`

```java
private static final int UNKNOWN_LENGTH_MAX_COUNT = 16000;          // line 45

public static int getIndexedPropertyLength(Object object, IndexedPropertyDescriptor pd) {
    if (pd.getReadMethod() != null) {                               // line 110 -- NOT taken
        return getLength(getValue(object, pd));
    }
    Method readMethod = pd.getIndexedReadMethod();
    ...
    for (int i = 0; i < UNKNOWN_LENGTH_MAX_COUNT; i++) {            // line 120
        try {
            readMethod.invoke(object, new Object[] { new Integer(i) });
        }
        catch (Throwable t) {
            return i;                                               // ONLY normal exit
        }
    }
    throw new JXPathException("Cannot determine the length of the indexed property " + pd.getName());
}
```

The **only** normal exit of the loop is an exception from the getter. Because
`getBuiltInTypeName` of Castor returns `null` instead of throwing (finding **C2**), the loop runs
the **full 16000 iterations** and then throws `JXPathException`.

`BeanPropertyPointer.getLength()` (lines 195-205) routes every `IndexedPropertyDescriptor` into
that method, and `PropertyIterator.setPositionAllProperties` (line 206) calls `getLength()` for
**every property of every bean** the descendant axis visits.

### 4.2b The cost is completely silent, and repeated

`PropertyIterator.getLength()` (lines 311-325) is documented as *"Computes length for the current
pointer - ignores any exceptions"*:

```java
private int getLength() {
    int length;
    try {
        length = propertyNodePointer.getLength();     // -> the 16000-call probe
    }
    catch (Throwable t) {
        propertyNodePointer.handle(t);                // -> dropped, see below
        length = 0;
    }
    return length;
}
```

and `NodePointer.handle(Throwable)` (lines 818-835) **discards** the throwable when no
`ExceptionHandler` is installed, which is the default. Consequences:

- the XPath expression **succeeds** and returns a correct result;
- **nothing is logged**, there is no `"Cannot determine the length of the indexed property"`
  message anywhere;
- the only symptom is burnt CPU and, in production, the monitor the getter happens to acquire;
- the `0` length is never cached, so the probe runs **again** on the next scan.

Measured in `JXPathFilteredBeanInfoTest` with a single bean carrying one such property, one
`//*` evaluation invoked the indexed getter **96000 times = 6 full probes**.

### 4.3 Cost model

```
per length probe
  = 16000 * ( reflective Method.invoke
            + Integer boxing
            + synchronized Hashtable.get on ONE static monitor )
```

With `P` probes per visited node, `N` nodes visited by the `//` axis, `K` evaluations per process
instance and `M` concurrent BPEL threads:

```
locked Hashtable.get calls = 16000 * P * N * K * M
```

All of them queue on the *same* monitor. Throughput collapses; threads look "hung" but are merely
waiting in line.

### 4.4 Contributing factors

1. **`DescendantContext.nextNode`**: the expression uses the `//` descendant axis, so the whole
   (cyclic) Castor schema object graph is walked.
2. **`CoreFunction.functionStartsWith` + `CoreOperationCompare.equal`** re-evaluate the location
   path per candidate node.
3. The expression is evaluated **per `<assign><copy>` per process instance**, on many concurrent
   JMS correlation threads (`ProcessCorrelationBase.processMsg`).

## 5. Improvement plan

Five independent layers. **F1 and F3 together remove the bottleneck completely**; each can be
shipped on its own.

### F1 - Cache `getBuiltInTypeName` in Castor (primary target: highest value, lowest risk)

> **Status: IMPLEMENTED, VERIFIED, COMMITTED** as `8fa4a3f3a`.
> Code: `castor/schema/src/main/java/org/exolab/castor/xml/schema/SimpleTypesFactory.java`
> Test: `castor/schema/src/test/java/org/exolab/castor/xml/schema/SimpleTypesFactoryLockFreeLookupTest.java`
> See section 5a for the measured result.

Built-in type codes are a **fixed, small integer range** (`INVALID_TYPE = -1` to
`ANYSIMPLETYPE_TYPE = 100`) that never changes after class initialization. Replace the
`Hashtable` lookup with a **lock-free immutable array**.

```java
/**
 * Lock-free lookup table: built-in type code -> type name.
 * Rebuilt (once) at the end of loadTypesDefinitions() and published through a volatile
 * write, so readers never need to synchronize.
 */
private static volatile String[] _typeNamesByCode = new String[0];

/** O(1), allocation-free, lock-free. */
public String getBuiltInTypeName(final int builtInTypeCode) {
    final String[] names = _typeNamesByCode;          // single volatile read
    if (builtInTypeCode < 0 || builtInTypeCode >= names.length) {
        return null;
    }
    return names[builtInTypeCode];
}

/** Called from the end of the synchronized loadTypesDefinitions(). */
private static void rebuildTypeNameTable() {
    int maxCode = 0;
    for (Integer code : _typesByCode.keySet()) {
        if (code.intValue() > maxCode) { maxCode = code.intValue(); }
    }
    final String[] names = new String[maxCode + 1];
    for (Map.Entry<Integer, Type> entry : _typesByCode.entrySet()) {
        final int code = entry.getKey().intValue();
        if (code >= 0) { names[code] = entry.getValue().getName(); }
    }
    _typeNamesByCode = names;                          // safe publication
}
```

**Effect:** monitor `0x00000003e973c7c8` is no longer acquired on the hot path at all. Even if
the 16000-iteration loop still runs (F3 not yet applied), it degenerates to 16000 array reads:
microseconds, fully parallel, zero contention.

**Risk:** none functionally. `getBuiltInTypeName` keeps returning `null` for unknown codes and
the same `String` instances for known ones; behaviour is byte-for-byte identical.

The call site is the end of the population loop in `loadTypesDefinitions()` (lines 403-407 of the
original file, line 469 after the patch):

```java
    for (Type type : typeList.getTypes()) {
        _typesByName.put(type.getName(), type);
        type.setSimpleType(createSimpleType(BUILD_IN_SCHEMA, type));
        _typesByCode.put(Integer.valueOf(type.getSimpleType().getTypeCode()), type);
    }
    rebuildTypeNameTable();          // <-- ADDED
```

**Variant F1b:** if touching the load path is undesirable, put a lazily populated
`ConcurrentHashMap<Integer, String>` (or `AtomicReferenceArray`) in front of `getType(int)`.
Still lock-free, but slower and more code than the array. Not recommended; the array was chosen.

### F2 - De-`Hashtable` and single-init Castor (structural hygiene)

> **Status: IMPLEMENTED AND VERIFIED** (working tree).
> Code: `castor/schema/src/main/java/org/exolab/castor/xml/schema/SimpleTypesFactory.java`
> Test: `castor/schema/src/test/java/org/exolab/castor/xml/schema/SimpleTypesFactoryInitialisationTest.java`
> See section 5c for the measured result.

1. Change `_typesByName` / `_typesByCode` to `ConcurrentHashMap`. This also relieves the
   `getBuiltInType(String)` / `getSimpleType(...)` paths, which sit on the same monitor whenever
   a schema is parsed.
2. Guard `loadTypesDefinitions()` so it runs **once**:

```java
private static final Object INIT_LOCK = new Object();
private static volatile boolean _typesLoaded;

public SimpleTypesFactory() {
    ensureTypesLoaded();
}

private void ensureTypesLoaded() {
    if (_typesLoaded) {
        return;                       // fast path: one volatile read
    }
    synchronized (INIT_LOCK) {
        if (_typesLoaded) {
            return;
        }
        loadTypesDefinitions();
        _typesLoaded = true;
    }
}
```

   A static holder class would also do the job and let the JVM class-initialization lock handle
   it, but it moves the load to a different point in time, which the initialization-order trap
   below makes risky. Double-checked locking keeps the timing exactly as it was.
3. Same treatment for the per-instance `Hashtable` fields of `Schema` (lines 108-114). See 5c for
   why this part is deferred.

**Risk:** low. Keep `Map` as the declared type so no call site changes. Note that
`ConcurrentHashMap` rejects `null` keys and values - but so does `Hashtable`, so this is not a
behaviour change.

> **Class-initialization ordering (discovered while testing F1).** `Schema` and
> `SimpleTypesFactory` initialise each other: `Schema.<clinit>` creates the shared
> `SimpleTypesFactory`, whose `<clinit>` creates `BUILD_IN_SCHEMA = new Schema()`. If
> `SimpleTypesFactory` is the *first* class touched, `BUILD_IN_SCHEMA` is still `null` when the
> constructor runs, and initialization fails with `"'schema' must not be null"`. Any refactoring
> for F2 must preserve "Schema first".

### F3 - Stop JXPath from probing the property at all

**F3a - hide the property from JXPath (implemented).**

> **Status: IMPLEMENTED, VERIFIED, COMMITTED** as `5ed7f7f4` on branch `1.4-seeburger`.
> Code: `commons-jxpath/src/main/java/org/apache/commons/jxpath/JXPathFilteredBeanInfo.java`
> and `JXPathIntrospector.registerFilteredClass(Class, String[])`
> Test: `commons-jxpath/src/test/java/org/apache/commons/jxpath/JXPathFilteredBeanInfoTest.java`
> See section 5b for the measured result.

`JXPathIntrospector.findInformant` (lines 167-187) looks for a class named
`<beanClass>XBeanInfo`. Writing an `org.exolab.castor.xml.schema.SchemaXBeanInfo` would work, but
it would force a compile-time dependency between Castor and JXPath. The SEEBURGER JXPath fork
therefore gained a **generic, reusable** mechanism instead, so no extra class is needed anywhere:

```java
// once, at engine start-up
JXPathIntrospector.registerFilteredClass(
        org.exolab.castor.xml.schema.Schema.class,
        new String[] {"builtInTypeName"});
```

`JXPathFilteredBeanInfo` extends `JXPathBasicBeanInfo` and removes the named properties from
`getPropertyDescriptors()`. Because `JXPathBasicBeanInfo.getPropertyDescriptor(String)` resolves
names against that (overridden, virtual) method, the hidden properties disappear from both
lookups. Everything else behaves exactly as before.

**F3b - register `Schema` as atomic.** One line at engine startup:

```java
JXPathIntrospector.registerAtomicClass(org.exolab.castor.xml.schema.Schema.class);
```

Cheapest possible fix, but it makes `Schema` a **leaf** for JXPath, so only use it if no
expression needs to descend *into* a `Schema` node. It also incidentally breaks the cyclic
`Schema -> ElementDecl -> Schema` traversal. F3a is preferred because it is surgical.

**F3c - add a non-indexed companion getter in Castor.**

```java
public String[] getBuiltInTypeName() {          // makes pd.getReadMethod() != null
    return _typeNamesByCode.clone();
}
```

`ValueUtils.getIndexedPropertyLength` then takes the fast branch at lines 110-112 and returns
about 101 immediately. **Caveat:** the array becomes visible to JXPath as child nodes, which
*changes XPath results*. Not recommended now that F3a exists.

### F4 - Harden `commons-jxpath` (benefits every consumer)

In `ValueUtils.getIndexedPropertyLength`:

1. **Memoize the outcome** per `Class` plus property name in a `ConcurrentHashMap`, so the
   16000-iteration probe happens at most **once per class and property per JVM**, not once per
   scan. This is especially valuable because `PropertyIterator.getLength()` swallows the failure
   and never caches the `0`; see section 4.2b.
2. **Treat a run of `null` returns as end-of-collection**, for example stop after 32 consecutive
   `null`s. This restores O(size) behaviour for getters that return `null` instead of throwing, a
   very common real-world pattern.
3. **Make `UNKNOWN_LENGTH_MAX_COUNT` configurable** via a system property, defaulting far below
   16000.
4. Replace `new Integer(i)` (line 122) with `Integer.valueOf(i)` to hit the `IntegerCache`.
5. Consider *logging* at debug level when a probe runs to exhaustion; today the condition is
   completely invisible.

### F5 - Application and expression level

| Action | Why |
|---|---|
| **Do not expose Castor schema objects to JXPath.** Evaluate against a DOM `Document` (JXPath DOM pointer factory) or a plain POJO/`Map` snapshot. | No Castor getter is ever invoked reflectively; eliminates the whole class of problems. |
| **Avoid `//` (descendant axis)** in `CustomUtilFunctions.postEvaluateXPath`; use explicit or absolute paths. | `DescendantContext` plus `getLength()` over a *cyclic* graph is the expensive combination. |
| **Cache compiled expressions** via `JXPathContext.compile(...)`. | Removes re-parsing per `<assign><copy>` execution. |
| Cache `Schema.getBuiltInTypeName` results in a `ConcurrentHashMap` in the calling layer. | Fallback if Castor cannot be patched. F1 is strictly better and cheaper. |

## 5a. F1 - implementation result

### What was changed

`SimpleTypesFactory.java` (post-patch line numbers):

- added `private static volatile String[] _typeNamesByCode` (line 165), the lock-free lookup
  table;
- `getBuiltInTypeName(int)` (line 246) is now a bounds check plus an array read, with no boxing
  and no locking;
- added `rebuildTypeNameTable()` (line 414), invoked at the end of the already-`synchronized`
  `loadTypesDefinitions()` (line 469); the finished array is handed to readers by a single
  volatile write;
- `getType(int)` (line 403) was kept, now package private, as the reference implementation so the
  test can assert that both lookups stay equivalent.

### The test

`SimpleTypesFactoryLockFreeLookupTest` (JUnit 4, package `org.exolab.castor.xml.schema`):

| Test | Purpose |
|---|---|
| `schemaExposesBuiltInTypeNameAsUnboundedIndexedProperty` | Pins the root cause: `Introspector` reports `builtInTypeName` as an `IndexedPropertyDescriptor` whose `getReadMethod()` is `null`, and the getter returns `null` instead of throwing. |
| `lockFreeLookupIsEquivalentToHashtableLookup` | Codes `-1024` to `16000` must yield identical results from the array and from the `Hashtable`. |
| `lockFreeLookupResolvesKnownTypeCodes` | Spot checks (`string`, `integer`, `boolean`, `anySimpleType`) plus `null` for `USER_TYPE`, `INVALID_TYPE`, `Integer.MIN_VALUE`, `Integer.MAX_VALUE`. |
| `lockFreeLookupSurvivesFactoryReinitialisation` | The table stays valid when another `SimpleTypesFactory` is constructed. |
| `lockFreeLookupRemovesMonitorContention` | Replays the 16000-call probe from *N* threads and compares both paths via `ThreadInfo.getBlockedCount()` and wall clock. |

The decisive assertion is **timing-independent**: the F1 path must block **exactly zero** times,
because it contains no `synchronized` region at all.

### Measured (20 workers, 3200000 lookups per run, JDK 8)

```
--- F1: SimpleTypesFactory.getBuiltInTypeName(int) --------------------
  workers .................. 20
  lookups per run .......... 3200000
  before (Hashtable) ....... 158,576 ms, blocked on monitor 140109 times
  after  (lock-free array) . 0,412 ms, blocked on monitor 0 times
  speed-up ................. 384,9x
-----------------------------------------------------------------------
OK (5 tests)
```

**140109 monitor blocks became 0**, and the lookup is about 385 times faster.

> **Note on the surrounding build:** `mvn -pl schema -am` currently fails in this workspace
> because `org.codehaus.mojo:castor-maven-plugin:3.0-SNAPSHOT` cannot be resolved from the
> configured repositories. This is pre-existing and unrelated to this change. The test was
> therefore compiled and executed directly against the locally available
> `castor-xml-schema` / `castor-xml` / `castor-core` 1.4.1 jars, with the patched
> `SimpleTypesFactory` shadowing the released class.

## 5b. F3a - implementation result

### What was changed

`commons-jxpath` (SEEBURGER fork, `com.seeburger.as:commons-jxpath`, branch `1.4-seeburger`):

- **new** `org.apache.commons.jxpath.JXPathFilteredBeanInfo`, a `JXPathBasicBeanInfo` that hides
  a configured set of property names. Immutable, thread-safe, lazily filtered and cached, returns
  defensive copies like its superclass;
- **new** `JXPathIntrospector.registerFilteredClass(Class, String[])`, which registers such a
  bean info for a class.

No Castor change and no `XBeanInfo` class are required; the application calls
`registerFilteredClass` once at start-up.

### The test

`JXPathFilteredBeanInfoTest` (JUnit 3, matching the existing test style of the project). The
fixtures reproduce the exact Castor shape **without depending on Castor**: a bean with
`String getBuiltInTypeName(int)`, no array getter, returning `null` instead of throwing. The
invocation counter is `static`, so it is not itself a JavaBeans property and stays invisible to
JXPath.

| Test | Purpose |
|---|---|
| `testUnfilteredBeanRunsTheFullIndexedProbeSilently` | Demonstrates the problem: `//*` succeeds, returns the right 2 values, logs nothing, and invokes the indexed getter 96000 times. |
| `testFilteredBeanNeverInvokesTheHiddenGetter` | Same traversal, same result, **0** invocations. |
| `testFilteringKeepsTheRemainingPropertiesIntact` | `/name`, `/version`, `//name` still resolve. |
| `testRegisteredFilteredBeanInfoIsReturnedByIntrospector` | `getBeanInfo` returns the filtered info; the hidden property does not resolve. |
| `testExcludedPropertyIsRemovedFromDescriptors` | Descriptor array and by-name lookup agree; `isAtomic` and `isDynamic` unchanged. |
| `testPropertyDescriptorsAreDefensivelyCopied` | Callers cannot corrupt the cached descriptors. |
| `testUnknownExcludedPropertyIsHarmless` | Filtering an unknown name is a no-op. |
| `testConstructorRejectsInvalidArguments` | `null`, empty and `null`-element arguments are rejected. |

### Measured

```
F3a: '//*' over an unfiltered bean invoked the indexed getter 96000 times (6 full probes), silently.
F3a: '//*' over a filtered bean invoked the indexed getter 0 times.

Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
```

Full `commons-jxpath` suite after the change: **Tests run: 393, Failures: 0, Errors: 0, BUILD
SUCCESS.** No regression.

### Application rollout for F3a

```java
// once, during engine start-up, BEFORE the first XPath evaluation
JXPathIntrospector.registerFilteredClass(
        org.exolab.castor.xml.schema.Schema.class,
        new String[] {"builtInTypeName"});
```

Caveats, both documented on the method:

- registration must happen before the class is introspected for the first time, because
  `JXPathIntrospector.getBeanInfo` caches whatever it computed first;
- the registration applies to the **exact** class, not to subclasses, which is the same semantics
  as the existing `registerAtomicClass`.

## 5c. F2 - implementation result

### What was changed

`SimpleTypesFactory.java`:

- `_typesByName` and `_typesByCode` are now `static final ConcurrentMap` backed by
  `ConcurrentHashMap` instead of `Hashtable`, so every read is lock-free;
- added `INIT_LOCK` plus a `volatile boolean _typesLoaded`, and a new `ensureTypesLoaded()` that
  performs double-checked locking;
- the constructor calls `ensureTypesLoaded()` instead of `loadTypesDefinitions()` directly;
- `loadTypesDefinitions()` lost its `synchronized` modifier, because it is now only ever reached
  while `INIT_LOCK` is held.

If loading throws, `_typesLoaded` stays `false` and the next construction retries, which is
exactly what the previous code did.

### Two latent bugs this fixed

The javadoc of `loadTypesDefinitions()` has always claimed *"Loading is done only once"*, but
nothing enforced it. That gap caused two real defects:

1. **Shared instances were silently swapped.** Every `new SimpleTypesFactory()` re-unmarshalled
   the type list and re-put freshly created `Type` and `SimpleType` objects into the static maps.
   Any schema still holding a reference to a built-in `SimpleType` was left pointing at an object
   the factory no longer knew about, so identity comparisons quietly started failing.
2. **The lock did not lock anything.** `loadTypesDefinitions()` was `private synchronized`, which
   locks the *instance*, while all the state it mutates is *static*. Two threads constructing two
   different factories therefore shared no lock at all: they mutated the static maps concurrently
   while `rebuildTypeNameTable()` iterated them. With the old `Hashtable` that iteration is
   fail-fast, so a `ConcurrentModificationException` was possible.

### The test

`SimpleTypesFactoryInitialisationTest` (JUnit 4, package `org.exolab.castor.xml.schema`):

| Test | Purpose |
|---|---|
| `definitionsAreLoadedOnlyOnce` | Deterministic core assertion: constructing another factory must not replace the shared `Type`, `SimpleType` or type-name instances. |
| `repeatedConstructionIsCheap` | Construction must not re-enter the XML load path (bounded at 100 us per construction). |
| `concurrentConstructionIsSafeAndLoadsOnce` | Reproduces the old instance-lock race: many threads construct factories at once and verify the shared state never changes. |
| `builtInTypeLookupByNameIsLockFree` | `getBuiltInType(String)` resolved concurrently must block on a monitor exactly **0** times. |
| `nameLookupBehaviourIsUnchanged` | The map swap must not change lookup results, including `null` for unknown names. |

### Measured (20 workers, JDK 11)

Running the same test class against the pre-F2 code (extracted from `HEAD`) and against the
patched code:

```
                                       before F2          after F2
  construction .................  4 236 658 ns          39 ns      ~108 000x
  getBuiltInType(String), 4 000 000 lookups
      elapsed ..................    202,800 ms       8,847 ms          ~22,9x
      blocked on monitor .......       41 974               0
```

Pre-F2, **4 of the 5 tests fail**, which confirms they are testing something real:

```
1) concurrentConstructionIsSafeAndLoadsOnce
   java.lang.AssertionError: concurrent construction failed 20 times,
   first: java.lang.IllegalStateException: shared SimpleType instance was replaced
2) repeatedConstructionIsCheap
   constructing a factory must not reload the type definitions, but took 4236658 ns
3) definitionsAreLoadedOnlyOnce
   constructing another factory must not replace the shared Type instance
   expected same:<name: string code: STRING_TYPE ...> was not:<name: string code: STRING_TYPE ...>
4) builtInTypeLookupByNameIsLockFree
   the ConcurrentHashMap lookup must never block on a monitor expected:<0> but was:<41974>
```

After F2, the full set of F1 and F2 tests passes:

```
--- F2: SimpleTypesFactory construction ------------------------------
  constructions ............ 500
  per construction ......... 39 ns
--- F2: SimpleTypesFactory.getBuiltInType(String) --------------------
  workers .................. 20
  lookups .................. 4000000
  elapsed .................. 8,847 ms
  blocked on monitor ....... 0 times
--- F1: SimpleTypesFactory.getBuiltInTypeName(int) --------------------
  before (Hashtable) ....... 147,275 ms, blocked on monitor 116756 times
  after  (lock-free array) . 0,307 ms, blocked on monitor 0 times
  speed-up ................. 479,6x
OK (10 tests)
```

### Knock-on change to the F1 test

`SimpleTypesFactoryLockFreeLookupTest` used the live `getType(int)` as its "before" baseline. F2
made that path lock-free too, which would have turned the baseline into a second "after"
measurement and silently invalidated the comparison. The test now keeps its own `Hashtable`
replica of the pre-F1 storage, so it remains an honest record of what F1 improved and is immune to
further changes to the live field.

### F2c - deferred: the `Schema` fields

Item 3 of F2 (converting the nine per-instance `Hashtable` fields of `Schema`, lines 108-194) has
**not** been carried out. Rationale:

- those monitors are *per `Schema` instance*, not process-wide, so they were never the bottleneck
  in `dump2.txt`;
- `Schema` is a 2198-line class with many call sites, and one field (`_simpleTypes`) is declared
  with the concrete `Hashtable` type and used through the legacy `elements()` API;
- the change cannot be validated here, because the Maven build of this workspace does not run (see
  the note in 5a). Making an unverifiable change to a large class carries more risk than the
  contention it would remove.

It should be done together with a working build and the full schema test suite.

## 6. Recommended rollout

| Step | Change | Scope | Risk | Status | Expected effect |
|---|---|---|---|---|---|
| 1 | **F1**, array cache for `getBuiltInTypeName` | `SimpleTypesFactory` (about 20 LOC) | **None** | **done**, commit `8fa4a3f3a` (5a) | Monitor `0x...e973c7c8` off the hot path; contention 140109 -> **0**. |
| 2 | **F3a**, hide `builtInTypeName` from JXPath | JXPath fork plus 1 start-up line | Low | **done**, commit `5ed7f7f4` (5b) | Reflective probe 96000 -> **0** invocations. |
| 3 | **F2**, `ConcurrentHashMap` plus single init | `SimpleTypesFactory` | Low | **done**, working tree (5c) | Name lookups lock-free (41974 -> **0** blocks); construction ~108000x cheaper; two latent bugs fixed. |
| 3b | **F2c**, de-`Hashtable` the `Schema` fields | `Schema` | Low | deferred (5c) | Removes the remaining per-instance lock hot spots during schema parsing. |
| 4 | **F4**, JXPath hardening and memoization | `commons-jxpath` fork | Medium | open | Protects against the next getter with the same shape. |
| 5 | **F5**, expression and model redesign | Engine | Medium/High | open | Structural fix; biggest long-term win. |

Steps 1 and 2 are independent and complementary: F1 makes the getter cheap and lock-free, F3a
stops it from being called at all.

### Outstanding integration work

F1 and F3a are committed in the libraries, but the benefit only reaches production once:

1. the patched `castor` artifact is released and the engine picks up the new version;
2. the patched `commons-jxpath` fork is released and the engine picks up the new version;
3. the engine start-up code calls `JXPathIntrospector.registerFilteredClass(...)` as shown in 5b.

Step 3 is the only application-side code change required.

## 7. Verification

**Before the fix**, a second thread dump taken about 10 seconds later shows a **different lock
owner but the same queue** on `0x00000003e973c7c8`. That confirms contention (a convoy) rather
than a hang or deadlock.

**After the fix**, verify:

1. No thread dump contains `SimpleTypesFactory.getType` in a `BLOCKED` state.
2. `-XX:+PrintConcurrentLocks` or JFR `jdk.JavaMonitorEnter` events show no hot monitor on
   `SimpleTypesFactory`.
3. Async-profiler shows `ValueUtils.getIndexedPropertyLength` below noise level, and `Integer`
   boxing allocations from that frame at zero.
4. **Do not look for a log message.** The `JXPathException` raised by the exhausted probe is
   swallowed by `PropertyIterator.getLength()` and dropped by `NodePointer.handle()`
   (section 4.2b), so the condition is invisible in logs both before *and* after the fix. Use the
   profiler or a temporary `JXPathContext.setExceptionHandler(...)` instead.
5. Regression: XPath results of the affected BPEL `<assign>` activities are unchanged.

## 8. Source reference index

Line numbers refer to the **original, pre-fix** files unless stated otherwise, so that they line
up with the thread dump.

| File | Line(s) | Relevance |
|---|---|---|
| `castor/schema/.../SimpleTypesFactory.java` | 143, 148 | static `Hashtable` fields, the contended monitor |
| `castor/schema/.../SimpleTypesFactory.java` | 157, 379 | load-on-construct, `synchronized` init |
| `castor/schema/.../SimpleTypesFactory.java` | 221-227 | `getBuiltInTypeName(int)`, returns `null`, never throws |
| `castor/schema/.../SimpleTypesFactory.java` | 371-373 | `getType(int)`, the `Hashtable.get` call |
| `castor/schema/.../SimpleTypesFactory.java` | 165, 246, 403, 414, 469 (post-F1) | lookup table, lock-free getter, reference getter, rebuild, call site |
| `castor/schema/.../SimpleTypesFactory.java` | 149, 160, 188, 195, 208, 221, 499 (post-F2) | `ConcurrentMap` fields, `INIT_LOCK`, `_typesLoaded`, constructor, `ensureTypesLoaded()`, de-`synchronized` load |
| `castor/schema/.../Schema.java` | 94 | `static SimpleTypesFactory`, one per JVM |
| `castor/schema/.../Schema.java` | 866-868 | indexed-getter-shaped public API |
| `castor/schema/.../Schema.java` | 108-114 | further `Hashtable` fields |
| `castor/schema/.../{XMLType,ElementDecl,AttributeDecl,ModelGroup,AttributeGroupDecl,Wildcard}.java` | `getSchema()` | makes `Schema` reachable and the graph cyclic |
| `commons-jxpath/.../util/ValueUtils.java` | 45 | `UNKNOWN_LENGTH_MAX_COUNT = 16000` |
| `commons-jxpath/.../util/ValueUtils.java` | 108-132 | the probe loop |
| `commons-jxpath/.../ri/model/beans/BeanPropertyPointer.java` | 195-212 | `getLength()` routes indexed properties into the probe |
| `commons-jxpath/.../ri/model/beans/PropertyIterator.java` | 139, 206, 311-325 | `getLength()` per property per bean; **swallows** the exception |
| `commons-jxpath/.../ri/model/NodePointer.java` | 818-835 | `handle(Throwable)` drops it when no handler is installed |
| `commons-jxpath/.../JXPathIntrospector.java` | 69-71, 107-120, 167-187 | registration hooks; `registerFilteredClass` added here |
| `commons-jxpath/.../JXPathFilteredBeanInfo.java` | all | **F3a** implementation |

