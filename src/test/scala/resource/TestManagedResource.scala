package resource

import _root_.java.{io => jio}

import _root_.scala.concurrent.Await
import _root_.scala.concurrent.duration.Duration
import _root_.scala.concurrent.ExecutionContext.Implicits.global

/**
 * This is a basic abstraction for an iterator that fetches new content as needed.
 */
abstract class FetchIterator[T] extends Iterator[T] {
	var fetched = false
	var nextItem : Option[T] = None
	protected def fetchNext(): Option[T]
	override def hasNext = {
		if (!fetched) {
			nextItem = fetchNext()
			fetched = true
		}
		nextItem.isDefined
	}
	override def next() = {
		if (!hasNext) throw new NoSuchElementException("EOF")
		fetched = false
		nextItem.get
	}
}

/** This class creates an iterator from a buffered iterator.  Used to test toTraverable method */
class JavaBufferedReaderLineIterator(br : jio.BufferedReader) extends FetchIterator[String] {
  override def fetchNext() = br.readLine() match {
    case null => None
    case s => Some(s)
  }
}

object FakeResource {
  val GEN_DATA_ERROR =  "Attempted to generate data when resource is not opened!"
  val CLOSE_ERROR = "Attempting to close unopened resource!"
  val OPEN_ERROR = "Attempting to open already opened resource!"
}

/**
 * This is a fake resource type we can utilize.
 */
class FakeResource {
   import java.util.concurrent.atomic.AtomicBoolean
   import FakeResource._
   private val opened = new AtomicBoolean(false)

   def open() : Unit = {
      if(!opened.compareAndSet(false,true)) {
         sys.error(OPEN_ERROR)
      }
   }

   def close() : Unit = {
     if(!opened.compareAndSet(true, false)) {
       sys.error(CLOSE_ERROR)
     }
   }
   protected def makeData = math.random
   def generateData = if(opened.get) makeData else sys.error(GEN_DATA_ERROR)
   def isOpened = opened.get
}

class ThrowingFakeResource(message: scala.Option[String] = None) extends FakeResource {
  override def close(): Unit = {
    throw message.map(new IllegalStateException(_)).getOrElse(new IllegalStateException)
  }
}

import org.junit._
import Assert._

class TestManagedResource extends TestManagedResourceCompat {
  /**
   * This type trait is used to override the default type trait and  give us
   * the use of the managed function for slightly nicer API.   We create this implicit
   * to allow subclasses of FakeResource because otherwise subclasses would *not* use this type trait
   * due to the invariant nature of Resource.
   *
   * TODO - Can we make Resource be contravariant or covariant?
   */
  implicit def fakeResourceTypeTrait[A <: FakeResource] = new Resource[A] {
    override def open(r : A) = r.open()
    override def close(r : A) = r.close()
  }

   @Test
   def mustOpenAndClose(): Unit = {     
     val r = new FakeResource();
     assertFalse("Failed to begin closed!", r.isOpened)
     val mr = managed(r)
      assertFalse("Creating managed resource opens the resource!", r.isOpened)
      for(r <- mr ) {
          assertTrue("Failed to open resource", r.isOpened)
      }
      assertFalse("Failed to close resource", r.isOpened)
   }  

   @Test
   def mustCloseWhenClosureThrows(): Unit = {
     val r = new FakeResource()
     assertFalse("Failed to begin closed!", r.isOpened)
     val mr = managed(r)
     assertFalse("Creating managed resource opens the resource!", r.isOpened)
     try {
       for(r <- mr) {
         assertTrue("Failed to open resource", r.isOpened)
         throw new RuntimeException
       }
     }
     catch {
       case _: RuntimeException =>
     }
     assertFalse("Failed to close resource", r.isOpened)
   }

   @Test(expected=classOf[IllegalStateException])
   def mustThrowCloseException(): Unit = {
     val r = new ThrowingFakeResource()
     assertFalse("Failed to begin closed!", r.isOpened)
     val mr = managed(r)
     assertFalse("Creating managed resource opens the resource!", r.isOpened)
     for(r <- mr) {
       assertTrue("Failed to open resource", r.isOpened)
     }
   }

   @Test
   def mustThrowClosureExceptionIfBothClosureAndCloseThrow(): Unit = {
     val r = new ThrowingFakeResource()
     assertFalse("Failed to begin closed!", r.isOpened)
     val mr = managed(r)
     assertFalse("Creating managed resource opens the resource!", r.isOpened)
     try {
       for(r <- mr) {
         assertTrue("Failed to open resource", r.isOpened)
         throw new RuntimeException
       }
     }
     catch {
       case _: RuntimeException =>
     }
     assertTrue("Unexpectedly closed resource", r.isOpened)
   }

   @Test
   def mustExtractValue(): Unit = {
     val r = new FakeResource();
     val mr = managed(r)
      assertFalse("Failed to begin closed!", r.isOpened)
      val monad = for(r <- mr ) yield {
          assertTrue("Failed to open resource", r.isOpened)
          r.generateData
      }      
      val result = monad.opt
      assertTrue("Failed to extract a result", result.isDefined)
      assertFalse("Failed to close resource", r.isOpened)
   }  

   @Test
   def mustNest(): Unit = {
     val r = new FakeResource();
     val mr = managed(r)
     val r2 = new FakeResource();
     val mr2 = managed(r2)
 
      assertFalse("Failed to begin closed!", r.isOpened)
      assertFalse("Failed to begin closed!", r2.isOpened)
      for(r <- mr; r2 <- mr2 ) {
          assertTrue("Failed to open resource", r.isOpened)
          assertTrue("Failed to open resource", r2.isOpened)
      }      
      assertFalse("Failed to close resource", r.isOpened)
      assertFalse("Failed to close resource", r2.isOpened)
   }  
   

   @Test
   def mustNestForYield(): Unit = {
     val r = new FakeResource();
     val mr = managed(r)
     val r2 = new FakeResource();
     val mr2 = managed(r2)
     val monad = for { r <- mr
                       r2 <- mr2 
                     } yield r.generateData + r2.generateData      
     //This can't compile as the monad is not an extractable resource!!!
     //assertTrue("Failed to extract a result", monad.opt.isDefined)      
     assertTrue("Failed to extract a result", monad.map(identity[Double]).opt.isDefined)            
     assertFalse("Failed to close resource", r.isOpened)
     assertFalse("Failed to close resource", r2.isOpened)
   }

  @Test
  def mustReturnCaptureAllExceptions(): Unit = {
    val r = new ThrowingFakeResource()

    val result = managed(r).map {
      case _ => throw new RuntimeException
    }.either

    assertTrue("Failed to capture all exceptions",
      result.left.exists(_.length == 2))
  }

  @Test
  def mustNestCaptureAllExceptions(): Unit = {
    val r = new ThrowingFakeResource(Some("outer close"))
    val r2 = new ThrowingFakeResource(Some("inner close"))

    val result = managed(r).map { _ =>
      managed(r2).map { _ => throw new RuntimeException("runtime")
      }.either
    }.either

    assertTrue("Failed to capture all exceptions",
      result.left.exists(_.length == 3))

    //check the ordering of the exceptions
    assertEquals(
      List("runtime", "inner close", "outer close"),
      result.left.toSeq.flatMap(_.map(_.getMessage)))
  }

   @Test
   def mustNestCaptureAllExceptions_and(): Unit = {
     val r = new ThrowingFakeResource(Some("outer close"))
     val r2 = new ThrowingFakeResource(Some("inner close"))

     val result = managed(r).and(managed(r2)).map {
       case (_, _) => throw new RuntimeException("runtime")
     }.either

     assertTrue("Failed to capture all exceptions",
       result.left.exists(_.length == 3))

     //check the ordering of the exceptions
    assertEquals(
      List("runtime", "inner close", "outer close"),
      result.left.toSeq.flatMap(_.map(_.getMessage)))
   }

   @Test
   def mustSupportValInFor(): Unit = {
     val r = new FakeResource();
     val mr = managed(r)
     val r2 = new FakeResource();
     val mr2 = managed(r2)
      val monad = for { r <- mr
          x = r.generateData
          r2 <- mr2 
          x2 = r2.generateData
      } yield x + x2      
      //This can't compile as the monad is not an extractable resource!!!
      //assertTrue("Failed to extract a result", monad.opt.isDefined)
      assertTrue("Failed to extract a result", monad.map(identity[Double]).opt.isDefined)            
      assertFalse("Failed to close resource", r.isOpened)
      assertFalse("Failed to close resource", r2.isOpened)
   }  



   @Test
   def mustAcquireFor(): Unit = {
     val r = new FakeResource();
     val mr = managed(r)
      assertFalse("Failed to begin closed!", r.isOpened)
      val result = mr.acquireFor { r =>
          assertTrue("Failed to open resource", r.isOpened)
      }      
      assertFalse("Failed to close resource", r.isOpened)
      assertTrue("Failed to get return value", result.isRight)
   }  

  @Test
   def mustCloseOnException(): Unit = {
     val r = new FakeResource();
     val mr = managed(r)
      assertFalse("Failed to begin closed!", r.isOpened)
      val result = mr.acquireFor { r =>
          assertTrue("Failed to open resource", r.isOpened)
          sys.error("Some Exception")
      }      
      assertFalse("Failed to close resource", r.isOpened)
      assertTrue("Failed to catch exception", result.isLeft)
   }  
  @Test
  def mustCloseOnReturn(): Unit = {
    val r = new FakeResource();

    def foo(): Boolean = {
      val mr = managed(r)
      var result: Boolean = false

      assertFalse("Failed to begin closed!", r.isOpened)

      mr.foreach { r =>
        assertTrue("Failed to open resource", r.isOpened)
        result = true
      }  

      result
    }    

    assertTrue("Failed to return from function", foo())
    assertFalse("Failed to close resource", r.isOpened)
   }  

  @Test
  def mustAcquireAndGet(): Unit = {
   val r = new FakeResource();
   val mr = managed(r)
    assertFalse("Failed to begin closed!", r.isOpened)
    val result = mr.acquireAndGet { r =>
        assertTrue("Failed to open resource", r.isOpened)

        r.generateData
    }
    assertNotNull("Failed to extract a result", result)
    assertFalse("Failed to close resource", r.isOpened)
  }

  @Test
  def mustReturnFirstExceptionInAcquireAndGet(): Unit = {
    val r = new FakeResource()
    val mr = managed(r)(Resource.reflectiveCloseableResource, implicitly[OptManifest[FakeResource]])
    try {
      val _ = mr.acquireAndGet { r => r.generateData }
      fail("Should not make it here, due to previous error!")
    } catch {
      case e: Throwable =>
          assertEquals("Failed to order exceptions appropriately", FakeResource.GEN_DATA_ERROR, e.getMessage)
    }
  }

  @Test
  def mustJoinSequence(): Unit = {
    val resources =  (1 to 10).map(_ => new FakeResource()).toSeq
    val managedResources = resources.map(managed(_))
    val unified = join(managedResources)

    for (all <- unified) {
      assertTrue("Failed to open resources!", all.forall(_.isOpened))
      all.map(_.generateData).sum      //Make sure no errors are thrown...

      assertTrue("Failed to order properly!", all.zip(resources).forall({ case (x,y) => x == y }))      
    }

     assertFalse("Failed to close resource!", resources.forall(_.isOpened))

  }

  @Test
  def mustAllowApplyUsage(): Unit = {
    val r = new FakeResource()

    assertFalse("Failed to begin closed!", r.isOpened)

    val _ = managed(r) acquireAndGet { r =>
        assertTrue("Failed to open resource", r.isOpened)
        r.generateData
    }
    assertFalse("Failed to close resource", r.isOpened)
  }

  @Test
  def mustBeShared(): Unit = {
    val r = new FakeResource()
    val sharedReference = shared(r)

    assertFalse("Failed to begin closed!", r.isOpened)
    val _ = sharedReference acquireAndGet { scoped =>
      assertEquals(scoped, r)
      assertTrue("Failed to open resource", scoped.isOpened)
      for (scoped2 <- sharedReference) {
        assertEquals(scoped, scoped2)
      }
      r.generateData
    }
    assertFalse("Failed to close resource", r.isOpened)
  }

  @Test
  def mustBeSuccessFuture(): Unit = {
    val r = new FakeResource()

    assertFalse("Failed to begin closed!", r.isOpened)

    val result = try {
      Await.result(managed(r).map[String](_ => "OK").toFuture, Duration("1s"))
    } catch {
      case _: Throwable => "KO"
    }

    assertEquals("Successful result", "OK", result)
    assertFalse("Failed to close resource", r.isOpened)
  }

  @Test
  def mustBeFailedFuture(): Unit = {
    val r = new FakeResource()

    assertFalse("Failed to begin closed!", r.isOpened)

    val result = try {
      Await.result(managed(r).map[String](_ => {
        sys.error("Error")
      }).toFuture, Duration("1s"))
    } catch {
      case _: Throwable => "KO"
    }

    assertEquals("Failure result", "KO", result)
    assertFalse("Failed to close resource", r.isOpened)
  }


    @Test
  def mustBeSuccessTry(): Unit = {
    val r = new FakeResource()

    assertFalse("Failed to begin closed!", r.isOpened)

    val result = managed(r).map[String](_ => "OK").tried

    assertTrue("Successful result", result.isSuccess)
    assertFalse("Failed to close resource", r.isOpened)
  }

  @Test
  def mustBeFailedTry(): Unit = {
    val r = new FakeResource()

    assertFalse("Failed to begin closed!", r.isOpened)

    val result = managed(r).map[String](_ => {
      sys.error("Error")
    }).tried

    assertFalse("Failure result", result.isSuccess)
    assertFalse("Failed to close resource", r.isOpened)
  }
}
