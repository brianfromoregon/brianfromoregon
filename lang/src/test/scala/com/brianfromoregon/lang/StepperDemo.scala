package com.brianfromoregon.lang

import scala.concurrent.ops.spawn
import org.junit.Test
import org.junit.Assert._

class PemdasBadTest {
  @Test def test = {
    var result = 1

    spawn { result += 1 }
    spawn { result *= 3 }

    // JVM could print 6, 4, 3, 2, or 1
    println("%s: 1+1*3 is %d".format(getClass.getSimpleName, result))
  }
}

class PemdasGoodTest {
  @Test def test = {
    val stepper = new Stepper
    var result = 1

    spawn { stepper.takeStep(1); result += 1 }
    spawn { result *= 3; stepper.takeStep(0) }

    // JVM must print 4
    stepper.awaitStep(1)
    assertEquals("1+1*3", 4, result)
  }
}

class LoopBadTest {
  @Test def test = {
    var world = ""

    spawn { Seq('W','O','R','D') foreach {world += _} }

    // JVM could print *many* different things.
    world += 'L'
    println("%s: HI %s!".format(getClass.getSimpleName, world))
  }
}

class LoopGoodTest {
  @Test def test = {
    val stepper = new Stepper
    var world = ""

    spawn { Seq('W','O','R','D') foreach { c => world += c; stepper.takeStep(0, 1, 3, 4)} }

    stepper.awaitStep(1)
    world += 'L'
    stepper.takeStep(2)
    stepper.awaitStep(4)

    // JVM must print "HI WORLD!"
    assertEquals("HI WORLD!", "HI %s!".format(world))
  }
}

package multithreadedtc {

import edu.umd.cs.mtc._

class PemdasGoodTest extends MultithreadedTest {
  def thread1 = {
    var result = 1

    spawn { waitForTick(2); result += 1 }
    spawn { result *= 3; waitForTick(1) }

    waitForTick(3);
    assertEquals("1+1*3", 4, result)
  }

  @Test def runHook() = TestFramework.runOnce(new PemdasGoodTest());
}

class LoopGoodTest extends MultithreadedTest {
  var world = ""

  def thread1() =
    Seq('W', 'O', 'R', 'D') foreach {c => world += c; c match {
        case 'R' => waitForTick(2)
        case 'D' => assertTick(2)
        case _ =>
      }
    }

  def thread2() = {
    waitForTick(1)
    world += 'L'
    waitForTick(3)
    assertEquals("HI WORLD!", "HI %s!".format(world))
  }

  @Test def runHook() = TestFramework.runOnce(new LoopGoodTest());
}

}