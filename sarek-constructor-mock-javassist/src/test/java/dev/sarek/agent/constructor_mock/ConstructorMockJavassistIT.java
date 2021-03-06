package dev.sarek.agent.constructor_mock;

import dev.sarek.junit4.SarekRunner;
import dev.sarek.test.util.SeparateJVM;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.acme.*;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static dev.sarek.agent.constructor_mock.ConstructorMockRegistry.pollMockInstance;
import static org.junit.Assert.*;

/**
 * This test runs without a Java agent set via command line. It attaches it dynamically after adding it to the
 * bootstrap class loader's search path. The latter is only necessary if we want to mock constructors of classes which
 * are either bootstrap classes themselves or have bootstrap classes in their ancestry (direct or indirect parent
 * classes).
 * <p>
 * Furthermore, the test demonstrates how to retransform an already loaded class (in this case also a bootstrap class)
 * in order to add constructor mock functionality to it. This proves that the constructor mock transformation does not
 * change the class structure but only instruments constructor bodies. I.e. that this is more flexible than e.g.
 * removing 'final' modifiers because the latter change class/method signatures and are not permitted in
 * retransformations, so they have to be done during class-loading.
 */
@Category(SeparateJVM.class)
@RunWith(SarekRunner.class)
//@Ignore("Only working with '-noverify'  because of https://github.com/jboss-javassist/javassist/issues/328")
public class ConstructorMockJavassistIT {
  private static final Instrumentation INSTRUMENTATION = ByteBuddyAgent.install();
  private static final Class<?>[] TRANSFORMATION_TARGETS = {
    Sub.class, Base.class,
    UUID.class,
    SubWithComplexConstructor.class, BaseWithComplexConstructor.class
  };
  private static ConstructorMockJavassistTransformer constructorMockTransformer;

  @BeforeClass
  public static void beforeClass() throws UnmodifiableClassException {

    // Please note: There is no need to add Javassist and this module's JAR to the bootstrap class path, because in
    // contrast to the aspect and mock APIs, the API itself or its dependencies are never used by transformed code. The
    // only thing that must be on the bootstrap class loader is the ConstructorMockRegistry from module
    // 'sarek-constructor-mock', which is already part of the Sarek uber JAR and will be injected via SarekRunner.

//    ConstructorMockJavassistTransformer.LOG_CONSTRUCTOR_MOCK = true;
//    ConstructorMockJavassistTransformer.DUMP_CLASS_FILES = true;

    // Create global transformer without includes list in order to test if there is any problem with transforming
    // additional JRE bootstrap classes with dormant constructor mocking code.
    //
    // Update: After latest changes in ConstructorMockRegistry concerning mock instence queues and indexing classes by
    // Class<?> instance instead of by class name, this error occurs when using a global transformer here:
    //   Exception in thread "main" java.lang.NoClassDefFoundError:
    //   Could not initialize class dev.sarek.agent.constructor_mock.ConstructorMockRegistry
    //     at java.base/java.util.LinkedHashMap$LinkedKeyIterator.<init>(LinkedHashMap.java)
    //     at java.base/java.util.LinkedHashMap$LinkedKeySet.iterator(LinkedHashMap.java:564)
    //     (...)
    //     at dev.sarek.junit4.SarekRunner.run(SarekRunner.java:70)
    //     at org.junit.runner.JUnitCore.run(JUnitCore.java:137)
    //     (...)
    // constructorMockTransformer = new ConstructorMockJavassistTransformer();

    // Alternatively just mock target classes incl. parents
    constructorMockTransformer = new ConstructorMockJavassistTransformer(TRANSFORMATION_TARGETS);

    // Important: set 'canRetransform' parameter to true
    INSTRUMENTATION.addTransformer(constructorMockTransformer, true);
    INSTRUMENTATION.retransformClasses(TRANSFORMATION_TARGETS);
    System.out.println("ConstructorMockRegistry class loader = " + ConstructorMockRegistry.class.getClassLoader());
  }

  @AfterClass
  public static void afterClass() throws UnmodifiableClassException {
    INSTRUMENTATION.removeTransformer(constructorMockTransformer);
    INSTRUMENTATION.retransformClasses(TRANSFORMATION_TARGETS);
    constructorMockTransformer = null;

    ConstructorMockJavassistTransformer.LOG_CONSTRUCTOR_MOCK = false;
    ConstructorMockJavassistTransformer.DUMP_CLASS_FILES = false;
  }

  Base base;
  AnotherSub anotherSub;
  Sub sub;
  ExtendsSub extendsSub;

  private void initialiseSubjectsUnderTest() {
    base = new Base(11);
    sub = new Sub(22, "foo");
    anotherSub = new AnotherSub(33, "bar");
    extendsSub = new ExtendsSub(44, "zot", Date.from(Instant.now()));
  }

  @Test
  public void constructorMockOnApplicationClass() throws UnmodifiableClassException {

    // (1) Before activating constructor mock mode for class Sub, everything is normal

    assertFalse(ConstructorMockRegistry.isMock(Sub.class));
    initialiseSubjectsUnderTest();
    assertEquals(11, base.getId());
    assertEquals(22, sub.getId());
    assertEquals("foo", sub.getName());
    assertEquals(33, anotherSub.getId());
    assertEquals("bar", anotherSub.getName());
    assertEquals(44, extendsSub.getId());
    assertEquals("zot", extendsSub.getName());
    assertNotNull(extendsSub.getDate());

    System.out.println("-----");

    // (2a) Retransform already loaded application class (incl. all ancestors) in order to make constructor mockable
    // TODO: make this easier by just providing Sub and letting the framework take care of the ancestors
    // TODO: Multiple retransformations work fine, buy will byte code be inserted multiple times? -> check & improve
    INSTRUMENTATION.retransformClasses(Sub.class, Base.class);

    // (2b) After activating constructor mock mode for class Sub,
    //   - fields should be uninitialised for Sub instances,
    //   - but not for direct base class instances or siblings in the inheritance hierarchy such as AnotherSub.
    //   - When instantiating child classes of Sub, their own constructors will not be mocked either, but the
    //     parent constructors from Sub upwards (i.e. Sub, Base) will be.

    ConstructorMockRegistry.activate(Sub.class);
    assertTrue(ConstructorMockRegistry.isMock(Sub.class));
    initialiseSubjectsUnderTest();
    // No change in behaviour for base class Base
    assertEquals(11, base.getId());
    // Constructor mock effect for target class Sub
    assertEquals(0, sub.getId());
    assertNull(sub.getName());
    // No change in behaviour for sibling class AnotherSub
    assertEquals(33, anotherSub.getId());
    assertEquals("bar", anotherSub.getName());
    // ExtendsSub extends Sub is unaffected because it is not a target class but only a sub class
    assertEquals(44, extendsSub.getId());
    assertEquals("zot", extendsSub.getName());
    assertNotNull(extendsSub.getDate());

    System.out.println("-----");

    // (3) After deactivating constructor mock mode for class Sub, everything is normal again

    ConstructorMockRegistry.deactivate(Sub.class);
    assertFalse(ConstructorMockRegistry.isMock(Sub.class));
    initialiseSubjectsUnderTest();
    assertEquals(11, base.getId());
    assertEquals(22, sub.getId());
    assertEquals("foo", sub.getName());
    assertEquals(33, anotherSub.getId());
    assertEquals("bar", anotherSub.getName());
    assertEquals(44, extendsSub.getId());
    assertEquals("zot", extendsSub.getName());
    assertNotNull(extendsSub.getDate());
  }

  @Test
  public void constructorMockOnAlreadyLoadedBootstrapClass() throws UnmodifiableClassException {
    // (1) Before activating constructor mock mode for class UUID, everything is normal
    assertFalse(ConstructorMockRegistry.isMock(UUID.class));
    assertEquals("00000000-0000-abba-0000-00000000cafe", new UUID(0xABBA, 0xCAFE).toString());

    // (2a) Retransform already loaded (bootstrap) class UUID in order to make constructors mockable
    INSTRUMENTATION.retransformClasses(UUID.class);

    // (2b) Make UUID constructor mockable
    ConstructorMockRegistry.activate(UUID.class);
    assertTrue(ConstructorMockRegistry.isMock(UUID.class));
    assertEquals("00000000-0000-0000-0000-000000000000", new UUID(0xABBA, 0xCAFE).toString());

    // (3) After deactivating constructor mock mode for class UUID, everything is normal again
    ConstructorMockRegistry.deactivate(UUID.class);
    assertFalse(ConstructorMockRegistry.isMock(UUID.class));
    assertEquals("00000000-0000-abba-0000-00000000cafe", new UUID(0xABBA, 0xCAFE).toString());
  }

  /**
   * This test serves the purpose of checking if the transformation is working for constructor arguments of
   * - all primitive types,
   * - a reference type and
   * - an array (technically also a reference type).
   */
  @Test
  public void constructorMockComplexConstructor() {
    ConstructorMockRegistry.activate(SubWithComplexConstructor.class);
    assertTrue(
      new SubWithComplexConstructor(
        (byte) 123, '@', 123.45, 67.89f,
        123, 123, (short) 123, true,
        "foo", new int[][] { { 12, 34 }, { 56, 78 } }
      ).toString().contains(
        "aByte=0, aChar=\0, aDouble=0.0, aFloat=0.0, " +
          "anInt=0, aLong=0, aShort=0, aBoolean=false, " +
          "string='null', ints=null"
      )
    );
    ConstructorMockRegistry.deactivate(SubWithComplexConstructor.class);
  }

  @Test
  public void nonInjectableMock() {
    new SubUser().doSomething();
    assertNull(pollMockInstance(Sub.class));
    ConstructorMockRegistry.activate(Sub.class);
    new SubUser().doSomething();
    assertTrue(pollMockInstance(Sub.class) instanceof Sub);
    assertNull(pollMockInstance(Sub.class));
    new SubUser().doSomething();
    new SubUser().doSomething();
    assertTrue(pollMockInstance(Sub.class) instanceof Sub);
    assertTrue(pollMockInstance(Sub.class) instanceof Sub);
    assertNull(pollMockInstance(Sub.class));
    ConstructorMockRegistry.deactivate(Sub.class);
    new SubUser().doSomething();
    assertNull(pollMockInstance(Sub.class));
  }

}
