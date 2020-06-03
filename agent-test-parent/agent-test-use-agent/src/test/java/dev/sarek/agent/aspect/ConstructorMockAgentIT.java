package dev.sarek.agent.aspect;

import dev.sarek.agent.constructor_mock.ConstructorMockRegistry;
import dev.sarek.app.AnotherSub;
import dev.sarek.app.Base;
import dev.sarek.app.ExtendsSub;
import dev.sarek.app.Sub;
import org.junit.Test;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.junit.Assert.*;

public class ConstructorMockAgentIT {
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
  public void constructorMockOnApplicationClass() {
    final String className_Sub = Sub.class.getName();

    // (1) Before activating constructor mock mode for class Sub, everything is normal

    assertFalse(ConstructorMockRegistry.isMock(className_Sub));
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

    // (2) After activating constructor mock mode for class Sub,
    //   - fields should be uninitialised for Sub instances,
    //   - but not for direct base class instances or siblings in the inheritance hierarchy such as AnotherSub.
    //   - When instantiating child classes of Sub, their own constructors will not be mocked either, but the
    //     parent constructors from Sub upwards (i.e. Sub, Base) will be.

    ConstructorMockRegistry.activate(className_Sub);
    assertTrue(ConstructorMockRegistry.isMock(className_Sub));
    initialiseSubjectsUnderTest();
    // No change in behaviour for base class Base
    assertEquals(11, base.getId());
    // Constructor mock effect for target class Sub
    assertEquals(0, sub.getId());
    assertNull(sub.getName());
    // No change in behaviour for sibling class AnotherSub
    assertEquals(33, anotherSub.getId());
    assertEquals("bar", anotherSub.getName());
    // ExtendsSub extends Sub behaves normally in its own constructor, but Sub/Base still have constructor mock behaviour
    assertEquals(0, extendsSub.getId());
    assertNull(extendsSub.getName());
    assertNotNull(extendsSub.getDate());

    System.out.println("-----");

    // (3) After deactivating constructor mock mode for class Sub, everything is normal again

    ConstructorMockRegistry.deactivate(className_Sub);
    assertFalse(ConstructorMockRegistry.isMock(className_Sub));
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
  public void constructorMockOnAlreadyLoadedBootstrapClass() {
    // (1) Before activating constructor mock mode for class UUID, everything is normal
    assertFalse(ConstructorMockRegistry.isMock(UUID.class.getName()));
    assertEquals("00000000-0000-abba-0000-00000000cafe", new UUID(0xABBA, 0xCAFE).toString());

    // (2) Make class UUID constructor mockable
    ConstructorMockRegistry.activate(UUID.class.getName());
    assertTrue(ConstructorMockRegistry.isMock(UUID.class.getName()));
    assertEquals("00000000-0000-0000-0000-000000000000", new UUID(0xABBA, 0xCAFE).toString());

    // (3) After deactivating constructor mock mode for class UUID, everything is normal again
    ConstructorMockRegistry.deactivate(UUID.class.getName());
    assertFalse(ConstructorMockRegistry.isMock(UUID.class.getName()));
    assertEquals("00000000-0000-abba-0000-00000000cafe", new UUID(0xABBA, 0xCAFE).toString());
  }
}