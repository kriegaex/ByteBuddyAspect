package de.scrum_master.testing;

import de.scrum_master.bytebuddy.aspect.WeaverTest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class TestHelper {
  public static boolean isClassLoaded(String name) throws RuntimeException {
    try {
      Method findLoadedClass = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
      findLoadedClass.setAccessible(true);
      return findLoadedClass.invoke(WeaverTest.class.getClassLoader(), name) != null;
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException reflectionProblem) {
      throw new RuntimeException(
        "Cannot use ClassLoader.findLoadedClass in order to check for already loaded classes",
        reflectionProblem
      );
    }
  }
}
