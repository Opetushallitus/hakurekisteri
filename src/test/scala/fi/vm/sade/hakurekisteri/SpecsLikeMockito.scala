package fi.vm.sade.hakurekisteri

import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito
import scala.language.implicitConversions

trait SpecsLikeMockito extends MockitoSugar {

  class MockitoMock[T](method: T) {
    def returns(value:T) = Mockito.when(method).thenReturn(value)
  }

  implicit def call2Mock[T](call: T): MockitoMock[T] = new MockitoMock[T](call)

}
