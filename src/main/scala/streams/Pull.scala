package streams

import collection.immutable.SortedSet

trait Pull[+F[_],+W,+R] {
  import Pull.Stack

  def run: Stream[F,W] = run0_(SortedSet.empty, Pull.emptyStack[F,W,R])

  private[streams]
  final def run0_[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
    implicit S: Sub1[F,F2]): Stream[F2,W2]
    =
    Stream.suspend { run1_(tracked, k) }

  /**
   * The implementation of `run`. Not public. Note on parameters:
   *
   *   - `tracked` is a map of the current in-scope finalizers,
   *     guaranteed to be run at most once before this `Pull` terminates
   *   - `k` is the stack of work remaining.
   */
  protected def run1_[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
    implicit S: Sub1[F,F2]): Stream[F2,W2]
}

object Pull {

  val done: Pull[Nothing,Nothing,Nothing] = new Pull[Nothing,Nothing,Nothing] {
    type W = Nothing; type R = Nothing
    def run1_[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[Nothing,F2]): Stream[F2,W2]
      =
      k (
        (_,_) => runCleanup(tracked),
        new k.H[Stream[F2,W2]] { def f[x] = (kh,k) =>
          if (kh.ors.isEmpty) done.run0_(tracked, k)
          else kh.ors.head().run0_(tracked, k push kh.copy(ors = kh.ors.tail))
        }
      )
  }

  def fail(err: Throwable): Pull[Nothing,Nothing,Nothing] = new Pull[Nothing,Nothing,Nothing] {
    type W = Nothing; type R = Nothing
    def run1_[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[Nothing,F2]): Stream[F2,W2]
      =
      k (
        (_,_) => runCleanup(tracked) ++ Stream.fail(err),
        new k.H[Stream[F2,W2]] { def f[x] = (kh,k) =>
          if (kh.handlers.isEmpty) fail(err).run0_(tracked, k)
          else kh.handlers.head(err).run0_(tracked, k push kh.copy(handlers = kh.handlers.tail))
        }
      )
  }

  def pure[R](a: R): Pull[Nothing,Nothing,R] = new Pull[Nothing,Nothing,R] {
    type W = Nothing
    def run1_[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[Nothing,F2]): Stream[F2,W2]
      =
      k (
        (_,_) => runCleanup(tracked),
        new k.H[Stream[F2,W2]] { def f[x] = (kh,k) =>
          kh.bind(a).run0_(tracked, k)
        }
      )
  }

  def onError[F[_],W,R](p: Pull[F,W,R])(handle: Throwable => Pull[F,W,R]) = new Pull[F,W,R] {
    def run1_[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      = {
        val handle2: Throwable => Pull[F2,W,R] = handle andThen (Sub1.substPull(_))
        p.run0_(tracked, push(k, handle2))
      }
  }

  def flatMap[F[_],W,R0,R](p: Pull[F,W,R0])(f: R0 => Pull[F,W,R]) = new Pull[F,W,R] {
    def run1_[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      = {
        val f2: R0 => Pull[F2,W,R] = f andThen (Sub1.substPull(_))
        p.run0_[F2,W2,R0,R2](tracked, k.push(Frame(f2)))
      }
  }

  def eval[F[_],R](f: F[R]) = new Pull[F,Nothing,R] {
    type W = Nothing
    def run1_[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      =
      Stream.eval(S(f)) flatMap { r => pure(r).run0_(tracked, k) }
  }

  def write[F[_],W](s: Stream[F,W]) = new Pull[F,W,Unit] {
    type R = Unit
    def run1_[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      =
      Sub1.substStream(s) ++ pure(()).run0_(tracked, k)
  }

  def or[F[_],W,R](p1: Pull[F,W,R], p2: => Pull[F,W,R]): Pull[F,W,R] = new Pull[F,W,R] {
    def run1_[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      =
      Sub1.substPull(p1).run0_(tracked, push(k, Sub1.substPull(p2)))
  }

  private[streams]
  def scope[F[_],W,R](inner: Long => Pull[F,W,R]): Pull[F,W,R] = new Pull[F,W,R] {
    def run1_[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      =
      Stream.scope(id => Sub1.substPull(inner(id)).run0_(tracked, k))
  }

  private[streams]
  def track(id: Long): Pull[Nothing,Nothing,Unit] = new Pull[Nothing,Nothing,Unit] {
    type W = Nothing; type R = Unit
    def run1_[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[Nothing,F2]): Stream[F2,W2]
      =
      pure(()).run0_(tracked + id, k)
  }

  private[streams]
  def release(id: Long): Pull[Nothing,Nothing,Unit] = new Pull[Nothing,Nothing,Unit] {
    type W = Nothing; type R = Unit
    def run1_[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[Nothing,F2]): Stream[F2,W2]
      =
      Stream.release(id) ++ pure(()).run0_(tracked - id, k)
  }

  private[streams] def runCleanup(s: SortedSet[Long]): Stream[Nothing,Nothing] =
    s.iterator.foldLeft(Stream.empty)((s,id) => Stream.append(Stream.release(id), s))

  private[streams]
  case class Frame[F[_],W,R1,R2](
    bind: R1 => Pull[F,W,R2],
    ors: List[() => Pull[F,W,R1]] = List(),
    handlers: List[Throwable => Pull[F,W,R1]] = List())

  private trait T[F[_],W] { type f[a,b] = Frame[F,W,a,b] }
  private[streams]
  type Stack[F[_],W,A,B] = streams.Chain[T[F,W]#f, A, B]

  private[streams]
  def emptyStack[F[_],W,R] = streams.Chain.empty[T[F,W]#f,R]

  private[streams]
  def push[F[_],W,R1,R2](c: Stack[F,W,R1,R2], p: => Pull[F,W,R1]): Stack[F,W,R1,R2] =
    ???
  private[streams]
  def push[F[_],W,R1,R2](c: Stack[F,W,R1,R2], h: Throwable => Pull[F,W,R1]): Stack[F,W,R1,R2] =
    ???
}
