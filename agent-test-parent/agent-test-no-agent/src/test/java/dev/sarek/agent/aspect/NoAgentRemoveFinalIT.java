package dev.sarek.agent.aspect;

import dev.sarek.agent.remove_final.RemoveFinalTransformer;
import dev.sarek.app.FinalClass;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.Test;

import java.io.ObjectOutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Modifier;
import java.util.UUID;

import static dev.sarek.testing.TestHelper.isClassLoaded;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * This test checks features which do not involve already loaded bootloader classes.
 * So we do not need a Java agent here.
 */
public class NoAgentRemoveFinalIT {
  private static final Instrumentation INSTRUMENTATION = ByteBuddyAgent.install();

  @Test
  public void hotAttachDefinaliser() throws NoSuchMethodException {
    // Ensure classes under test have not been loaded yet
    assertFalse(isClassLoaded("dev.sarek.app.FinalClass"));
    assertFalse(isClassLoaded("java.util.UUID"));

    // Activate definaliser
    RemoveFinalTransformer.install(INSTRUMENTATION, true);

    // Final application class has been definalised
    assertFalse(Modifier.isFinal(FinalClass.class.getModifiers()));
    // Final method of application class has been definalised
    assertFalse(Modifier.isFinal(FinalClass.class.getDeclaredMethod("doSomething").getModifiers()));
    // Final bootstrap class has been definalised
    assertFalse(Modifier.isFinal(UUID.class.getModifiers()));

    // Final method of already loaded bootstrap class has NOT been definalised
    assertTrue(Modifier.isFinal(ObjectOutputStream.class.getDeclaredMethod("writeObject", Object.class).getModifiers()));
  }

}