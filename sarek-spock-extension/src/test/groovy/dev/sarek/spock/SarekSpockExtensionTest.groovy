package dev.sarek.spock

import dev.sarek.agent.mock.MockFactory
import org.acme.Base
import org.acme.FinalClass
import org.acme.Sub
import spock.lang.Specification

class SarekSpockExtensionTest extends Specification {

  def canMockApplicationClasses() {
    given:
    MockFactory<FinalClass> mockFactory1 = MockFactory.forClass(FinalClass).global().addGlobalInstance().build();
    MockFactory<Sub> mockFactory2 = MockFactory.forClass(Sub).global().build();
    MockFactory<Base> mockFactory3 = MockFactory.forClass(Base).global().build()

    expect:
    new FinalClass().add(2, 3) == 0
    new Base(11).getId() == 0
    !new Sub("foo").getName()

    when:
    mockFactory1.close()
    mockFactory2.close()
    mockFactory3.close()

    then:
    new FinalClass().add(2, 3) == 5
    new Base(11).getId() == 11
    new Sub("foo").getName() == "foo"
  }

}