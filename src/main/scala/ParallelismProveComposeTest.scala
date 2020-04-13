import java.util.concurrent.{Callable, ExecutorService, Future, TimeUnit}

object ParallelismProveComposeTest extends App{

  /*
We have to prove that map(map(y)(g))(f) == map(y)(f compose g)

searching online, it appears that an important assumption is to assume y is of the form x0::xs0

so y is of the form
  x0::xs0
  x0::x1::xs1
  x0::x1::x2::xs2
    ...
  x0::x2::xs2 ... ::xsn::Nil

Proof:

First we show map(y)(g) == g(x0)::g(x1)::g(x2) ... ::g(xn)::Nil
  map(y)(g)
  map(x0::xs)(g)
  g(x0)::map(xs0)(g)
  g(x0)::g(x1)::g(x2) ... ::g(xn)::Nil

Now we prove map(map(y)(g))(f) == map(y)(f compose g)
Starting from LHS, we manipulate until we have RHS
  map(map(y)(g))(f)
  map(g(x0)::g(x1)::g(x2) ... ::g(xn))(f)
  f(g(x0))::map(g(x1)::g(x2)::g(x3):: ... ::g(xn)::Nil)(f)
  f(g(x0))::f(g(x1))::f(g(x2)):: ... ::f(g(xn)::Nil)
  (f compose g)(x0)::(f compose g)(x1)::(f compose g)(x2):: ... ::(f compose g)(xn)::Nil
  map(x0::x1::x2 ... ::xn::Nil)(f compose g)
  map(y)(f compose g)

   */

}


type Par[A] = ExecutorService => MyFuture[A]

trait MyFuture[A] {
  def get: A
  def get(timeout: Long, unit: TimeUnit): A
  def cancel(evenIfRunning: Boolean): Boolean
  def isDone: Boolean
  def isCancelled: Boolean
}

object Par {
  val myES: ExecutorService = ???

  def unit[A](a: A): Par[A] = (es: ExecutorService) => UnitFuture(a)

  def fork[A](a: => Par[A]): Par[A] = ???

  def lazyUnit[A](a: => A): Par[A] = fork(unit(a))

  def convertToNano(time: Long, units: TimeUnit): Long = ???

  private case class UnitFuture[A](get: A) extends MyFuture[A] {
    def isDone = true
    def get(timeout: Long, units: TimeUnit) = get
    def isCancelled = false
    def cancel(evenIfRunning: Boolean): Boolean = false
  }

  private case class TimeoutFuture[A](a1: Par[A], a2: Par[A], fun: (A, A) => A) extends MyFuture[A] {
    def isDone = true
    def get = {
      val output1 = a1(myES)
      val output2 = a2(myES)

      while (!output1.isDone && !output2.isDone){}

      fun(output1.get, output2.get)
    }
    def get(timeout: Long, units: TimeUnit) = {

      val start = System.nanoTime()
      val output1 = a1(myES)
      val output2 = a2(myES)

      val callable3 = new Callable[A]{
        override def call: A = { fun(output1, output2)}
      }
      val output3 = myES.submit(callable3)

      val nanoTimeout = units match {
        case TimeUnit.DAYS => TimeUnit.DAYS.toNanos(timeout)
        case TimeUnit.HOURS => TimeUnit.HOURS.toNanos(timeout)
        case TimeUnit.MINUTES => TimeUnit.MINUTES.toNanos(timeout)
        case TimeUnit.SECONDS => TimeUnit.SECONDS.toNanos(timeout)
        case TimeUnit.MILLISECONDS => TimeUnit.MILLISECONDS.toNanos(timeout)
        case TimeUnit.MICROSECONDS => TimeUnit.MICROSECONDS.toNanos(timeout)
        case TimeUnit.NANOSECONDS => TimeUnit.NANOSECONDS.toNanos(timeout)
      }

      while(System.nanoTime() - start < nanoTimeout && !output3.isDone){}

      if (!output3.isDone) {
        output1.cancel(true)
        output2.cancel(true)
        output3.cancel(true)
        throw new RuntimeException("Future timed out")
      } else output3.get
    }
    def isCancelled = false
    def cancel(evenIfRunning: Boolean): Boolean = false
  }

  def map2[A, B, C](a: Par[A], b: Par[B])(f: (A, B) => C): Par[C] = {
    (es: ExecutorService) => {
      TimeoutFuture(a, b, f)
    }
  }

  def asyncF[A, B](f: A => B): A => Par[B] = {
    (a: A) => lazyUnit(a)
  }

  def sequence[A](ps: List[Par[A]]): Par[List[A]] = {
    ps.foldRight(Par(List()): Par[List[A]]){ case (e, acc) =>
      map2(e, acc)((x, y)=> x::y)
    }
  }

  def parFilter[A](as: List[A])(f: A => Boolean): Par[List[A]] = {
    unit(as.filter(f))
  }
}