package dev.sarek.agent.constructor_mock;

import dev.sarek.agent.aspect.GlobalInstance;
import dev.sarek.agent.aspect.InstanceMethodAroundAdvice;
import dev.sarek.agent.aspect.StaticMethodAroundAdvice;
import dev.sarek.agent.aspect.Weaver;
import dev.sarek.test.util.SeparateJVM;
import org.acme.FinalClass;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static net.bytebuddy.matcher.ElementMatchers.*;
import static org.junit.Assert.*;

/**
 * This test runs without a Java agent set via command line. It attaches it dynamically after adding it to the
 * bootstrap class loader's search path. The latter is only necessary if we want to mock constructors of classes which
 * are either bootstrap classes themselves or have bootstrap classes in their ancestry (direct or indirect parent
 * classes).
 * <p>
 * Furthermore, the test demonstrates how to retransform an already loaded class in order to not just create a mock
 * without subclassing but also add stub behaviour to it. This proves that both the constructor mock transformation as
 * well as the aspect advices do not change the class structure but only instrument constructor and method bodies. I.e.
 * that this is more flexible than e.g. removing 'final' modifiers because the latter change class/method signatures and
 * are not permitted in retransformations, so they have to be done during class-loading.
 */
@Category(SeparateJVM.class)
public class ConstructorMockAspectIT {
  private ConstructorMockTransformer constructorMockTransformer;
  private Weaver weaver;

  @BeforeClass
  public static void beforeClass() {
    useApplicationClassBeforeInstrumentation();
  }

  private static void useApplicationClassBeforeInstrumentation() {
    new FinalClass();
  }

  @Before
  public void beforeTest() {
    constructorMockTransformer = ConstructorMockTransformer.forClass(FinalClass.class).build();
  }

  @After
  public void afterTest() {
    constructorMockTransformer.close();
    constructorMockTransformer = null;
    if (weaver != null)
      weaver.unregisterTransformer();
    weaver = null;
  }

  @Test
  public void mockAndStubFinalClass() {

    // (1) Before mocking is active, class under test behaves normally

    // Check instance methods
    FinalClass.resetInstanceCounter();
    new FinalClass().doSomething();
    assertEquals("Hello world", new FinalClass().greet("world"));
    assertEquals(3, new FinalClass().add(1, 2));
    assertEquals(12.3, new FinalClass().percentOf(123, 10), 1e-6);
    assertEquals('t', new FinalClass().firstChar("test"));
    //noinspection ConstantConditions
    assertTrue(new FinalClass().invert(false));

    // Each constructor call was executed
    assertEquals(6, FinalClass.getInstanceCounter());

    // Static methods
    assertEquals("foo bar zot", FinalClass.concatenate("foo", "bar", "zot"));
    assertEquals(12, FinalClass.multiply(3, 4), 1e-6);

    // (2) Mock both constructors and methods
    ConstructorMockRegistry.activate(FinalClass.class);
    weaver = Weaver
      .forTypes(is(FinalClass.class))
      .addAdvice(
        null,
        InstanceMethodAroundAdvice.MOCK
      )
      .addAdvice(
        not(nameEndsWith("InstanceCounter")),
        StaticMethodAroundAdvice.MOCK
      )
      .addTargets(FinalClass.class, GlobalInstance.of(FinalClass.class))
      .build();

    // (3) After mocking was activated, class under test behaves like a mock

    // Check instance methods
    FinalClass.resetInstanceCounter();
    new FinalClass().doSomething();
    //noinspection ConstantConditions
    assertNull(new FinalClass().greet("world"));
    assertEquals(0, new FinalClass().add(1, 2));
    assertEquals(0, new FinalClass().percentOf(123, 10), 1e-6);
    assertEquals(0, new FinalClass().firstChar("test"));
    assertFalse(new FinalClass().invert(false));

    // No(!) constructor call was executed
    assertEquals(0, FinalClass.getInstanceCounter());

    // Static methods
    assertNull(FinalClass.concatenate("foo", "bar", "zot"));
    assertEquals(0, FinalClass.multiply(3, 4), 1e-6);
  }

}
