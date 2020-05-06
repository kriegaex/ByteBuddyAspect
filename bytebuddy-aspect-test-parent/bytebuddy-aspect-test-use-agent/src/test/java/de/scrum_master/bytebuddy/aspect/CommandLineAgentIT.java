package de.scrum_master.bytebuddy.aspect;

import de.scrum_master.app.UnderTest;
import de.scrum_master.bytebuddy.ByteBuddyAspectAgent;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.UUID;

import static de.scrum_master.testing.TestHelper.isClassLoaded;
import static net.bytebuddy.matcher.ElementMatchers.*;
import static org.junit.Assert.*;

/**
 * When running this test in an IDE like IntelliJ IDEA, please make sure that the JARs for both this module
 * ('bytebuddy-aspect-agent') and 'bytebuddy-aspect' have been created. Just run 'mvn package' first. In IDEA
 * you can also edit the run configuration for this test or a group of tests and add a "before launch" action,
 * select "run Maven goal" and then add goal 'package'.
 * <p>
 * Furthermore, make sure add this to the Maven Failsafe condiguration:
 * <argLine>-javaagent:target/bytebuddy-aspect-agent-1.0-SNAPSHOT.jar</argLine>
 * Otherwise you will see a NoClassDefFoundError when running the tests for the bootstrap JRE classes because
 * boot classloader injection for the Java agent does not work as expected.
 */
public class CommandLineAgentIT {
  private static final Instrumentation INSTRUMENTATION = ByteBuddyAspectAgent.getInstrumentation();

  private Weaver weaver;

  @After
  public void cleanUp() {
    if (weaver != null)
      weaver.unregisterTransformer();
  }

  @Test
  public void weaveLoadedApplicationClass() throws IOException {
    final String CLASS_NAME = "de.scrum_master.app.UnderTest";

    // Load application class
    assertFalse(isClassLoaded(CLASS_NAME));
    UnderTest calculator = new UnderTest();
    assertTrue(isClassLoaded(CLASS_NAME));

    // Create weaver, directly registering a target in the constructor
    weaver = new Weaver(
      INSTRUMENTATION,
      named(CLASS_NAME),
      isMethod(),
      new MethodAroundAdvice(
        null,
        (target, method, args, proceedMode, returnValue, throwable) -> ((int) returnValue) * 11
      ),
      calculator
    );

    // Registered target is affected by aspect, unregistered one is not
    assertEquals(55, calculator.add(2, 3));
    assertNotEquals(55, new UnderTest().add(2, 3));

    // After unregistering the transformer, the target is unaffected by the aspect
    weaver.unregisterTransformer();
    assertEquals(15, calculator.add(7, 8));
  }

  @Test
  public void weaveNotLoadedJREBootstrapClass() throws IOException {
    final String CLASS_NAME = "java.util.UUID";
    final String UUID_TEXT_STUB = "111-222-333-444";

    // Create weaver *before* bootstrap class is loaded (should not make a difference, but check anyway)
    assertFalse(isClassLoaded(CLASS_NAME));
    weaver = new Weaver(
      INSTRUMENTATION,
      named(CLASS_NAME),
      named("toString"),
      // Skip target method and return fixed result -> a classical stub
      new MethodAroundAdvice(
        (target, method, args) -> false,
        (target, method, args, proceedMode, returnValue, throwable) -> UUID_TEXT_STUB
      )
    );

    // Load bootstrap class by instantiating it
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
  public void weaveLoadedJREBootstrapClass() throws IOException {
    final String CLASS_NAME = "java.lang.String";
    final String TEXT = "To be, or not to be, that is the question";

    // Create weaver *after* bootstrap class is loaded (should not make a difference, but check anyway)
    assertTrue(isClassLoaded(CLASS_NAME));
    weaver = new Weaver(
      INSTRUMENTATION,
      named(CLASS_NAME),
      named("replaceAll").and(takesArguments(String.class, String.class)),
      replaceAllAdvice()
    );

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

  /**
   * This is an example for a somewhat more complex aspect doing the following:
   * 1. conditionally skip proceeding to target method
   * 2. conditionally modify method argument before proceeding
   * 3. catch exception thrown by target method and return a value instead
   * 4. in case target method was not called (proceed), return special value
   * 5. otherwise pass through return value from target method
   */
  private MethodAroundAdvice replaceAllAdvice() {
    return new MethodAroundAdvice(
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

}