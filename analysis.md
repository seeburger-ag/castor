# Thread Dump Analysis ? JXPath / Castor Blocking (`dump2.txt`)

_Analysis date: 2026-09-22 · updated 2026-09-23 · JVM: Java 21.0.12 · Container: Apache Karaf / Felix_
_Sources inspected: `commons-jxpath` (SEEBURGER fork) and `castor` (this repository)_
_Thread dump analysed: `dump2.txt` (kept in the `commons-jxpath` working copy)_

## 1. Summary

All JXPath-related threads in `dump2.txt` are blocked on **one single monitor**:

```
<0x00000003e973c7c8> (a java.util.Hashtable)
```

That `Hashtable` is `org.exolab.castor.xml.schema.SimpleTypesFactory._typesByCode` ? a
**`static`** field, i.e. exactly one instance per classloader, guarded by `Hashtable`'s
intrinsic lock.

This is **monitor contention (a lock convoy), not a deadlock**. The lock owner has the same
stack as the waiters and is making progress; all other worker threads serialize behind it.

| Item | Value |
|---|---|
| Contended monitor | `0x00000003e973c7c8` (`java.util.Hashtable`) |
| Real identity of the monitor | `SimpleTypesFactory._typesByCode` (**static**) |
| Lock owner | `dump2.txt:7244` (`- locked ?`) ? same JXPath/Castor code path |
| Blocked threads | 20+ BPEL/JMS workers |
| Blocked thread lines | 2964, 6119, 7318, 7394, 7470, 7546, 7622, 7698, 7774, 7967, 8043, 8119, 8195, 8271, 8347, 8423, 8499, 8575, 8651, 8727, ? |
| Deadlock | **No** |
| Amplification | **16 000 locked lookups per length probe**, probe repeated per node scan (see ?4) |

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
> `Schema.getBuiltInTypeName` and `Method.invoke` ? only JDK reflection glue. JXPath calls the
> Castor method **directly by reflection**.

## 3. Castor source evidence

### 3.1 `SimpleTypesFactory` ? static `Hashtable` lookups

`castor/schema/src/main/java/org/exolab/castor/xml/schema/SimpleTypesFactory.java`

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
| C1 | `_typesByCode` / `_typesByName` are **`static` `Hashtable`s** ? `get()` is `synchronized` on a single JVM-wide object. | Global serialization point for *all* threads doing schema work. |
| C2 | `getBuiltInTypeName(int)` **returns `null`** instead of throwing for out-of-range codes. | Defeats JXPath's loop-termination contract (?4) ? 16 000 iterations instead of ~45. |
| C3 | `loadTypesDefinitions()` runs in the **constructor**, re-unmarshalling `SimpleTypes.properties` and re-`put`-ing into the *static* maps on every `new SimpleTypesFactory()`. | Wasted work; concurrent structural mutation of shared static state. |
| C4 | Type codes are a small, fixed range (`-1` ? `ANYSIMPLETYPE_TYPE = 100`) yet are looked up via boxed `Integer` keys in a hash map. | A plain array lookup would be O(1) **and** lock-free. |

### 3.2 `Schema` ? the reflection entry point

`castor/schema/src/main/java/org/exolab/castor/xml/schema/Schema.java`

```java
// line 94  -- one factory for the whole JVM
private static SimpleTypesFactory simpleTypesFactory = new SimpleTypesFactory();

// line 866 -- signature `String getXxx(int)` == JavaBeans INDEXED GETTER pattern
public String getBuiltInTypeName(int builtInTypeCode) {
    return simpleTypesFactory.getBuiltInTypeName(builtInTypeCode);
}
```

`Schema` additionally uses `Hashtable` for `_attributeGroups`, `_attributes`, ? (lines 108?114).
Those are *per-instance* locks and contribute far less, but are the same anti-pattern.

### 3.3 `Schema` is reachable from almost every schema node

`public Schema getSchema()` exists on `XMLType.java:166`, `ElementDecl.java:400`,
`AttributeDecl.java:292`, `ModelGroup.java:356`, `AttributeGroupDecl.java:218`,
`Wildcard.java:182`.

So a `//` (descendant) traversal reaches a `Schema` node from **every** element, attribute, type
and group it visits ? and the object graph is **cyclic** (`Schema ? ElementDecl ? Schema ? ?`).

### 3.4 Other `getXxx(int)` methods (benign)

`Group.getParticle(int)`, `ContentModelGroupImpl.getParticle(int)` (line 309) and
`ComplexType.getParticle(int)` delegate to `Vector.elementAt(index)`, which **does** throw
`ArrayIndexOutOfBoundsException`. JXPath's probe loop therefore terminates correctly after
`size()+1` calls ? inefficient, but not pathological.

## 4. Root cause ? the pathological interaction

### 4.1 JavaBeans mis-detection

`java.beans.Introspector` sees `String Schema.getBuiltInTypeName(int)` and classifies
`builtInTypeName` as an **indexed property** with:

- indexed read method = `getBuiltInTypeName(int)`
- **non-indexed read method = `null`** (there is no `String[] getBuiltInTypeName()`)

### 4.2 JXPath's "guess the length" fallback

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

The loop's **only** normal exit is an exception from the getter. Because Castor's
`getBuiltInTypeName` returns `null` instead of throwing (finding **C2**), the loop runs the
**full 16 000 iterations** and then throws `JXPathException`.

`BeanPropertyPointer.getLength()` (lines 195?205) routes every `IndexedPropertyDescriptor` into
that method, and `PropertyIterator.setPositionAllProperties` (line 206) calls `getLength()` for
**every property of every bean** the descendant axis visits.

### 4.2b The cost is completely silent ? and repeated

`PropertyIterator.getLength()` (lines 311?325) is documented as *"Computes length for the current
pointer - ignores any exceptions"*:

```java
private int getLength() {
    int length;
    try {
        length = propertyNodePointer.getLength();     // -> the 16 000-call probe
    }
    catch (Throwable t) {
        propertyNodePointer.handle(t);                // -> dropped, see below
        length = 0;
    }
    return length;
}
```

and `NodePointer.handle(Throwable)` (lines 818?835) **discards** the throwable when no
`ExceptionHandler` is installed ? which is the default. Consequences:

- the XPath expression **succeeds** and returns a correct result;
- **nothing is logged** ? there is no `"Cannot determine the length of the indexed property"`
  message anywhere;
- the only symptom is burnt CPU and, in production, the monitor the getter happens to acquire;
- the `0` length is never cached, so the probe runs **again** on the next scan.

Measured in `JXPathFilteredBeanInfoTest` with a single bean carrying one such property, one
`//*` evaluation invoked the indexed getter **96 000 times = 6 full probes**.

### 4.3 Cost model

```
per length probe
  = 16 000 ? ( reflective Method.invoke
             + Integer boxing
             + synchronized Hashtable.get on ONE static monitor )
```

With `P` probes per visited node, `N` nodes visited by the `//` axis, `K` evaluations per process
instance and `M` concurrent BPEL threads:

```
locked Hashtable.get calls ? 16 000 ? P ? N ? K ? M
```

All of them queue on the *same* monitor. Throughput collapses; threads look "hung" but are merely
waiting in line.

### 4.4 Contributing factors

1. **`DescendantContext.nextNode`** ? the expression uses the `//` descendant axis, so the whole
   (cyclic) Castor schema object graph is walked.
2. **`CoreFunction.functionStartsWith` + `CoreOperationCompare.equal`** re-evaluate the location
   path per candidate node.
3. The expression is evaluated **per `<assign><copy>` per process instance**, on many concurrent
   JMS correlation threads (`ProcessCorrelationBase.processMsg`).

## 5. Improvement plan

Five independent layers. **F1 + F3 together remove the bottleneck completely**; each can be
shipped on its own.

### F1 ? Cache `getBuiltInTypeName` in Castor _(primary target: highest value, lowest risk)_

> **Status: IMPLEMENTED & VERIFIED.**
> Code: `castor/schema/src/main/java/org/exolab/castor/xml/schema/SimpleTypesFactory.java`
> Test: `castor/schema/src/test/java/org/exolab/castor/xml/schema/SimpleTypesFactoryLockFreeLookupTest.java`
> See ?5a for the measured result.

Built-in type codes are a **fixed, small integer range** (`INVALID_TYPE = -1` ?
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
the 16 000-iteration loop still runs (F3 not yet applied), it degenerates to 16 000 array reads ?
microseconds, fully parallel, zero contention.

**Risk:** none functionally. `getBuiltInTypeName` keeps returning `null` for unknown codes and
the same `String` instances for known ones; behaviour is byte-for-byte identical.

The call site is the end of the population loop in `loadTypesDefinitions()` (lines 403?407 of the
original file):

```java
    for (Type type : typeList.getTypes()) {
        _typesByName.put(type.getName(), type);
        type.setSimpleType(createSimpleType(BUILD_IN_SCHEMA, type));
        _typesByCode.put(Integer.valueOf(type.getSimpleType().getTypeCode()), type);
    }
    rebuildTypeNameTable();          // <-- ADDED
```

**Variant F1b** ? if touching the load path is undesirable, put a lazily populated
`ConcurrentHashMap<Integer, String>` (or `AtomicReferenceArray`) in front of `getType(int)`.
Still lock-free, but slower and more code than the array. Not recommended; the array was chosen.

### F2 ? De-`Hashtable` and single-init Castor _(structural hygiene)_

1. `_typesByName` / `_typesByCode` ? `ConcurrentHashMap`. This also relieves the
   `getBuiltInType(String)` / `getSimpleType(...)` paths, which sit on the same monitor whenever
   a schema is parsed.
2. Guard `loadTypesDefinitions()` so it runs **once**:

```java
private static volatile boolean _initialized;

public SimpleTypesFactory() {
    if (!_initialized) { loadTypesDefinitions(); }
}

private synchronized void loadTypesDefinitions() {
    if (_initialized) { return; }
    ...
    rebuildTypeNameTable();
    _initialized = true;
}
```

   Better still: move the whole initialization into a static holder class, so the JVM's class
   initialization lock does the work exactly once and the `synchronized` method can be dropped.
3. Same treatment for `Schema`'s per-instance `Hashtable` fields (lines 108?114).

**Risk:** low. Keep `Map` as the declared type so no call site changes. Note `ConcurrentHashMap`
rejects `null` keys/values ? currently impossible here, since `createSimpleType` throws instead.

> ? **Class-initialization ordering (discovered while testing F1).** `Schema` and
> `SimpleTypesFactory` initialise each other: `Schema.<clinit>` creates the shared
> `SimpleTypesFactory`, whose `<clinit>` creates `BUILD_IN_SCHEMA = new Schema()`. If
> `SimpleTypesFactory` is the *first* class touched, `BUILD_IN_SCHEMA` is still `null` when the
> constructor runs, and initialization fails with
> `"'schema' must not be null"`. Any refactoring of F2 must preserve "Schema first".

### F3 ? Stop JXPath from probing the property at all

**F3a ? hide the property from JXPath (implemented).**

> **Status: IMPLEMENTED & VERIFIED.**
> Code: `commons-jxpath/src/main/java/org/apache/commons/jxpath/JXPathFilteredBeanInfo.java`
> and `JXPathIntrospector.registerFilteredClass(Class, String[])`
> Test: `commons-jxpath/src/test/java/org/apache/commons/jxpath/JXPathFilteredBeanInfoTest.java`
> See ?5b for the measured result.

`JXPathIntrospector.findInformant` (lines 167?187) looks for a class named
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

**F3b ? register `Schema` as atomic.** One line at engine startup:

```java
JXPathIntrospector.registerAtomicClass(org.exolab.castor.xml.schema.Schema.class);
```

Cheapest possible fix, but it makes `Schema` a **leaf** for JXPath ? only use it if no expression
needs to descend *into* a `Schema` node. It also incidentally breaks the cyclic
`Schema ? ElementDecl ? Schema` traversal. F3a is preferred because it is surgical.

**F3c ? add a non-indexed companion getter in Castor.**

```java
public String[] getBuiltInTypeName() {          // makes pd.getReadMethod() != null
    return _typeNamesByCode.clone();
}
```

`ValueUtils.getIndexedPropertyLength` then takes the fast branch at lines 110?112 and returns
~101 immediately. **Caveat:** the array becomes visible to JXPath as child nodes, which *changes
XPath results*. Not recommended now that F3a exists.

### F4 ? Harden `commons-jxpath` (benefits every consumer)

In `ValueUtils.getIndexedPropertyLength`:

1. **Memoize the outcome** per `Class` + property name in a `ConcurrentHashMap`, so the
   16 000-iteration probe happens at most **once per class+property per JVM**, not once per
   scan. (Especially valuable because `PropertyIterator.getLength()` swallows the failure and
   never caches the `0`; see ?4.2b.)
2. **Treat a run of `null` returns as end-of-collection** (e.g. stop after 32 consecutive
   `null`s). Restores O(size) behaviour for getters that return `null` instead of throwing ? a
   very common real-world pattern.
3. **Make `UNKNOWN_LENGTH_MAX_COUNT` configurable** (system property), defaulting far below
   16 000.
4. Replace `new Integer(i)` (line 122) with `Integer.valueOf(i)` to hit the `IntegerCache`.
5. Consider *logging* (at debug level) when a probe runs to exhaustion ? today the condition is
   completely invisible.

### F5 ? Application / expression level

| Action | Why |
|---|---|
| **Do not expose Castor schema objects to JXPath.** Evaluate against a DOM `Document` (JXPath DOM pointer factory) or a plain POJO/`Map` snapshot. | No Castor getter is ever invoked reflectively; eliminates the whole class of problems. |
| **Avoid `//` (descendant axis)** in `CustomUtilFunctions.postEvaluateXPath`; use explicit/absolute paths. | `DescendantContext` + `getLength()` over a *cyclic* graph is the expensive combination. |
| **Cache compiled expressions** via `JXPathContext.compile(...)`. | Removes re-parsing per `<assign><copy>` execution. |
| Cache `Schema.getBuiltInTypeName` results in a `ConcurrentHashMap` in the calling layer. | Fallback if Castor cannot be patched ? F1 is strictly better and cheaper. |

## 5a. F1 ? implementation result

### What was changed

`SimpleTypesFactory.java`:

- added `private static volatile String[] _typeNamesByCode` ? the lock-free lookup table;
- `getBuiltInTypeName(int)` is now a bounds check plus an array read (no boxing, no locking);
- added `rebuildTypeNameTable()`, invoked at the end of the already-`synchronized`
  `loadTypesDefinitions()`; the finished array is handed to readers by a single volatile write;
- `getType(int)` was kept (now package private) as the reference implementation, so the test can
  assert that both lookups stay equivalent.

### The test

`SimpleTypesFactoryLockFreeLookupTest` (JUnit 4, `org.exolab.castor.xml.schema`):

| Test | Purpose |
|---|---|
| `schemaExposesBuiltInTypeNameAsUnboundedIndexedProperty` | Pins the root cause: `Introspector` reports `builtInTypeName` as an `IndexedPropertyDescriptor` whose `getReadMethod()` is `null`, and the getter returns `null` instead of throwing. |
| `lockFreeLookupIsEquivalentToHashtableLookup` | Codes `-1024 ? 16000` must yield identical results from the array and from the `Hashtable`. |
| `lockFreeLookupResolvesKnownTypeCodes` | Spot checks (`string`, `integer`, `boolean`, `anySimpleType`) plus `null` for `USER_TYPE`, `INVALID_TYPE`, `Integer.MIN_VALUE`, `Integer.MAX_VALUE`. |
| `lockFreeLookupSurvivesFactoryReinitialisation` | The table stays valid when another `SimpleTypesFactory` is constructed. |
| `lockFreeLookupRemovesMonitorContention` | Replays the 16 000-call probe from *N* threads and compares both paths via `ThreadInfo.getBlockedCount()` and wall clock. |

The decisive assertion is **timing-independent**: the F1 path must block **exactly zero** times,
because it contains no `synchronized` region at all.

### Measured (20 workers, 3 200 000 lookups per run, JDK 8)

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

**140 109 monitor blocks ? 0**, and ~385? faster.

> **Note on the surrounding build:** `mvn -pl schema -am` currently fails in this workspace
> because `org.codehaus.mojo:castor-maven-plugin:3.0-SNAPSHOT` cannot be resolved from the
> configured repositories (pre-existing, unrelated to this change). The test was therefore
> compiled and executed directly against the locally available
> `castor-xml-schema/castor-xml/castor-core 1.4.1` jars, with the patched `SimpleTypesFactory`
> shadowing the released class.

## 5b. F3a ? implementation result

### What was changed

`commons-jxpath` (SEEBURGER fork, `com.seeburger.as:commons-jxpath`):

- **new** `org.apache.commons.jxpath.JXPathFilteredBeanInfo` ? a `JXPathBasicBeanInfo` that hides
  a configured set of property names. Immutable, thread-safe, lazily filtered and cached, returns
  defensive copies like its superclass;
- **new** `JXPathIntrospector.registerFilteredClass(Class, String[])` ? registers such a bean info
  for a class.

No Castor change and no `?XBeanInfo` class are required; the application calls
`registerFilteredClass` once at start-up.

### The test

`JXPathFilteredBeanInfoTest` (JUnit 3, matching the project's existing test style). The fixtures
reproduce the exact Castor shape **without depending on Castor**: a bean with
`String getBuiltInTypeName(int)`, no array getter, returning `null` instead of throwing. The
invocation counter is `static`, so it is not itself a JavaBeans property and stays invisible to
JXPath.

| Test | Purpose |
|---|---|
| `testUnfilteredBeanRunsTheFullIndexedProbeSilently` | Demonstrates the problem: `//*` succeeds, returns the right 2 values, logs nothing ? and invokes the indexed getter 96 000 times. |
| `testFilteredBeanNeverInvokesTheHiddenGetter` | Same traversal, same result, **0** invocations. |
| `testFilteringKeepsTheRemainingPropertiesIntact` | `/name`, `/version`, `//name` still resolve. |
| `testRegisteredFilteredBeanInfoIsReturnedByIntrospector` | `getBeanInfo` returns the filtered info; hidden property does not resolve. |
| `testExcludedPropertyIsRemovedFromDescriptors` | Descriptor array and by-name lookup agree; `isAtomic`/`isDynamic` unchanged. |
| `testPropertyDescriptorsAreDefensivelyCopied` | Callers cannot corrupt the cached descriptors. |
| `testUnknownExcludedPropertyIsHarmless` | Filtering an unknown name is a no-op. |
| `testConstructorRejectsInvalidArguments` | `null` / empty / `null`-element rejected. |

### Measured

```
F3a: '//*' over an unfiltered bean invoked the indexed getter 96000 times (6 full probes), silently.
F3a: '//*' over a filtered bean invoked the indexed getter 0 times.

Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
```

Full `commons-jxpath` suite after the change: **Tests run: 393, Failures: 0, Errors: 0 ? BUILD
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
- the registration applies to the **exact** class, not to subclasses (same semantics as the
  existing `registerAtomicClass`).

## 6. Recommended rollout

| Step | Change | Scope | Risk | Status | Expected effect |
|---|---|---|---|---|---|
| 1 | **F1** ? array cache for `getBuiltInTypeName` | `SimpleTypesFactory` (~20 LOC) | **None** | ? **done** (?5a) | Monitor `0x?e973c7c8` off the hot path; contention 140 109 ? **0**. |
| 2 | **F3a** ? hide `builtInTypeName` from JXPath | JXPath fork + 1 start-up line | Low | ? **done** (?5b) | Reflective probe 96 000 ? **0** invocations. |
| 3 | **F2** ? `ConcurrentHashMap` + single init | `SimpleTypesFactory`, `Schema` | Low | open | Removes the remaining Castor lock hot spots during schema parsing. |
| 4 | **F4** ? JXPath hardening + memoization | `commons-jxpath` fork | Medium | open | Protects against the next getter with the same shape. |
| 5 | **F5** ? expression/model redesign | Engine | Medium/High | open | Structural fix; biggest long-term win. |

Steps 1 and 2 are independent and complementary: F1 makes the getter cheap and lock-free, F3a
stops it from being called at all.

## 7. Verification

**Before the fix** ? a second thread dump taken ~10 s later shows a **different lock owner but the
same queue** on `0x00000003e973c7c8`. That confirms contention (convoy) rather than a
hang/deadlock.

**After the fix**, verify:

1. No thread dump contains `SimpleTypesFactory.getType` in a `BLOCKED` state.
2. `-XX:+PrintConcurrentLocks` / JFR `jdk.JavaMonitorEnter` events show no hot monitor on
   `SimpleTypesFactory`.
3. Async-profiler shows `ValueUtils.getIndexedPropertyLength` below noise level, and `Integer`
   boxing allocations from that frame at zero.
4. ? **Do not look for a log message.** The `JXPathException` raised by the exhausted probe is
   swallowed by `PropertyIterator.getLength()` and dropped by `NodePointer.handle()` (?4.2b), so
   the condition is invisible in logs both before *and* after the fix. Use the profiler or a
   temporary `JXPathContext.setExceptionHandler(...)` instead.
5. Regression: XPath results of the affected BPEL `<assign>` activities are unchanged.

## 8. Source reference index

| File | Line(s) | Relevance |
|---|---|---|
| `castor/schema/.../SimpleTypesFactory.java` | 143, 148 | static `Hashtable` fields ? the contended monitor |
| `castor/schema/.../SimpleTypesFactory.java` | 157, 379 | load-on-construct, `synchronized` init |
| `castor/schema/.../SimpleTypesFactory.java` | 221?227 | `getBuiltInTypeName(int)` ? returns `null`, never throws |
| `castor/schema/.../SimpleTypesFactory.java` | 371?373 | `getType(int)` ? the `Hashtable.get` call |
| `castor/schema/.../Schema.java` | 94 | `static SimpleTypesFactory` ? one per JVM |
| `castor/schema/.../Schema.java` | 866?868 | indexed-getter-shaped public API |
| `castor/schema/.../Schema.java` | 108?114 | further `Hashtable` fields |
| `castor/schema/.../{XMLType,ElementDecl,AttributeDecl,ModelGroup,AttributeGroupDecl,Wildcard}.java` | `getSchema()` | makes `Schema` reachable and the graph cyclic |
| `commons-jxpath/.../util/ValueUtils.java` | 45 | `UNKNOWN_LENGTH_MAX_COUNT = 16000` |
| `commons-jxpath/.../util/ValueUtils.java` | 108?132 | the probe loop |
| `commons-jxpath/.../ri/model/beans/BeanPropertyPointer.java` | 195?212 | `getLength()` routes indexed properties into the probe |
| `commons-jxpath/.../ri/model/beans/PropertyIterator.java` | 139, 206, 311?325 | `getLength()` per property per bean; **swallows** the exception |
| `commons-jxpath/.../ri/model/NodePointer.java` | 818?835 | `handle(Throwable)` drops it when no handler is installed |
| `commons-jxpath/.../JXPathIntrospector.java` | 69?71, 107?120, 167?187 | registration hooks; `registerFilteredClass` added here |
| `commons-jxpath/.../JXPathFilteredBeanInfo.java` | all | **F3a** implementation |

