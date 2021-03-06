package dev.sarek.agent.aspect;

import dev.sarek.junit4.SarekRunner;
import dev.sarek.test.util.SeparateJVM;
import org.acme.UnderTest;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

import static dev.sarek.test.util.TestHelper.isClassLoaded;
import static net.bytebuddy.matcher.ElementMatchers.*;
import static org.junit.Assert.*;

@Category(SeparateJVM.class)
@RunWith(SarekRunner.class)
public class WeaverIT {
  private Weaver weaver;

  @After
  public void cleanUp() {
    if (weaver != null)
      weaver.unregisterTransformer();
  }

  @Test
  public void weaveLoadedApplicationClass() {
    final String CLASS_NAME = "org.acme.UnderTest";

    // Create application class instance
    UnderTest underTest = new UnderTest();
    assertTrue(isClassLoaded(CLASS_NAME));

    // Create weaver, directly registering a target in the constructor
    weaver = Weaver
      .forTypes(named(CLASS_NAME))
      .addAdvice(
        isMethod().and(not(named("greet"))),
        new InstanceMethodAroundAdvice(
          null,
          (target, method, args, proceedMode, returnValue, throwable) -> ((int) returnValue) * 11
        )
      )
      .addTargets(underTest)
      .build();

    // Registered target is affected by aspect, unregistered one is not
    assertEquals(55, underTest.add(2, 3));
    assertNotEquals(55, new UnderTest().add(2, 3));

    // Matcher too broad (all methods of target class) + sloppy advice implementation
    // (assuming specific parameter types) -> runtime exception
    //noinspection ResultOfMethodCallIgnored
    assertThrows(ClassCastException.class, underTest::getName);

    // After unregistering the transformer, the target is unaffected by the aspect
    weaver.unregisterTransformer();
    assertEquals(15, underTest.add(7, 8));
  }

  @Test
  public void weaveJREUtilityBootstrapClass() {
    final String CLASS_NAME = "java.util.UUID";
    final String UUID_TEXT_STUB = "111-222-333-444";

    weaver = Weaver
      .forTypes(named(CLASS_NAME))
      .addAdvice(
        // Skip target method and return fixed result -> a classical stub
        named("toString"),
        new InstanceMethodAroundAdvice(
          (target, method, args) -> false,
          (target, method, args, proceedMode, returnValue, throwable) -> UUID_TEXT_STUB
        )
      )
      .build();

    // Load bootstrap class by instantiating it, if it was not loaded yet (should not make any difference)
    UUID uuid = UUID.randomUUID();
    assertTrue(isClassLoaded(CLASS_NAME));

    // The target instance has not been registered on the weaver yet
    assertNotEquals(UUID_TEXT_STUB, uuid.toString());

    // After registration on the weaver, the aspect affects the target instance
    weaver.addTarget(uuid);
    assertEquals(UUID_TEXT_STUB, uuid.toString());

    // Another instance is unaffected by the aspect
    assertNotEquals(UUID_TEXT_STUB, UUID.randomUUID().toString());

    // After deregistration, the target instance is also unaffected again
    weaver.removeTarget(uuid);
    assertNotEquals(UUID_TEXT_STUB, uuid.toString());

    // The same instance can be registered again
    weaver.addTarget(uuid);
    assertEquals(UUID_TEXT_STUB, uuid.toString());

    // After unregistering the whole transformer from instrumentation, the aspect is ineffective
    weaver.unregisterTransformer();
    assertNotEquals(UUID_TEXT_STUB, uuid.toString());
  }

  @Test
  public void weaveJRECoreBootstrapClass() {
    final String CLASS_NAME = "java.lang.String";
    final String TEXT = "To be, or not to be, that is the question";

    // Create weaver *after* bootstrap class is loaded (should not make a difference, but check anyway)
    assertTrue(isClassLoaded(CLASS_NAME));
    weaver = Weaver
      .forTypes(named(CLASS_NAME))
      .addAdvice(
        named("replaceAll").and(takesArguments(String.class, String.class)),
        replaceAllAdvice()
      )
      .build();

    // Before registering TEXT as an advice target instance, 'replaceAll' behaves normally
    assertEquals("To modify, or not to modify, that is the question", TEXT.replaceAll("be", "modify"));

    // Register target instance on weaver, then check expected aspect behaviour
    weaver.addTarget(TEXT);
    // (1) Proceed to target method without any modifications
    assertEquals("To eat, or not to eat, that is the question", TEXT.replaceAll("be", "eat"));
    // (2) Do not proceed to target method, let aspect modify input text instead
    assertEquals("T0 bε, 0r n0t t0 bε, that is thε quεsti0n", TEXT.replaceAll("be", "skip"));
    // (3) Aspect handles exception, returns dummy result
    assertEquals("caught exception from proceed", TEXT.replaceAll("be", "$1"));
    // (4) Aspect modifies replacement parameter
    assertEquals("To ❤, or not to ❤, that is the question", TEXT.replaceAll("be", "modify"));

    // Negative test: aspect has no effect on an instance not registered as a target
    String noTarget = "Let it be";
    assertEquals("Let it go", noTarget.replaceAll("be", "go"));
    assertEquals("Let it skip", noTarget.replaceAll("be", "skip"));
    assertEquals("Let it modify", noTarget.replaceAll("be", "modify"));

    // After unregistering TEXT as an advice target instance, 'replaceAll' behaves normally again
    weaver.removeTarget(TEXT);
    assertEquals("To modify, or not to modify, that is the question", TEXT.replaceAll("be", "modify"));
  }

  @Test
  public void weaveStaticJREMethods() {
    weaver = Weaver
      .forTypes(is(System.class))
      .addAdvice(
        named("getProperty"),
        new StaticMethodAroundAdvice(
          (method, args) -> !args[0].equals("java.version"),
          (method, args, proceedMode, returnValue, throwable) -> proceedMode ? returnValue : "42"
        )
      )
      .addTargets(System.class)
      .build();

    // Only system property 'java.version' is mocked
    assertEquals("42", System.getProperty("java.version"));
    assertEquals("42", System.getProperty("java.version", "1.2.3"));
    assertNotEquals("42", System.getProperty("java.home"));
    assertNotEquals("42", System.getProperty("line.separator"));
  }

  @Test
  public void weavingNativeMethodsHasNoEffect() {
    weaver = Weaver
      .forTypes(is(System.class))
      .addAdvice(
        named("currentTimeMillis").or(named("nanoTime")),
        new StaticMethodAroundAdvice(
          (method, args) -> false,
          (method, args, proceedMode, returnValue, throwable) -> 123
        )
      )
      .addTargets(System.class)
      .build();

    assertNotEquals(123, System.currentTimeMillis());
    assertNotEquals(123, System.nanoTime());
  }

  /**
   * This is an example for a somewhat more complex aspect doing the following:
   * 1. conditionally skip proceeding to target method
   * 2. conditionally modify method argument before proceeding
   * 3. catch exception thrown by target method and return a value instead
   * 4. in case target method was not called (proceed), return special value
   * 5. otherwise pass through return value from target method
   */
  private InstanceMethodAroundAdvice replaceAllAdvice() {
    return new InstanceMethodAroundAdvice(
      // Should proceed?
      (target, method, args) -> {
        String replacement = (String) args[1];
        if (replacement.equalsIgnoreCase("skip"))
          return false;
        if (replacement.equalsIgnoreCase("modify"))
          args[1] = "❤";
        return true;
      },

      // Handle result of (optional) proceed
      (target, method, args, proceedMode, returnValue, throwable) -> {
        if (throwable != null)
          return "caught exception from proceed";
        if (!proceedMode)
          return ((String) target).replace("e", "ε").replace("o", "0");
        return returnValue;
      }
    );
  }

  @Test
  public void createFile() throws URISyntaxException {
    final ThreadLocal<Integer> callCount = ThreadLocal.withInitial(() -> 0);

    // Before registering the transformer, the class is unaffected by the aspect
    assertEquals("foo", new File("foo").getName());
    assertEquals(0, (int) callCount.get());

    // Count all File constructor calls, modify first argument if it is a String
    weaver = Weaver
      .forTypes(is(File.class))
      .addAdvice(
        any(),
        new ConstructorAroundAdvice(
          (constructor, args) -> {
            if (args[0] instanceof String)
              args[0] = "ADVISED";
            callCount.set(callCount.get() + 1);
          },
          null
        )
      )
      .addTargets(File.class)
      .build();

    // First argument is a String -> the aspect modifies it + increments the call counter
    assertEquals("ADVISED", new File("foo").getName());
    assertEquals("ADVISED", new File("bar").getName());
    assertEquals("ADVISED", new File("parent", "child").getParent());
    // First argument is not a String -> the aspect only increments the call counter
    assertEquals("bar", new File(new URI("file:///foo/bar")).getName());
    assertEquals(4, (int) callCount.get());

    // After unregistering the transformer, the class is unaffected by the aspect
    weaver.unregisterTransformer();
    assertEquals("foo", new File("foo").getName());
    assertEquals(4, (int) callCount.get());
  }

  @Test
  public void staticMethodCall() {
    // Create weaver, directly registering a target class in the constructor
    weaver = Weaver
      .forTypes(is(UnderTest.class))
      .addAdvice(
        named("greet"),
        new StaticMethodAroundAdvice(
          null,
          (method, args, proceedMode, returnValue, throwable) -> "Hi world!"
        )
      )
      .addTargets(UnderTest.class)
      .build();

    // Registered class is affected by aspect
    assertEquals("Hi world!", UnderTest.greet("Sir"));

    // After unregistering the transformer, the class is unaffected by the aspect
    weaver.unregisterTransformer();
    assertEquals("Hello Sir", UnderTest.greet("Sir"));
  }

  @Test
  public void perClassAdvice() {
    // Create weaver, directly registering a target class in the constructor
    weaver = Weaver
      .forTypes(is(UnderTest.class))
      .addAdvice(
        isMethod(),
        new StaticMethodAroundAdvice(
          null,
          (method, args, proceedMode, returnValue, throwable) ->
            returnValue instanceof Integer
              ? ((int) returnValue) * 11
              : "Welcome, dear " + args[0]
        )
      )
      .addTargets(UnderTest.class)
      .build();

    // Static method is affected by aspect
    assertEquals("Welcome, dear Sir", UnderTest.greet("Sir"));
    // Instance method is unaffected by aspect
    assertEquals(3, new UnderTest().add(1, 2));

    // After unregistering the transformer, the class is unaffected by the aspect
    weaver.unregisterTransformer();
    assertEquals("Hello Sir", UnderTest.greet("Sir"));
    assertEquals(3, new UnderTest().add(1, 2));
  }

  @Test
  public void constructorAdvice() {
    // Create weaver, directly registering a target class in the constructor
    final ThreadLocal<Integer> callCount = ThreadLocal.withInitial(() -> 0);

    // Before registering the transformer, the class is unaffected by the aspect
    assertEquals("whatever", new UnderTest("whatever").getName());
    assertEquals(0, (int) callCount.get());

    weaver = Weaver
      .forTypes(is(UnderTest.class))
      .addAdvice(
        takesArguments(String.class),
        new ConstructorAroundAdvice(
          (constructor, args) -> {
            args[0] = "ADVISED";
            callCount.set(callCount.get() + 1);
          },
          null
        )
      )
      .addTargets(UnderTest.class)
      .build();

    // Registered class is affected by aspect
    assertEquals("ADVISED", new UnderTest("whatever").getName());
    assertEquals("ADVISED", new UnderTest("whenever").getName());
    assertEquals(2, (int) callCount.get());

    // After unregistering the transformer, the class is unaffected by the aspect
    weaver.unregisterTransformer();
    assertEquals("whatever", new UnderTest("whatever").getName());
    assertEquals(2, (int) callCount.get());
  }

  @Test
  public void multiAdvice() {
    UnderTest underTest = new UnderTest();
    UnderTest underTestUnregistered = new UnderTest();
    weaver = Weaver
      .forTypes(is(UnderTest.class))
      .addAdvice(
        named("add"),
        new InstanceMethodAroundAdvice(
          null,
          (target, method, args, proceedMode, returnValue, throwable) -> ((int) returnValue) * 11
        )
      )
      .addAdvice(
        named("multiply"),
        new InstanceMethodAroundAdvice(
          null,
          (target, method, args, proceedMode, returnValue, throwable) -> ((int) returnValue) / 5
        )
      )
      .addAdvice(
        named("greet"),
        new StaticMethodAroundAdvice(
          null,
          (method, args, proceedMode, returnValue, throwable) -> "Hi world!"
        )
      )
      .addAdvice(
        takesArguments(String.class),
        new ConstructorAroundAdvice(
          (constructor, args) -> args[0] = "ADVISED",
          null
        )
      )
      .addTargets(underTest, UnderTest.class)
      .build();

    // Weaver is only active for registered instance and we can advise multiple instance methods for the same advice
    assertEquals(55, underTest.add(2, 3));
    assertEquals(3, underTest.multiply(3, 6));
    assertEquals(5, underTestUnregistered.add(2, 3));
    assertEquals(18, underTestUnregistered.multiply(3, 6));
    // Weaver is also active for static method and constructor
    assertEquals("Hi world!", UnderTest.greet("Sir"));
    assertEquals("ADVISED", new UnderTest("whatever").getName());

    // Now there are two registered instances
    weaver.addTarget(underTestUnregistered);
    assertEquals(55, underTest.add(2, 3));
    assertEquals(55, underTestUnregistered.add(2, 3));
    assertEquals(5, new UnderTest().add(2, 3));

    // If no more instances are registered but still the class is registered, no instances will be advised
    weaver.removeTarget(underTest);
    weaver.removeTarget(underTestUnregistered);
    assertEquals(5, underTest.add(2, 3));
    assertEquals(5, underTestUnregistered.add(2, 3));
    assertEquals(5, new UnderTest().add(2, 3));

    // If the class target is also removed, no static methods and constructors will be advised either
    weaver.removeTarget(UnderTest.class);
    assertEquals(5, underTest.add(2, 3));
    assertEquals(5, underTestUnregistered.add(2, 3));
    assertEquals(5, new UnderTest().add(2, 3));
    assertEquals("Hello Sir", UnderTest.greet("Sir"));
    assertEquals("whatever", new UnderTest("whatever").getName());

    // If a global instance target is registered, all instances are advised, but no static methods or constructors
    weaver.addTarget(GlobalInstance.of(UnderTest.class));
    assertEquals(55, underTest.add(2, 3));
    assertEquals(55, underTestUnregistered.add(2, 3));
    assertEquals(55, new UnderTest().add(2, 3));
    assertEquals("Hello Sir", UnderTest.greet("Sir"));
    assertEquals("whatever", new UnderTest("whatever").getName());
  }

  @Test
  public void multiAdvicePrecedence() {
    UnderTest underTest = new UnderTest("John Doe");
    UnderTest underTestUnregistered = new UnderTest("Nobody");

    // Scenario 1: advices ordered from more to less specific
    weaver = Weaver
      .forTypes(is(UnderTest.class))
      .addAdvice(
        named("add"),
        new InstanceMethodAroundAdvice(
          null,
          (target, method, args, proceedMode, returnValue, throwable) -> ((int) returnValue) * 11
        )
      )
      .addAdvice(
        takesArguments(int.class, int.class),
        new InstanceMethodAroundAdvice(
          null,
          (target, method, args, proceedMode, returnValue, throwable) -> ((int) returnValue) / 5
        )
      )
      .addAdvice(
        returns(int.class),
        new InstanceMethodAroundAdvice(
          null,
          (target, method, args, proceedMode, returnValue, throwable) -> 42
        )
      )
      .addAdvice(
        any(),
        InstanceMethodAroundAdvice.MOCK
      )
      .addTargets(underTest)
      .build();

    // Weaver is only active for registered instance
    assertEquals(5, underTestUnregistered.add(2, 3));
    assertEquals(18, underTestUnregistered.multiply(3, 6));
    assertEquals(-11, underTestUnregistered.negate(11));
    assertEquals("Nobody", underTestUnregistered.getName());

    // Advice matching is sensitive to chronological order in which advices were added
    assertEquals(55, underTest.add(2, 3));
    assertEquals(3, underTest.multiply(3, 6));
    assertEquals(42, underTest.negate(11));
    assertNull(underTest.getName());

    weaver.unregisterTransformer();

    // Scenario 2: advice #2 is less specific than #3, so #3 is never in effect
    weaver = Weaver
      .forTypes(is(UnderTest.class))
      .addAdvice(
        named("add"),
        new InstanceMethodAroundAdvice(
          null,
          (target, method, args, proceedMode, returnValue, throwable) -> ((int) returnValue) * 11
        )
      )
      .addAdvice(
        returns(int.class),
        new InstanceMethodAroundAdvice(
          null,
          (target, method, args, proceedMode, returnValue, throwable) -> 42
        )
      )
      .addAdvice(
        takesArguments(int.class, int.class),
        new InstanceMethodAroundAdvice(
          null,
          (target, method, args, proceedMode, returnValue, throwable) -> ((int) returnValue) / 5
        )
      )
      .addAdvice(
        any(),
        InstanceMethodAroundAdvice.MOCK
      )
      .addTargets(underTest)
      .build();

    // Advice matching is sensitive to chronological order in which advices were added
    assertEquals(55, underTest.add(2, 3));
    assertEquals(42, underTest.multiply(3, 6));
    assertEquals(42, underTest.negate(11));
    assertNull(underTest.getName());

    weaver.unregisterTransformer();

    // Scenario 3: advice #1 is less specific than #2 and #3, so #2 and #3 are never in effect
    weaver = Weaver
      .forTypes(is(UnderTest.class))
      .addAdvice(
        returns(int.class),
        new InstanceMethodAroundAdvice(
          null,
          (target, method, args, proceedMode, returnValue, throwable) -> 42
        )
      )
      .addAdvice(
        named("add"),
        new InstanceMethodAroundAdvice(
          null,
          (target, method, args, proceedMode, returnValue, throwable) -> ((int) returnValue) * 11
        )
      )
      .addAdvice(
        takesArguments(int.class, int.class),
        new InstanceMethodAroundAdvice(
          null,
          (target, method, args, proceedMode, returnValue, throwable) -> ((int) returnValue) / 5
        )
      )
      .addAdvice(
        any(),
        InstanceMethodAroundAdvice.MOCK
      )
      .addTargets(underTest)
      .build();

    // Advice matching is sensitive to chronological order in which advices were added
    assertEquals(42, underTest.add(2, 3));
    assertEquals(42, underTest.multiply(3, 6));
    assertEquals(42, underTest.negate(11));
    assertNull(underTest.getName());

    weaver.unregisterTransformer();

    // Scenario 4: advice #1 is less specific than all the others, so none of them are never in effect
    weaver = Weaver
      .forTypes(is(UnderTest.class))
      .addAdvice(
        any(),
        InstanceMethodAroundAdvice.MOCK
      )
      .addAdvice(
        named("add"),
        new InstanceMethodAroundAdvice(
          null,
          (target, method, args, proceedMode, returnValue, throwable) -> ((int) returnValue) * 11
        )
      )
      .addAdvice(
        takesArguments(int.class, int.class),
        new InstanceMethodAroundAdvice(
          null,
          (target, method, args, proceedMode, returnValue, throwable) -> ((int) returnValue) / 5
        )
      )
      .addAdvice(
        returns(int.class),
        new InstanceMethodAroundAdvice(
          null,
          (target, method, args, proceedMode, returnValue, throwable) -> 42
        )
      )
      .addTargets(underTest)
      .build();

    // Advice matching is sensitive to chronological order in which advices were added
    assertEquals(0, underTest.add(2, 3));
    assertEquals(0, underTest.multiply(3, 6));
    assertEquals(0, underTest.negate(11));
    assertNull(underTest.getName());

  }

}
